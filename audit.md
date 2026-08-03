# Talaria Manual Code Audit

Reviewed: 2026-08-03

> **STATUS — 2026-08-04:** superseded as the risk statement. The v0.8.0 remediation wave + follow-ups resolved **41 of the 52 remaining Medium/Low findings** (13 High all fixed); 4 remain present and 7 are only partially resolved. Full re-verification per finding is in `review-reports/improvements.md`; remaining work is tracked in `ROADMAP.md` (P0/P1). This file is preserved as the original finding record.

This was a manual, read-only review of every Kotlin source file under app/src/main/java, every resource under app/src/main/res, every unit test under app/src/test, AndroidManifest.xml, the Gradle configuration, and plan.md. No build, test, scanner, fuzzer, SAST tool, Codex Security workflow, or other automated analysis was run. The requested gradle/libs.versions.toml file does not exist in this repository; dependency versions are declared inline in app/build.gradle.kts.

### BUG-001: [High] Network clients can combine one server URL with another profile's credentials
**File:** app/src/main/java/com/hermesgadget/talaria/core/network/HermesClientFactory.kt:64
**Description:** Retrofit is cached by base URL alone, while AuthInterceptor, ProfileQueryInterceptor, and WsAuthHelper resolve the active profile later when each request runs. A profile switch can therefore leave an in-flight request pointed at server A but attach server B's token/profile; profiles sharing a URL can also reuse a client built with the wrong certificate pin or logging configuration.
**Root Cause:** Connection identity is mutable global state instead of an immutable property of a request/client, and the cache key does not represent the full transport configuration.
**Fix:** Introduce an immutable ConnectionSnapshot containing connection id, management profile, URL, credentials, pin, and logging policy. Build/cache REST and WebSocket clients by that snapshot, pass it through each operation, and cancel old-scope calls and sockets on a switch.

### BUG-002: [Medium] HTTP logging exposes WebSocket authentication query values
**File:** app/src/main/java/com/hermesgadget/talaria/core/network/HermesClientFactory.kt:53
**Description:** BASIC HttpLoggingInterceptor is installed on the same client used for WebSocket upgrades. PTY/event URLs carry ticket, token, and attach values in the query string, so enabling diagnostics writes live credentials to logcat.
**Root Cause:** The application treats REST and credential-bearing WebSocket URLs as one logging surface and does not redact URL query parameters.
**Fix:** Use a separate non-logging WebSocket client, or replace the logger with one that strips ticket, token, and attach parameters before output. Keep authentication headers redacted as well.

### BUG-003: [High] Editing a connection silently retains credentials for a different server or auth mode
**File:** app/src/main/java/com/hermesgadget/talaria/core/data/repo/ConnectionRepository.kt:75
**Description:** Blank secret fields preserve every previous password, bearer token, session token, and OIDC refresh token even when the base URL or authentication mode changes. AuthInterceptor also sends a retained session token in NONE mode, so changing a profile to a new host can disclose the old host's credential.
**Root Cause:** “Blank means keep” is applied without considering whether the secret remains compatible with the edited URL/authentication mode.
**Fix:** Clear all secrets when the base URL changes, clear secrets not used by the selected auth mode, and offer explicit per-secret Keep/Clear controls when editing. Never send a retained token in NONE mode unless the user deliberately selected a loopback-token mode.

### BUG-004: [Medium] Encrypted connection updates can lose concurrent changes
**File:** app/src/main/java/com/hermesgadget/talaria/core/data/prefs/SecureConnectionStore.kt:67
**Description:** upsert, updateSessionToken, and updateOidcTokens perform unsynchronized read-modify-write cycles across StateFlow and EncryptedSharedPreferences. A WebSocket token refresh, OIDC refresh, and UI save occurring together can overwrite a newer profile or secret record.
**Root Cause:** Thread-safe preference calls do not make the multi-step logical transaction atomic.
**Fix:** Serialize all profile/secret mutations with one Mutex, reload the latest record inside the lock, persist profile and secrets in one editor transaction, and update flows only after the commit has been prepared.

### BUG-005: [High] Responses can be persisted under the profile active at completion instead of at request start
**File:** app/src/main/java/com/hermesgadget/talaria/core/data/repo/HermesRepository.kt:118
**Description:** refreshSessions and loadMessages make the network request and only afterward call connId to choose the Room namespace. A switch during the request can store server A's sessions/messages under server B's scope; cached reads have the analogous risk because the cache key and fetch can resolve active state at different times.
**Root Cause:** The repository repeatedly reads mutable active-profile state during one logical operation.
**Fix:** Capture a ConnectionSnapshot and scope id before any suspension, use a client bound to it, and write only to that captured scope. Discard the result if the owning ViewModel no longer accepts that generation.

### BUG-006: [Medium] Deleted remote sessions remain indefinitely in the offline cache
**File:** app/src/main/java/com/hermesgadget/talaria/core/data/repo/HermesRepository.kt:118
**Description:** Refresh only upserts returned sessions and repository delete/prune paths do not remove corresponding Room rows. Deleted, pruned, or expired sessions can continue appearing in offline observers forever.
**Root Cause:** The cache has insertion/update behavior but no deletion or expiry policy.
**Fix:** Delete rows explicitly after successful destructive operations, add age-based expiry, and reconcile only against a complete server page rather than treating a limited recent page as authoritative.

### BUG-007: [Medium] Message cache replacement can leave a session empty
**File:** app/src/main/java/com/hermesgadget/talaria/core/data/repo/HermesRepository.kt:189
**Description:** loadMessages clears the existing messages and then inserts the replacement in separate DAO calls. Cancellation, process death, or a database error between them destroys the last-good offline transcript.
**Root Cause:** A logical replace operation is not enclosed in a Room transaction.
**Fix:** Add a database transaction/DAO replace method that clears and inserts atomically, retaining the old rows unless the full replacement succeeds.

### BUG-008: [Medium] One failed profile refresh erases that profile from the registry snapshot
**File:** app/src/main/java/com/hermesgadget/talaria/core/network/ProfileRegistry.kt:96
**Description:** Per-profile failures are omitted from sessionsByProfile, replacing last-good data with absence. Chat synchronization can then treat sessions as gone; runCatching inside async also converts CancellationException into an ordinary per-profile failure instead of cancelling the refresh.
**Root Cause:** Partial failure and cancellation are represented as empty/missing data.
**Fix:** Preserve the last successful snapshot for failed profiles, expose freshness/error metadata separately, and always rethrow CancellationException before collecting ordinary failures.

### BUG-009: [High] Restored agent watches connect to the current server, not the watch's recorded server
**File:** app/src/main/java/com/hermesgadget/talaria/core/notifications/AgentTaskNotificationService.kt:109
**Description:** PersistedAgentWatch carries connectionId and managementProfile, but watch creates HermesEventClient from the globally active connection. Restoring watches from multiple connections can listen to the wrong channel/server and dispatch events labeled with the original watch identity.
**Root Cause:** Watch metadata is used only for notification labeling, not transport construction.
**Fix:** Construct each event client from a profile-bound snapshot resolved by record.connectionId and record.managementProfile. If that profile is unavailable, keep the watch paused with a visible error instead of attaching elsewhere.

### BUG-010: [High] Notification workers have a profile-switch time-of-check/time-of-use race
**File:** app/src/main/java/com/hermesgadget/talaria/worker/PairingApproveWorker.kt:37
**Description:** PairingApproveWorker and ReplyWorker compare the active profile with notification metadata, then perform approval or PTY creation through globally active clients. A switch between the check and request can approve a pairing or send a private reply to a different server/profile.
**Root Cause:** The safety check and the network action are not bound to the same immutable connection.
**Fix:** Resolve the notification's connection id to a snapshot, create a client directly from it, include its management profile explicitly, and reject the job if the saved profile no longer exists. Do not mutate global active state to execute background work.

### BUG-011: [High] Background sync can label one profile's data as another profile
**File:** app/src/main/java/com/hermesgadget/talaria/worker/HermesSyncWorker.kt:36
**Description:** The worker captures scopeId, then pollForNotifications and activity writes continue to resolve global active state. Switching profiles during the poll can store fingerprints/status under the old scope while fetching or recording data for the new one; the catch-all Throwable handler also turns worker cancellation into retry/error notifications.
**Root Cause:** A long-running worker is not connection-bound and treats cancellation as an operational failure.
**Fix:** Pass one captured ConnectionSnapshot through the entire poll and persistence path, rethrow CancellationException, and emit notification deep-link metadata from that same snapshot.

### BUG-012: [High] Managed files, media, and ElevenLabs voices are not reliably management-profile scoped
**File:** app/src/main/java/com/hermesgadget/talaria/core/network/HermesApi.kt:759
**Description:** File list/read callers use the optional profile default, download and upload-stream expose no profile parameter, getElevenLabsVoices exposes none, and ProfileQueryInterceptor omits /api/files, /api/media, and /api/audio. Reads and writes can therefore hit the default Hermes profile while the UI claims another profile is selected.
**Root Cause:** Profile scoping is split between optional endpoint parameters and an incomplete path allowlist.
**Fix:** Require an explicit profile on every scoped API method, include it in every caller, and centralize endpoint scoping in a typed/profile-bound API rather than a manually maintained prefix list. Add contract tests for all scoped endpoint families.

### BUG-013: [Medium] Release builds permit cleartext credentials to arbitrary hosts
**File:** app/src/main/res/xml/network_security_config.xml:16
**Description:** The release base configuration allows HTTP for every user-supplied host, so session tokens, prompts, transcripts, and voice data can cross an untrusted network in plaintext. The ConnectionProfile allowCleartext field is informational and defaults true rather than enforcing an explicit risk decision.
**Root Cause:** LAN development compatibility is implemented as a global release exception.
**Fix:** Default to HTTPS, require an explicit per-profile cleartext confirmation with a persistent warning, and restrict cleartext to verified loopback/private-network destinations where feasible. Do not present pinning as protection for HTTP.

### BUG-014: [Medium] MCP OAuth permits non-loopback HTTP authorization pages
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/mcp/McpScreen.kt:179
**Description:** Any http or https authorization_url supplied by the dashboard is opened. A plain-HTTP remote URL can expose authorization codes, login credentials, or consent decisions to interception.
**Root Cause:** Scheme validation treats HTTP and HTTPS as equally safe without checking the host.
**Fix:** Require HTTPS for remote hosts and permit HTTP only for verified loopback callback/authorization URLs. Surface a clear error naming the rejected host and scheme.

### BUG-015: [Low] One-time webhook secrets remain visible and enter normal clipboard history
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/webhooks/WebhooksScreen.kt:215
**Description:** The complete secret remains rendered until manual dismissal and is copied as an ordinary clip, allowing keyboard/clipboard previews and history to retain it.
**Root Cause:** Secret display and copy use the same helpers as non-sensitive text.
**Fix:** Mask by default with a timed reveal, mark the clip sensitive with ClipDescription.EXTRA_IS_SENSITIVE, clear it after a short interval where supported, and clear local secret state when the screen leaves composition.

### BUG-016: [High] The car service trusts every host application
**File:** app/src/main/java/com/hermesgadget/talaria/car/TalariaCarService.kt:38
**Description:** HostValidator.ALLOW_ALL_HOSTS_VALIDATOR lets any installed app act as a car host and invoke Talaria's authenticated conversation surface. Sideloaded distribution does not make arbitrary host packages trustworthy.
**Root Cause:** A development validator is used unconditionally in production.
**Fix:** Allow only known Android Auto/Automotive host signatures and any explicitly supported development host in debug builds. Keep ALLOW_ALL_HOSTS_VALIDATOR behind BuildConfig.DEBUG.

### BUG-017: [High] PTY sends report success even when no frame was accepted
**File:** app/src/main/java/com/hermesgadget/talaria/core/network/PtyWebSocketSession.kt:132
**Description:** sendText/sendRaw ignore both a null socket and OkHttp WebSocket.send's Boolean result. Chat immediately clears the draft, appends an optimistic user turn, starts monitoring, and marks working even while disconnected or after the queue rejects the frame.
**Root Cause:** The transport exposes fire-and-forget Unit methods and the UI has no delivery state.
**Fix:** Return a delivery Result for both body and Enter frames, reject sends until Connected, keep the draft/attachments until acceptance, and show retry/failed state if either frame is rejected.

### BUG-018: [High] Reply and car prompt delivery relies on a fixed delay rather than an acknowledgement
**File:** app/src/main/java/com/hermesgadget/talaria/worker/ReplyWorker.kt:54
**Description:** ReplyWorker and CarSessionsRepository wait for any first PTY output, sleep 350 ms, call the unchecked send, and immediately close the socket. A slow, busy, differently rendered, or back-pressured TUI can still drop the prompt while the worker/car UI reports success.
**Root Cause:** Banner output and elapsed time are being used as a proxy for input readiness and prompt consumption.
**Fix:** Add a server/TUI readiness signal and a prompt-accepted or transcript-visible acknowledgement. Keep the socket attached until confirmation, make retry idempotent with a client message id, and fail visibly when confirmation times out.

### BUG-019: [Medium] Initial WebSocket events can be lost before collectors subscribe
**File:** app/src/main/java/com/hermesgadget/talaria/core/network/HermesEventClient.kt:86
**Description:** events is a zero-replay SharedFlow, while chat and the notification service call start before launching collectors. Fast authentication failures, model/catalog responses, or early session events emitted without a subscriber disappear.
**Root Cause:** Startup ordering depends on coroutine scheduling and the event stream has no replayed state.
**Fix:** Subscribe before opening sockets, or model connection/auth/model/catalog as replaying StateFlows and use a buffered channel for ordered transient events.

### BUG-020: [Medium] Immediate-open/close WebSocket loops never exhaust reconnect attempts
**File:** app/src/main/java/com/hermesgadget/talaria/core/network/HermesEventClient.kt:308
**Description:** Reconnect attempts are cleared as soon as onOpen fires. A server that accepts the upgrade and immediately closes for auth/protocol reasons repeatedly returns to attempt zero, causing endless reconnect traffic and battery use.
**Root Cause:** TCP/WebSocket open is treated as a stable successful connection.
**Fix:** Reset the attempt counter only after a stability interval or successful protocol heartbeat, classify terminal close codes, and stop with a user-visible error after the retry budget.

### BUG-021: [High] The 30-second session poller can steal a new local session from its originating tab
**File:** app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatViewModel.kt:679
**Description:** New local tabs initially have no server session id. syncActiveSessions may observe and auto-open their newly created session first, add it to claimedSessions, and leave the original tab unable to discover it because discovery excludes claimed ids.
**Root Cause:** Session ownership is inferred independently by the auto-open poller and per-tab discovery with no atomic claim or originating-channel correlation.
**Fix:** Track pending local creations by channel/client id and exclude their candidate sessions from auto-open until discovery resolves. Centralize ownership in one atomic allocator and add a timeout/reconciliation path for orphaned tabs.

### BUG-022: [Medium] Reading-mode polling launches overlapping loads that can regress the transcript
**File:** app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatViewModel.kt:2037
**Description:** The poll loop calls loadReading, which launches another coroutine and returns immediately. Slow requests can overlap; an older equal-length response passes the size guard and overwrite newer equal-length content.
**Root Cause:** Poll iterations do not await their fetch and transcript freshness is approximated by list length.
**Fix:** Make loadReading suspend and serialize it in the poll job, cancel superseded requests, and guard updates with a monotonically increasing request generation or server revision.

### BUG-023: [Medium] Prompt response text can be reused for a different prompt
**File:** app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatScreen.kt:255
**Description:** clarifyText is remembered only by prompt.message. Consecutive SECRET/SUDO/clarification requests with the same message reuse the prior response and can submit an old password or answer to a new request.
**Root Cause:** Human-readable text is used as prompt identity and sensitive state is not cleared explicitly.
**Fix:** Key state by request id plus kind/session, clear it on submit/deny/dismiss and when the prompt object changes, and avoid retaining secret values in saveable state.

### BUG-024: [Medium] Chat server-STT capability is stale across connection changes
**File:** app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatViewModel.kt:344
**Description:** resetForConnectionScope cancels jobs but does not reset serverSttChecked or serverSttUnavailable. After switching to a different server, a previous 404 can permanently force on-device dictation, while a previous successful probe can incorrectly attempt unsupported server STT.
**Root Cause:** Capability state is ViewModel-global rather than keyed by connection/profile scope.
**Fix:** Store capability results per immutable scope with expiry, reset the active projection on scope change, and invalidate it after relevant HTTP/protocol failures.

### BUG-025: [Medium] Chat recording is not cancelled on scope reset or ViewModel destruction
**File:** app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatViewModel.kt:2413
**Description:** onCleared and resetForConnectionScope cancel coroutine jobs and sockets but never call voiceRecorder.cancel. Navigating away or changing connections while recording can leave MediaRecorder and its cache file alive until process cleanup.
**Root Cause:** The recorder is not owned by a closeable lifecycle path.
**Fix:** Call voiceRecorder.cancel from both resetForConnectionScope and onCleared, and make recorder cancellation idempotent.

### BUG-026: [Medium] Server dictation bypasses the normal composer update pipeline
**File:** app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatViewModel.kt:1939
**Description:** A successful transcription writes the tab draft directly with updateTab. It does not run slash/reference analysis or persist the draft, so the visible text and composer metadata/storage diverge and the transcript can disappear after recreation.
**Root Cause:** The STT path duplicates draft mutation instead of using the canonical updateDraft behavior.
**Fix:** Route the result through a tab-scoped canonical draft updater that performs analysis and persistence, and verify the tab/scope generation before applying the response.

### BUG-027: [Medium] ANSI cleanup corrupts data at WebSocket frame boundaries
**File:** app/src/main/java/com/hermesgadget/talaria/core/util/AnsiStripper.kt:28
**Description:** Every individual frame is trimEnd'ed after ANSI removal. Significant trailing spaces/newlines are lost before frames are concatenated, and split escape sequences are not handled as a stream.
**Root Cause:** Terminal output is parsed as independent complete strings.
**Fix:** Use a stateful streaming ANSI parser and preserve ordinary whitespace. Apply presentation trimming only after a complete logical message is assembled.

### BUG-028: [Medium] Picture-in-picture snapshots can exceed the Binder transaction limit
**File:** app/src/main/java/com/hermesgadget/talaria/feature/pip/PipChatActivity.kt:67
**Description:** All selected message strings and streaming text are copied into Intent extras without a byte budget. A large transcript can trigger TransactionTooLargeException or prevent the PiP activity from launching.
**Root Cause:** The handoff limits neither message count by encoded size nor total payload bytes.
**Fix:** Pass a lightweight snapshot id backed by in-process/shared storage, or truncate to a conservative UTF-8 byte budget with an explicit continuation indicator.

### BUG-029: [Medium] Scoped deep links can be consumed during navigation-host replacement
**File:** app/src/main/java/com/hermesgadget/talaria/ui/navigation/TalariaNavRoot.kt:117
**Description:** Deep-link handling lives inside key(activeScope). applyScope changes active state and then suspends to invalidate auth before navigating with the old NavController, so recomposition can dispose the effect and lose the destination. A profile-only deep link is also ignored unless it contains a valid connection id.
**Root Cause:** Scope selection and destination navigation are performed inside the subtree that the scope change destroys.
**Fix:** Resolve/apply the target scope above the keyed host, queue the destination until the new host exists, consume the link only after successful navigation, and apply a valid profile independently of whether connectionId is present.

### BUG-030: [Medium] The profile switcher retains options from the previous connection after failure
**File:** app/src/main/java/com/hermesgadget/talaria/ui/components/ProfileSwitcherBar.kt:64
**Description:** hermesNames is remembered across active connection changes and deliberately kept on load failure. The menu can therefore offer server A's profiles while server B is active, allowing an invalid scope selection.
**Root Cause:** Fetched option state is not keyed by connection identity and failure is represented by stale success data.
**Fix:** Key/reset the list on active id, expose loading/error states, retain cached values only per connection, and reject a selection not present in the current connection's confirmed list.

### BUG-031: [Medium] One-shot SpeechRecognizer flows remain open after final results
**File:** app/src/main/java/com/hermesgadget/talaria/core/voice/SpeechCoordinator.kt:174
**Description:** For continuous=false, onResults emits Final but neither closes the callbackFlow nor destroys the recognizer. The collecting job and platform recognizer remain alive until another action or ViewModel teardown cancels them.
**Root Cause:** Only error/caller cancellation closes the flow; successful one-shot completion has no terminal transition.
**Fix:** Close after emitting a final or empty terminal result when non-continuous, and let awaitClose stop/destroy the recognizer exactly once.

### BUG-032: [Medium] Voice features are disabled unless both server STT and TTS exist
**File:** app/src/main/java/com/hermesgadget/talaria/feature/voice/VoiceViewModel.kt:71
**Description:** Capability refresh marks the whole screen unavailable unless capabilities.isComplete, and startRecording also requires completeness. An STT-only server loses server transcription, while a TTS-only server loses otherwise functional speech playback.
**Root Cause:** Two independent capabilities are collapsed into one all-or-nothing phase.
**Fix:** Model STT and TTS availability separately, enable each control independently, and show on-device dictation only as the STT fallback while preserving server TTS.

### BUG-033: [High] Voice recordings have no duration or size limit and are copied repeatedly in memory
**File:** app/src/main/java/com/hermesgadget/talaria/feature/voice/VoiceRecorder.kt:37
**Description:** Recording can continue indefinitely. Both VoiceViewModel and chat read the entire file and Base64-encode it into a data URL, creating multiple large byte/string copies that can exhaust cache, heap, or the HTTP request budget.
**Root Cause:** The data-URL API path has no client-side recording quota or streaming representation.
**Fix:** Enforce a short maximum duration and encoded-byte limit in MediaRecorder, stop automatically with clear UX, check file length before allocation, and prefer multipart/streaming upload.

### BUG-034: [Medium] Server-supplied data URLs are decoded without bounds, sometimes on the main thread
**File:** app/src/main/java/com/hermesgadget/talaria/feature/voice/VoiceAudioPlayer.kt:33
**Description:** TTS playback decodes and writes the complete Base64 payload synchronously from a main-dispatched ViewModel call. FilesPreview and artifact preview/share paths similarly materialize unbounded strings and byte arrays, allowing a large or malformed server response to cause an ANR or OOM.
**Root Cause:** Data URLs are treated as small trusted values and decoded eagerly.
**Fix:** Enforce response and decoded-size ceilings before allocation, decode incrementally on Dispatchers.IO, stream media to bounded files, and reject oversized content with a useful error.

### BUG-035: [Medium] Car conversation loading performs up to 100 message requests serially
**File:** app/src/main/java/com/hermesgadget/talaria/car/CarSessionsRepository.kt:57
**Description:** conversations maps every active session and waits for each getSessionMessages request in sequence. With the 100-session limit and 120-second HTTP read timeout, the car screen can remain unavailable for minutes.
**Root Cause:** An N+1 endpoint pattern is executed sequentially with no aggregate latency budget.
**Fix:** Request a server-side conversation summary, or fetch a small visible page with bounded concurrency and one short aggregate timeout. Load additional rows/messages lazily.

### BUG-036: [Medium] The car screen leaks its executor and can update after destruction
**File:** app/src/main/java/com/hermesgadget/talaria/car/SessionListScreen.kt:53
**Description:** A new single-thread ExecutorService is stored as Executor and never shut down. It captures screen/car context, and queued tasks can call invalidate after the screen has been destroyed.
**Root Cause:** Background work is not tied to the Screen lifecycle.
**Fix:** Store ExecutorService and shut it down in onDestroy, cancel outstanding work, and guard main-thread callbacks with a destroyed flag; a lifecycle CoroutineScope is preferable.

### BUG-037: [Medium] Artifact refresh fans out one request per session without a concurrency bound
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/artifacts/ArtifactsViewModel.kt:242
**Description:** loadArtifacts starts an async message request for every session in the recent slice at once. This creates a burst of network work, response parsing, and memory pressure on dashboards with many sessions.
**Root Cause:** A bounded input count is assumed to make unbounded parallelism safe.
**Fix:** Use limitedParallelism or a semaphore, page results, cancel on refresh/scope change, and ideally expose a server-side artifacts index.

### BUG-038: [Medium] Stale artifact preview requests can reopen or replace the current preview
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/artifacts/ArtifactsViewModel.kt:162
**Description:** openPreview launches a new job without cancelling the prior job or checking artifact identity at completion. Selecting B or closing the preview while A is loading allows A's later response to replace/reopen the UI.
**Root Cause:** Asynchronous results are applied to global preview state without a request generation.
**Fix:** Keep and cancel a preview Job, increment a generation for open/close, and apply results only when both generation and artifact path still match.

### BUG-039: [Medium] Cache-backed share files have no cleanup lifecycle
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/artifacts/ArtifactsViewModel.kt:271
**Description:** Artifact, backup, and debug-share flows create cache files and hand out FileProvider URIs, but consuming the share request only clears UI state. Repeated sharing accumulates potentially sensitive artifacts, backups, and diagnostic URLs until Android happens to evict them.
**Root Cause:** File creation is duplicated across features without ownership or expiry.
**Fix:** Centralize share-file creation in a manager that records expiry, deletes files after a safe chooser grace period, cleans stale files at startup, and caps total cache usage.

### BUG-040: [Medium] Slow directory responses can move the Files UI back to an older path
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/files/FilesViewModel.kt:148
**Description:** Each open call launches independently and unconditionally applies its response. Rapid navigation or refresh can let an older request finish last and replace the newer directory, parent, and entries.
**Root Cause:** Directory requests have no cancellation, requested-path check, or generation token.
**Fix:** Cancel the previous open job and apply the response only if its generation/requested path is still current.

### BUG-041: [Medium] Managed-file preview and fallback upload materialize unbounded payloads
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/files/FilesTransfer.kt:53
**Description:** readContent grows a ByteArrayOutputStream until EOF, while preview parsing fully decodes Base64 into memory. Large SAF documents or server files can exhaust heap; progress callbacks can continue throughout the allocation.
**Root Cause:** The non-streaming compatibility paths have no maximum and use whole-value API contracts.
**Fix:** Reject the fallback above a documented byte limit, use the streaming upload endpoint for larger content, preflight Content-Length/data-URL length, and decode previews on IO with strict type-specific limits.

### BUG-042: [Medium] A failed SAF save loses the only reference to the downloaded cache file
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/files/FilesViewModel.kt:573
**Description:** saveDownload converts Ready to Saving and then Failed on output error. Failed carries no File, so the user cannot retry and onCleared cannot delete the orphaned download.
**Root Cause:** Error state discards the resumable resource it still owns.
**Fix:** Preserve the Ready payload alongside the error or delete it deterministically. Provide Retry/Cancel actions and make onCleared delete files from every owning state.

### BUG-043: [Low] File transfers trigger a UI update for every 8 KiB chunk
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/files/FilesViewModel.kt:539
**Description:** Download/save/upload progress updates MutableStateFlow on every buffer operation. Fast transfers can schedule thousands of Compose recompositions and slow the transfer itself.
**Root Cause:** Raw I/O progress is exposed directly as render-state frequency.
**Fix:** Throttle by time or meaningful percentage/byte increments and always emit the terminal value.

### BUG-044: [Low] Managed path joining accepts separators and dot segments
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/files/FilesTransfer.kt:106
**Description:** joinManagedPath trims only surrounding slashes and accepts embedded slashes, backslashes, "." and "..". If server-side normalization is incomplete, a create/upload name can address an unintended location.
**Root Cause:** A user-entered child name is concatenated as a path fragment.
**Fix:** Validate a single opaque child name, reject separators/control characters/dot segments, and rely on a server endpoint that resolves children beneath an authorized directory.

### BUG-045: [High] Editing raw YAML during a save can silently mark unsent text as saved
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/system/SystemViewModel.kt:622
**Description:** saveRawConfig sends current.yaml, but on success reads the latest Ready state and sets savedYaml to ready.yaml. If the user types while the request is running, newer unsent YAML becomes the saved baseline and the dirty indicator disappears.
**Root Cause:** The completion handler does not retain the exact submitted revision.
**Fix:** Capture submittedYaml and a generation, set savedYaml only to submittedYaml, preserve any newer draft as dirty, and validate the response's semantic ok/error status before reporting Saved.

### BUG-046: [Medium] System hook mutations report success on HTTP-200 semantic failures
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/system/SystemViewModel.kt:381
**Description:** createHook and deleteHook discard the action response, reload, and show “saved/deleted” whenever transport calls complete. An API response with ok=false/error can therefore be presented as success.
**Root Cause:** Retrofit transport success is treated as operation success.
**Fix:** Inspect ok/success/error on every mutation response, throw a typed domain error before reload when rejected, and keep the user's input available for retry.

### BUG-047: [Medium] System import has no size cap and concurrent selections corrupt temp-file ownership
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/system/SystemViewModel.kt:447
**Description:** Selection copies an arbitrary document to cache with copyTo and JSON validation then readText loads it wholly into memory. Selecting again or cancelling while Preparing does not cancel/track the first job; late completion can install stale state, and upload failure discards the only File reference without deleting it.
**Root Cause:** Import preparation has neither a quota nor a generation-aware resource owner.
**Fix:** Track one cancellable import job and temp file, enforce a streamed byte limit, validate JSON incrementally/on IO, guard completion by generation, and retain for retry or delete on every terminal/cancel/onCleared path.

### BUG-048: [Medium] Config import reads and parses arbitrary documents on the main thread
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/config/ConfigScreen.kt:86
**Description:** The activity-result callback calls readText and JSON parsing synchronously with no byte limit. A large selected document can freeze the UI or exhaust heap.
**Root Cause:** SAF input is assumed to be small and trusted.
**Fix:** Launch bounded streaming read and parsing on Dispatchers.IO/Default, show an import loading state, and reject oversize input before assembling a String.

### BUG-049: [Low] Schema-form fields silently reject normal intermediate edits
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/config/ConfigScreen.kt:405
**Description:** updateConfigKey returns the old entire document for an invalid intermediate number/list/object. Users cannot temporarily type "-", clear a number, or build JSON incrementally, and no validation error explains why the field appears stuck.
**Root Cause:** Display text and committed typed value are the same state, with exceptions silently converted to no-op.
**Fix:** Keep per-field string drafts, show inline validation, and update the JSON document only when a draft is valid or the user commits it.

### BUG-050: [Medium] Cron creation loses input on failure and allows overlapping mutations
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/cron/CronScreen.kt:104
**Description:** The form clears immediately after locally valid input, before the server succeeds. CronViewModel's mutate does not reject calls while busy, so repeated taps can create duplicates and an error leaves no original prompt/name to retry.
**Root Cause:** UI state is optimistically destroyed and busy is presentation-only.
**Fix:** Disable/deduplicate mutation entry points in the ViewModel, retain the form until confirmed success, and preserve it with the server error on failure.

### BUG-051: [Medium] Session administration fabricates success counts and ignores semantic failures
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/sessions/SessionAdminViewModel.kt:224
**Description:** Bulk delete reports result.deleted.coerceAtLeast(ids.size), claiming every requested deletion even when fewer succeeded. Bulk/empty delete and import also do not consistently check ok/error, and refresh clears the selection that would identify partial failures.
**Root Cause:** Response counters and HTTP completion are treated as guaranteed success.
**Fix:** Honor exact server counts/status, surface per-id failures when available, retain failed selections, and never coerce a result upward.

### BUG-052: [Medium] Session search and reload results race across filters
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/sessions/SessionsScreen.kt:108
**Description:** reload launches untracked jobs, while only the debounced search job is cancelled. Tab/source/query changes can leave older reload or search responses applying after newer state, including search results filtered with a captured old tab.
**Root Cause:** Related inputs are handled by independent effects without a single latest-request policy.
**Fix:** Combine tab, source, and query into one flow and use flatMapLatest/mapLatest, or guard every response with a shared generation and its complete filter tuple.

### BUG-053: [Medium] The session-import limit is checked only after the whole file is allocated
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/sessions/SessionsScreen.kt:137
**Description:** input.readBytes allocates the entire document before enforcing the 25 MB maximum, then UTF-8 conversion and JSON parsing add more copies. The advertised limit does not prevent an oversized file from causing OOM.
**Root Cause:** Validation occurs after unbounded consumption.
**Fix:** Read at most MAX_IMPORT_BYTES + 1 into a bounded buffer on IO, reject immediately when exceeded, and parse off the main dispatcher.

### BUG-054: [Medium] The context-engine provider cannot be reset to its displayed default
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/plugins/PluginsScreen.kt:561
**Description:** A blank contextSelection is displayed as “Default,” but ProviderPicker omits the default menu item when defaultName is blank and the Save button is disabled for blank selection. Once a custom engine is chosen, the user cannot restore the default.
**Root Cause:** Empty string is both a valid default sentinel and an invalid/unselectable value.
**Fix:** Represent Default as an explicit option/sentinel, always show it, permit saving it, and translate it to the server's clear/default representation.

### BUG-055: [Medium] Malformed skill-write responses default to success
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/skills/SkillsViewModel.kt:553
**Description:** A non-object response returns ok=true, and an object missing both ok and success also defaults true. Protocol errors or unexpected failure payloads can be reported as a successful skill write.
**Root Cause:** The parser is fail-open.
**Fix:** Default to failure, require an explicit success field or documented success shape, propagate error/message, and retain the edited content when confirmation is ambiguous.

### BUG-056: [Medium] Pairing approval errors are discarded
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/pairing/PairingScreen.kt:125
**Description:** The screen calls approvePairing, ignores its Result, and reloads. A failed approval provides no error, permits repeated taps, and may look successful if the pending list changes for another reason.
**Root Cause:** The mutation result is not folded into UI state.
**Fix:** Add a per-request busy state, handle success/failure explicitly, disable duplicate taps, and reload only after confirmed success or as a clearly labeled refresh.

### BUG-057: [Medium] Analytics range refreshes can apply stale data and serialize independent endpoints
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/analytics/AnalyticsScreen.kt:85
**Description:** Every range change or Refresh launches an untracked coroutine; a slow older range can overwrite a newer selection. Analytics, model metrics, and egress calls are then awaited sequentially, extending loading latency.
**Root Cause:** Reload has no latest-generation guard and independent requests are serialized.
**Fix:** Cancel the prior reload or use mapLatest keyed by days, verify the requested range before applying, and fetch independent panels concurrently within structured concurrency.

### BUG-058: [Medium] The quick-entry “Talk” button opens connection setup instead of Voice
**File:** app/src/main/java/com/hermesgadget/talaria/widget/TalariaQuickEntryWidget.kt:47
**Description:** TALK_URI is talaria://connect, and the unit test explicitly locks in that behavior. Even with a configured connection, tapping “Talk” never opens the server voice screen.
**Root Cause:** No Voice deep-link route was added when the widget action was named.
**Fix:** Add a talaria://voice deep link and navigation target, route Talk to it, handle connection absence inside Voice, and update the test to assert the intended destination.

### BUG-059: [Low] “Open in Files” discards the selected review path
**File:** app/src/main/java/com/hermesgadget/talaria/ui/navigation/TalariaNavRoot.kt:374
**Description:** ReviewScreen supplies a path, but the callback ignores it and navigates to the Files root. The user must manually find the file again.
**Root Cause:** The Files route has no path handoff contract.
**Fix:** Encode/pass the selected managed path safely, initialize FilesViewModel at that directory/file, and preserve profile scope.

### BUG-060: [Low] Localized builds contain large English-only surfaces
**File:** app/src/main/res/values-ja/strings.xml:3
**Description:** Localized values files cover only a subset of core strings, while many manage, voice, car, widget, and error labels are in base-only resource splits or hard-coded Kotlin/manifest text. Arabic, Japanese, and Chinese users receive a mixed-language UI.
**Root Cause:** Feature additions did not consistently use or populate the localization resource set.
**Fix:** Move every user-visible literal to resources, translate all base keys in each supported locale, add missing-translation checks, and include car/widget labels and accessibility descriptions.

### BUG-061: [Low] Analytics charts expose no usable semantics
**File:** app/src/main/java/com/hermesgadget/talaria/feature/manage/analytics/AnalyticsScreen.kt:178
**Description:** Daily bars are anonymous Boxes with no date/value semantics and only an aggregate text summary. TalkBack users cannot inspect the data represented by each bar.
**Root Cause:** The visual chart has no accessible parallel representation.
**Fix:** Add per-bar content descriptions/semantics with date and value, provide a readable table or list alternative, and ensure focus order matches chronology.

### BUG-062: [Low] Signed-release task reports CI-signed APKs as unsigned
**File:** app/build.gradle.kts:188
**Description:** assembleSignedRelease checks only keystore.properties after assembleRelease. When CI signing variables select the ci signingConfig but no local file exists, the task logs that the APK is unsigned.
**Root Cause:** Status reporting does not use the same useCiSigning decision as release configuration.
**Fix:** Branch on useCiSigning or the release signingConfig, update the description to cover both sources, and fail the explicitly signed task if neither signing source is configured.

### BUG-063: [Low] The car implementation plan's checklist no longer reflects repository state
**File:** plan.md:118
**Description:** The document says the AAOS path and agent creation are verified, while the corresponding early phases remain unchecked and later architecture/status text mixes completed behavior with future work. This makes it unreliable for handoff and regression planning.
**Root Cause:** Narrative status was appended without updating the canonical phased checklist and exit criteria.
**Fix:** Mark verified items with evidence, separate completed/current/deferred work, reconcile notification/car-layer wording, and keep one authoritative status section.

### BUG-064: [Low] Dependency versions are scattered and the referenced version catalog is absent
**File:** app/build.gradle.kts:120
**Description:** All plugin/library versions are inline and gradle/libs.versions.toml does not exist despite being named in the review scope. Related Compose, lifecycle, Room, test, and car versions cannot be updated or audited from one place.
**Root Cause:** The build has not adopted a version catalog.
**Fix:** Add gradle/libs.versions.toml, enable aliases in settings/build scripts, group related stacks, and keep compatibility comments next to catalog version declarations.

### BUG-065: [Medium] Recent high-risk transport and profile-isolation paths lack behavioral tests
**File:** app/src/test/java/com/hermesgadget/talaria/worker/ReplyWorkerTest.kt:23
**Description:** ReplyWorkerTest tests only deep-link parsing, while the reply/keep-alive handshake, send rejection, profile-switch race, timeout/retry, and cancellation paths are untested. Chat tests cover reducers/pure helpers but not auto-open ownership, prompt timing, server-STT scope changes, overlapping reading polls, AgentTaskNotificationService restoration, car PTY creation, or profile-bound REST/WebSocket behavior.
**Root Cause:** Tests are concentrated on pure parsing/state helpers rather than the asynchronous boundaries changed most recently.
**Fix:** Introduce injectable profile-bound transport/gateway interfaces and deterministic coroutine tests with fake WebSockets/MockWebServer. Cover switch-during-request, first-output-without-readiness, send=false, lost/late events, equal-length stale polls, auto-open versus local discovery, and cleanup on cancellation.

## Executive summary

The audit found 65 issues: 0 Critical, 13 High, 42 Medium, and 10 Low.

The five most important improvements are:

1. Replace mutable global profile lookup with immutable, profile-bound REST/WebSocket/worker snapshots so credentials, writes, and notification actions cannot cross server boundaries.
2. Make PTY prompt delivery acknowledged and retryable, and atomically coordinate local session discovery with the 30-second auto-open poller.
3. Require explicit management-profile parameters for every files/media/audio endpoint and add endpoint-scope contract tests.
4. Bound and stream voice, data-URL, import, preview, and share payloads; make every recorder/temp file lifecycle-owned.
5. Make raw-config saves revision-aware so edits made during an in-flight save remain visibly dirty and cannot be lost.
