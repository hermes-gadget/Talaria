# Talaria v0.8.2 Android performance review

## Summary

This is a read-only static review of the Kotlin/Compose source in `the review worktree`. No Git command, build, benchmark, or source edit was performed. The requested version is confirmed by `app/build.gradle.kts:20-21` (`0.8.2`). The reported software-GPU frame p50 improvement from 150 ms to 85 ms was not re-measured.

The four landed fixes are present and directionally correct: the in-flight assistant is separated from the immutable line list (`ChatViewModel.kt:121-127`), markdown parsing is memoized by message text (`SimpleMarkdown.kt:88-90`), identical REST transcripts no longer replace Compose state (`ChatViewModel.kt:2478-2495`), and auto-follow uses a keyed instant `scrollToItem` (`ChatScreen.kt:222-226`). Lazy chat, file, artifact, session, and activity rows also have stable keys (`ChatScreen.kt:1272`, `FilesScreen.kt:558-561`, `ArtifactsScreen.kt:194-199`, `SessionRailPane.kt:128-130`, `ActivityScreen.kt:80-81`).

The largest remaining issue is below the Compose equality guard: every open chat tab starts a 2.5-second loop that fetches the complete transcript, clears its Room rows, and re-inserts every message. The loop is not lifecycle-gated and runs for inactive tabs. This combines network, JSON allocation, SQLite writes, and CPU even when the UI does not change.

The next largest risks are stream-rate state publication (the complete accumulated PTY string is copied into one large `ChatUiState` per frame), unbounded event/chat buffers, three WebSockets per chat tab, a duplicate foreground-service event subscriber during active turns, and high-peak base64/bitmap paths. Cold start also eagerly opens every persisted tab and eagerly initializes encrypted preferences, WorkManager scheduling, notification channels, and Android TTS even though TTS defaults off.

Static priority: fix transcript synchronization and lifecycle ownership first; then coalesce/bound streams and consolidate sockets; then move image/artifact work off main and reduce peak memory. These changes should improve frame tails, disk/network use, memory pressure, and battery together.

## Jank-and-streaming

### J1. PTY frames still copy and publish the whole accumulated output

- **Evidence:** Every WS message allocates a stripped string and a `PtyEvent.Output` containing both stripped and raw strings (`app/src/main/java/com/hermesgadget/talaria/core/network/PtyWebSocketSession.kt:127-142`). `appendAssistant` appends the chunk, calls `StringBuilder.toString()`, maps/copies the tab list, and publishes the full accumulated string (`feature/chat/ChatViewModel.kt:2321-2324`, `2544-2553`). `ChatScreen` collects the entire `ChatUiState` (`feature/chat/ChatScreen.kt:150`) even though active turns force reading mode and do not display the raw stream (`ChatScreen.kt:216-225`, `ChatTranscriptPolicy.kt:25-34`).
- **Impact:** O(total-turn-length) string allocation per frame, repeated large `ChatTab`/tab-list copies, full-screen recomposition invalidation, and young-generation GC. The landed streaming field avoids rebuilding every historical line but does not cap or cadence the live field; while reading mode is active, much of this work is invisible.
- **Suggested fix:** Keep raw PTY data in a bounded transport-owned ring buffer. Publish a sampled snapshot at most once per display frame or every 50-100 ms, and publish nothing to Compose while reading mode is active unless the diagnostic terminal view is visible. Split composer, transcript, connection status, and session rail into narrower flows/state holders so unrelated deltas do not invalidate the whole screen.
- **Effort:** M

### J2. Sidecar deltas allocate state even when the visible value is already true

- **Evidence:** Each `message.delta` appends to a sidecar `StringBuilder` and calls `updateTab { copy(working = true) }` (`feature/chat/ChatViewModel.kt:2590-2600`); interim and status events do the same (`2602-2608`, `2611-2616`). `updateTab` maps the complete tabs list (`2321-2324`). Every sidecar text frame is first parsed into a generic `JsonElement` tree (`core/network/HermesEventClient.kt:438-456`) and then classified with repeated map lookups (`HermesEventClient.kt:544-617`).
- **Impact:** Allocation and list traversal at token/event rate even when `working` is already true. Generic JSON trees add short-lived objects before the typed event is created.
- **Suggested fix:** Guard state mutations (`if (tab.working) tab else tab.copy(...)`), batch low-value progress/status deltas, and decode a small typed event envelope directly instead of materializing a generic `JsonElement` tree for every frame. Keep the final-answer buffer off UI state until completion.
- **Effort:** M

### J3. Stable list keys are fixed, but row work still contains linear scans

- **Evidence:** Chat rows are correctly keyed by `line.id` (`feature/chat/ChatScreen.kt:1272`), and markdown parse is correctly memoized (`ui/components/SimpleMarkdown.kt:88-90`). However, every composed row calls `displayLines.indexOfFirst` (`ChatScreen.kt:1273-1275`). Session-rail rows scan all sessions again to resolve a branch parent (`feature/chat/SessionRailPane.kt:129-135`). Transcript search also scans all lines (`feature/chat/ChatTranscriptPolicy.kt:38-45`), with the count performing the filter again when requested.
- **Impact:** O(n x visible rows) work during unrelated recompositions; on long sessions/search results it consumes the savings from stable keys and memoized markdown.
- **Suggested fix:** Build `id -> index` and `id -> title` maps once with `remember`/derived state, and derive filtered lines plus count in one pass. Keep the existing stable keys and markdown memoization.
- **Effort:** S

### J4. Terminal output is bounded but recopied and laid out as one 120k-character node per frame

- **Evidence:** `TerminalOutputBuffer.text` converts the entire builder to a string, and `append` does so after every PTY frame (`feature/terminal/TerminalOutput.kt:39-57`). `TerminalViewModel` publishes that complete string for every output event (`feature/terminal/TerminalViewModel.kt:293-296`). The UI scrolls on every output string and renders the entire buffer in one `Text` (`feature/terminal/TerminalScreen.kt:99-100`, `179-204`). It also re-strips the raw frame using a fresh `AnsiStripper` (`TerminalOutput.kt:26-35`) even though the PTY session already maintains a streaming stripper.
- **Impact:** Repeated 120k string copies, full text measurement/layout, scroll work, and incorrect/duplicated ANSI work under high terminal output.
- **Suggested fix:** Maintain a stateful ANSI parser once, coalesce frames, store bounded line/chunk objects in a ring buffer, render them with a keyed `LazyColumn`, and follow only when the user is already at the bottom.
- **Effort:** M

### J5. Artifact discovery does regex and recursive JSON work on the main dispatcher

- **Evidence:** `refresh` launches on `viewModelScope` (`feature/manage/artifacts/ArtifactsViewModel.kt:145-178`); after network awaits, `loadArtifacts` performs extraction and sorting without switching to `Dispatchers.Default` (`282-298`). Extraction runs three regex scans per message (`feature/manage/artifacts/ArtifactExtraction.kt:153-157`), parses tool strings as JSON (`83-90`, `202-204`), and recursively walks every array/object while allocating a new dotted key path at each node (`166-199`). Structural recursion does not increment the depth argument (`192-198`); only stringified-JSON recursion is capped.
- **Impact:** Opening/refreshing Artifacts can block Compose on large transcripts/tool payloads, generate substantial garbage, and still stack-overflow on adversarially deep structural JSON.
- **Suggested fix:** Run extraction on `Dispatchers.Default`, add a total node/byte/depth budget with iterative traversal, track only the current key hint rather than a full dotted path, and increment structural depth. Prefer typed artifact metadata from the server if available.
- **Effort:** M

## Memory

### M1. Every sidecar has an unbounded ingress queue

- **Evidence:** Each `HermesEventClient` owns `Channel.UNLIMITED`, then republishes into a flow with replay 64 plus 256 buffered events (`core/network/HermesEventClient.kt:81-112`). Each chat tab constructs its own event client (`feature/chat/ChatViewModel.kt:590-605`) and starts both event and RPC sockets (`core/network/HermesEventClient.kt:138-158`).
- **Impact:** If parsing/dispatch or the main-thread collector falls behind a burst, the channel can grow until process OOM. Replay retains payloads (including raw `JsonObject`s), and the cost multiplies by open tabs/services.
- **Suggested fix:** Use a bounded channel with an explicit policy: never drop prompt/completion/connection boundaries; conflate or drop old deltas/status/tool progress. Reduce replay to the small startup contract actually required and expose diagnostics for drops/high-water marks.
- **Effort:** M

### M2. Chat answer buffers and transcript lists have no size/line ceiling

- **Evidence:** Each runtime owns two uncapped `StringBuilder`s (`feature/chat/ChatViewModel.kt:193-204`). PTY output and sidecar deltas append indefinitely until a completion/close event (`2544-2559`, `2581-2600`, `2712-2719`). Completed messages are appended to unbounded `lines`/`readingMessages` lists (`2560-2574`, `2721-2736`), and the full REST result replaces `readingMessages` (`2460-2492`).
- **Impact:** Runaway tool/PTY output, a missing completion event, or a very long session can retain arbitrarily large strings and message lists per tab. Copying those strings during streaming amplifies peak memory.
- **Suggested fix:** Cap PTY/sidecar buffers by characters/bytes, preserve only a tail for diagnostic mode, page older committed messages, and represent truncated output explicitly. Keep the authoritative full history on the server/Room rather than in every tab state.
- **Effort:** M

### M3. Image attachment RPC has a very high peak allocation

- **Evidence:** Picked images are retained as `ByteArray`s, with a 25 MiB aggregate cap per tab (`feature/chat/ChatImageAttachments.kt:22-31`, `feature/chat/ChatViewModel.kt:1812-1829`). Sending creates a full base64 string (`ChatViewModel.kt:1945-1957`), places it in a `JsonObject`, and `sendRpc` stringifies the entire JSON request again (`core/network/HermesEventClient.kt:190-203`).
- **Impact:** A 25 MiB image can coexist with roughly 33 MiB of base64, a second JSON request string of similar size, and OkHttp's outbound queue copy. Peak heap can exceed 90 MiB before any decoded bitmap and can trigger OOM/long GC on ordinary devices.
- **Suggested fix:** Prefer streaming multipart/file upload or a chunked binary WebSocket contract. Until that exists, downscale/re-encode images, lower the per-turn cap, send one small image at a time, and reject by decoded dimensions as well as compressed bytes.
- **Effort:** L (server contract); M (client mitigation)

### M4. Image previews decode full-resolution bitmaps and retain encoded + decoded forms

- **Evidence:** Artifact previews retain a data URL up to 24 MiB/16 MiB decoded in `StateFlow` (`feature/manage/artifacts/ArtifactsViewModel.kt:46-49`, `213-221`, `381-403`); Compose then base64-decodes and `BitmapFactory.decodeByteArray`s it synchronously (`feature/manage/artifacts/ArtifactsScreen.kt:318-328`, `442-449`). Files retains up to a 16 MiB `ByteArray` in UI state (`feature/manage/files/FilesViewModel.kt:121-126`, `879-893`) and decodes it synchronously without sampling (`feature/manage/files/FilesScreen.kt:643-648`).
- **Impact:** Compressed size does not bound decoded bitmap memory: a large-dimension PNG/JPEG can require width x height x 4 bytes. Encoded string/bytes, temporary decoded bytes, and bitmap overlap; decoding on composition also causes jank.
- **Suggested fix:** Use a streaming image loader (or an endpoint returning bytes) with target-size downsampling, dimension/pixel-count limits, background decode, hardware bitmap eligibility, and lifecycle-aware cache eviction. Do not keep data URLs in Compose state.
- **Effort:** M

### M5. The in-memory response cache never evicts expired or path-keyed entries

- **Evidence:** `ResponseCache` uses an unbounded `ConcurrentHashMap`; `peek` returns null for expired entries but does not remove them (`core/data/repo/ResponseCache.kt:35-50`). Dynamic filesystem paths are cache keys (`core/data/repo/HermesRepository.kt:916-922`).
- **Impact:** Browsing many directories retains every decoded listing for the process lifetime, including expired values. Other large generic responses (config schema, portal, memory, curator) are also held until an explicit profile/cache clear.
- **Suggested fix:** Replace with a size/weight-bounded LRU, remove expired entries on lookup, and avoid caching large path-specific values or cache only a small recent directory set.
- **Effort:** S

### M6. Artifact refresh retains all 50 message responses before extraction

- **Evidence:** The browser fetches 50 sessions with concurrency four (`feature/manage/artifacts/ArtifactsViewModel.kt:46-47`, `282-297`), but awaits every deferred into a complete list before `flatMap` extraction. Extraction also snapshots all discovered keys for every message and then scans the complete key set to find additions (`feature/manage/artifacts/ArtifactExtraction.kt:79-100`).
- **Impact:** Up to 50 full transcript lists plus decoded JSON/tool payloads overlap in memory; key-set copying/scanning becomes quadratic as artifacts accumulate.
- **Suggested fix:** Extract and discard each session response inside the semaphore permit, emit/merge records incrementally, and record the insertion index or return newly added IDs rather than copying/scanning all keys per message.
- **Effort:** M

### M7. Voice is bounded, but Chat bypasses the streaming encoder

- **Evidence:** Voice recording is correctly bounded to 60 seconds/2 MiB and temp files are deleted on normal completion/cancel (`feature/voice/VoiceAudioDataUrl.kt:28-38`, `feature/voice/VoiceRecorder.kt:70-105`, `176-198`). The Voice feature encodes on IO without a full audio byte array (`VoiceViewModel.kt:207-218`, `VoiceAudioDataUrl.kt:46-87`), but Chat uses `audio.file.readBytes()` followed by a full base64 string (`feature/chat/ChatViewModel.kt:2254-2265`).
- **Impact:** Chat STT unnecessarily overlaps the recording byte array and base64 string (roughly 5 MiB at the current cap), increasing GC pressure during a latency-sensitive interaction.
- **Suggested fix:** Reuse `encodeRecordedVoiceDataUrl` from Chat, then move to streaming multipart audio when the server supports it.
- **Effort:** S

## Battery

### B1. The 2.5-second full-transcript poll runs per tab and is not lifecycle-aware

- **Evidence:** Every new/reconnected tab calls `startReadingPoll` (`feature/chat/ChatViewModel.kt:580-581`, `664-665`). Its unconditional loop runs while the ViewModel/runtime exists and delays only 2.5 seconds (`2374-2405`); it does not test active tab, transcript mode, working state, screen lifecycle, or process foreground. Each iteration fetches all messages (`2452-2468`, `2515-2527`). The Compose equality guard occurs only after the response is mapped (`2478-2495`).
- **Impact:** With N tabs, roughly 24N full-history HTTP reads per minute continue for inactive tabs and can continue while Chat is on the back stack/backgrounded. This keeps the radio/CPU/storage active for no visible change and scales poorly with transcript length.
- **Suggested fix:** Treat sidecar `message.complete` as the primary refresh trigger. Keep one adaptive fallback only for the active, visible, working tab (for example 2.5 s while working, then 15-60 s backoff, then stop), cancel below `STARTED`, and use ETag/revision/message-count or incremental `after` semantics.
- **Effort:** M/L (depending on server support)

### B2. The 30-second profile poll fans out across every profile off-screen

- **Evidence:** Chat starts a ViewModel-owned infinite 30-second poll (`feature/chat/ChatViewModel.kt:337-352`, `2820`). Each registry refresh fetches the profile catalog and then concurrently fetches up to 100 sessions for every profile (`core/network/ProfileRegistry.kt:99-138`, `224`). The loop is not tied to `repeatOnLifecycle`.
- **Impact:** Persistent network fan-out and JSON decoding while Chat is inactive; cost grows with profile count. It also publishes loading/new registry states even when contents are equal.
- **Suggested fix:** Lifecycle-gate the loop, prefer `sessions.changed` events, refresh only the selected/visible profile by default, use conditional requests, and skip state publication when semantically unchanged.
- **Effort:** M

### B3. Socket ownership is duplicated across Chat, process observer, and foreground service

- **Evidence:** A chat tab starts PTY + `/api/events` + `/api/ws` (`feature/chat/ChatViewModel.kt:590-665`; `core/network/HermesEventClient.kt:138-158`, `338-435`). Sending also starts a foreground monitor (`ChatViewModel.kt:2034-2041`) which opens another `/api/events` socket for that channel and is `START_STICKY` (`core/notifications/AgentTaskNotificationService.kt:38-42`, `69-83`, `138-150`). The screen's lifecycle block only reconnects on start; it does not stop runtimes on stop (`feature/chat/ChatScreen.kt:201-207`). Terminal similarly reconnects on start but does not disconnect on stop (`feature/terminal/TerminalScreen.kt:85-98`).
- **Impact:** During an active foreground turn the same channel can be parsed by both Chat and the monitor service; background/off-screen ViewModels can keep their sockets alive. Each socket has reconnect/auth overhead and incoming frames cause radio wakeups and JSON work.
- **Suggested fix:** Define one lifecycle owner per channel. While foreground, forward the in-process event stream to notification logic; hand off to a single foreground-service subscriber only when the process backgrounds. Close PTY/RPC for inactive tabs/screens where server work continues independently, or lazily reconnect on selection.
- **Effort:** L

### B4. Recording and continuous STT are not stopped by screen/process lifecycle

- **Evidence:** Chat's on-device fallback uses continuous recognition (`feature/chat/ChatViewModel.kt:2293-2312`); `SpeechCoordinator` restarts after end/no-match with a 350 ms delay (`core/voice/SpeechCoordinator.kt:101-117`, `160-190`, `232-235`). Chat/Voice cancel only on explicit action, scope reset, or `onCleared` (`ChatViewModel.kt:2156-2169`, `2790-2816`; `feature/voice/VoiceViewModel.kt:422-426`), while navigation destinations can remain on the back stack. `VoiceScreen` has no lifecycle disposal (`feature/voice/VoiceScreen.kt:75-118`).
- **Impact:** The microphone/recognizer or MediaRecorder can continue after navigating away/backgrounding (server recording is bounded to 60 seconds, but still consumes power and surprises users). Continuous recognition can repeatedly wake CPU/audio DSP.
- **Suggested fix:** Stop/cancel on `ON_STOP` unless an explicit foreground recording UX/service is active; add a visible time limit for continuous dictation and exponential backoff/terminal timeout for repeated soft errors.
- **Effort:** S/M

### B5. Background sync is reasonable but can be cheaper

- **Evidence:** Background sync defaults on at 30 minutes (`core/data/prefs/SettingsStore.kt:156-162`). WorkManager is constrained only to connected network (`worker/SyncScheduler.kt:32-50`), and each run performs status, pairing, and cron requests sequentially (`core/data/repo/HermesRepository.kt:1019-1040`) with up to three attempts (`worker/HermesSyncWorker.kt:118-149`).
- **Impact:** Three radio/network transactions per run even when notifications for a category are disabled; retries can add wakeups. This is much smaller than the chat poll problem but matters for idle battery.
- **Suggested fix:** Skip disabled categories, consider `BatteryNotLow`/unmetered options for non-urgent sync, add explicit exponential backoff, and use a server batch/changes endpoint if available. Preserve the user-selectable 15-360 minute range.
- **Effort:** S/M

### B6. Keep-alive and wakelock observations

- **Evidence:** The WS client sets timeouts but no `pingInterval` (`core/network/HermesClientFactory.kt:115-145`), so Talaria does not generate periodic OkHttp WebSocket pings. Sidecars use capped exponential reconnect delays and stop after repeated failure (`core/network/HermesEventClient.kt:295-335`). `WAKE_LOCK` is declared (`app/src/main/AndroidManifest.xml:11`), but no application code acquires a manual `WakeLock`; WorkManager/foreground-service infrastructure is the apparent consumer.
- **Impact:** There is no periodic client-ping battery drain and no explicit unbounded wakelock in source. The tradeoff is NAT idle closure/reconnect churn; the dominant cost is the number of sockets and application polling, not keep-alive pings.
- **Suggested fix:** Do not add frequent pings globally. If field data shows idle disconnects, use a conservative server-aligned interval only for the single required foreground socket and continue lifecycle shutdown/backoff.
- **Effort:** S (policy/measurement)

## IO-and-startup

### I1. Every composer keystroke launches a Room write

- **Evidence:** `applyDraft` scans slash/composer suggestions, performs two state updates, and launches `chatRepository.saveDraft(text)` for every change (`feature/chat/ChatViewModel.kt:1711-1731`). `saveDraft` is a Room upsert (`core/data/repo/ChatRepository.kt:47-50`). The entire `ChatUiState` is collected at screen root (`feature/chat/ChatScreen.kt:150`).
- **Impact:** Rapid typing creates coroutine/SQLite write churn and invalidates the whole chat screen. Outstanding writes may complete after newer input and waste flash/CPU; large drafts make suggestion analysis more expensive.
- **Suggested fix:** Keep immediate text locally/narrowly scoped, debounce persistence by 300-500 ms with `distinctUntilChanged`/`mapLatest`, flush on send or lifecycle stop, combine the two state mutations, and precompute the known-agent index.
- **Effort:** M

### I2. Every transcript poll rewrites the complete Room message set

- **Evidence:** `loadReading` calls `HermesRepository.loadMessages` (`feature/chat/ChatViewModel.kt:2452-2457`, `2515-2527`). The repository fetches the complete message list, clears the session table, maps every message to an entity, and upserts all rows (`core/data/repo/HermesRepository.kt:216-239`). This occurs before `lines != tab.readingMessages` is checked (`ChatViewModel.kt:2478-2495`).
- **Impact:** At 2.5 seconds per tab, unchanged sessions still generate full-table delete/insert cycles, Room invalidations, SQLite journal traffic, JSON/object mapping, and flash wear.
- **Suggested fix:** Do not persist unchanged snapshots. Compare server revision/message count/hash first, use one transaction with diff/upsert/delete-tail semantics, or persist only on sidecar completion. Add a uniqueness/revision field so polling can become incremental.
- **Effort:** M

### I3. Files performs duplicate listing and uncancelled preview work

- **Evidence:** `FilesViewModel.init` immediately lists the root (`feature/manage/files/FilesViewModel.kt:157-170`), and the screen refreshes again as soon as it reaches `RESUMED` (`feature/manage/files/FilesScreen.kt:121-127`), cancelling/restarting the first request. Directory responses are unpaged and retained in full (`FilesViewModel.kt:170-184`). Preview jobs are anonymous `viewModelScope.launch` calls (`240-303`); `closePreview` clears state but cannot cancel the in-flight request/decode (`427-443`). Base64 parsing/UTF-8 conversion resumes on Main (`240-289`, `feature/manage/files/FilesPreview.kt:73-96`).
- **Impact:** Extra entry request on navigation, large-directory memory/JSON cost, and continued network/CPU after dismissing a preview. A 16 MiB base64 decode/string conversion can block main.
- **Suggested fix:** Choose either init load or first-resume load, add freshness tracking/pagination, retain and cancel a generation-scoped preview job, and perform base64/text decode on IO/Default. Prefer streaming byte responses over data URLs.
- **Effort:** M

### I4. Artifact discovery is an N+1 full-history scan

- **Evidence:** Refresh fetches 50 recent sessions then one full message request for each, four at a time (`feature/manage/artifacts/ArtifactsViewModel.kt:107-123`, `282-297`). There is no cache/revision check and refresh repeats the complete scan.
- **Impact:** Up to 51 requests plus complete transcript transfer/parse per refresh; high latency, server load, data use, and memory.
- **Suggested fix:** Add a backend artifact index or session summary artifact metadata. Client-only fallback: scan incrementally by session `last_active`/message count, cache per-session extraction by revision, and limit the initial scan more aggressively.
- **Effort:** L (server index); M (client cache)

### S1. Application startup eagerly creates expensive services

- **Evidence:** `Application.onCreate` synchronously builds the full container, applies locale, creates notification channels, updates periodic WorkManager, and installs the observer before first draw (`app/src/main/java/com/hermesgadget/talaria/TalariaApp.kt:29-37`). `AppContainer` eagerly creates encrypted storage, Room, all repositories/clients, speech/TTS, and observer (`di/AppContainer.kt:42-70`). `SecureConnectionStore` creates a Keystore master key/encrypted preferences and immediately decrypts/parses profiles (`core/data/prefs/SecureConnectionStore.kt:38-58`, `193-201`). `TtsSpeaker` binds `TextToSpeech` immediately even though TTS defaults off (`core/voice/TtsSpeaker.kt:25-40`; `core/data/prefs/SettingsStore.kt:164-166`). Notification setup creates 16 channels (`core/notifications/NotificationChannels.kt:61-100`).
- **Impact:** Keystore/provider initialization, service binding, WorkManager DB access, channel binder calls, and object graph construction all sit on the cold-start critical path. TTS also retains a service connection for users who never enable it.
- **Suggested fix:** Make TTS lazy and initialize/shutdown with the enabled setting; lazily create Room/repos/clients; schedule WorkManager only on setting change/boot or defer it after first frame; create channels once/deferred. Keep only the minimal settings/profile data required to choose the first route synchronous.
- **Effort:** M

### S2. Cold start eagerly reopens every persisted chat tab

- **Evidence:** Chats is the connected start destination (`ui/navigation/TalariaNavRoot.kt:200-203`, `285-304`). Restore loops through every saved tab and calls `newSession` (`feature/chat/ChatViewModel.kt:412-431`). Each call opens PTY plus two sidecar sockets, requests model info, refreshes sessions, loads reading history, and starts the 2.5-second poll (`ChatViewModel.kt:584-665`).
- **Impact:** First screen can fan out to 3N WebSockets plus multiple REST calls and N full histories, causing slow first interaction, memory spikes, authentication-ticket churn, and battery/network use for inactive tabs.
- **Suggested fix:** Restore lightweight tab metadata immediately but connect/load only the active tab. Lazily hydrate inactive tabs on selection, cap concurrent reconnects, and show a dormant state. Reconnect genuinely active server work through the notification monitor rather than every historical tab.
- **Effort:** M

### S3. The global event socket is restarted by two owners on launch/scope change

- **Evidence:** `TalariaApp` installs `HermesForegroundObserver`, whose `onStart` starts the global event client and `onStop` closes it (`core/lifecycle/HermesForegroundObserver.kt:56-74`). `TalariaNavRoot` independently stops, invalidates all HTTP clients/auth, and starts the same global event client whenever `activeScope` is observed, including initial composition (`ui/navigation/TalariaNavRoot.kt:151-164`). `HermesEventClient.start` itself calls `stop` then opens sockets (`core/network/HermesEventClient.kt:138-158`).
- **Impact:** Initial/scope transitions can mint tickets, open sockets, probe model/catalog, then immediately close/reopen them. Factory invalidation also discards connection pools on first composition.
- **Suggested fix:** Give the process observer sole socket lifecycle ownership; make scope changes a single explicit rebind event. Avoid factory invalidation when the immutable scope did not actually change.
- **Effort:** S/M

### S4. Splash path has no artificial hold, but the starting window masks synchronous work

- **Evidence:** No `installSplashScreen`/keep condition exists; MainActivity proceeds directly to `setContent` (`app/src/main/java/com/hermesgadget/talaria/MainActivity.kt:63-105`). The activity theme supplies only a solid starting window background (`app/src/main/res/values/themes.xml:3-9`).
- **Impact:** There is no deliberate splash delay, which is good, but all synchronous `Application` work still extends time-to-first-draw behind a blank/solid window and there is no structured exit transition.
- **Suggested fix:** Optimize/defer startup work first. Optionally adopt AndroidX SplashScreen only to provide a consistent branded starting window; do not keep it waiting for network/DI initialization.
- **Effort:** S

### Positive IO observations

- Large managed downloads are streamed to a temp file and then to SAF with throttled progress (`feature/manage/files/FilesViewModel.kt:542-612`, `615-684`), and large uploads use a streaming `RequestBody` (`feature/manage/files/FilesTransfer.kt:128-155`; `FilesViewModel.kt:756-791`).
- Voice recording/playback uses bounded temp files and background decode in the dedicated Voice feature (`feature/voice/VoiceAudioDataUrl.kt:46-139`; `feature/voice/VoiceAudioPlayer.kt:43-100`).
- Poll-based Status/Logs screens use lifecycle-aware `PollEffect` and stop below `RESUMED` (`ui/components/PollEffect.kt:25-47`; `feature/manage/logs/LogsScreen.kt:77-86`; `feature/manage/status/StatusScreen.kt:182-185`). Chat/session polling should adopt the same ownership pattern.

## Prioritized-fixes

| Priority | Fix | Expected result | Effort |
|---|---|---|---|
| P0 | Replace per-tab 2.5 s full transcript polling with sidecar-completion refresh plus lifecycle-gated adaptive fallback; suppress unchanged Room writes | Largest battery, network, disk, JSON, and memory reduction; fewer invisible UI invalidations | M/L |
| P0 | Coalesce/bound PTY and sidecar streams; remove `Channel.UNLIMITED`; publish narrow UI state only when visible | Lower frame tails and GC; prevents burst-driven OOM | M |
| P0 | Restore only active-tab metadata/runtime on cold start and make inactive tabs dormant | Removes 3N socket and N-history startup fan-out | M |
| P0 | Establish a single lifecycle owner for chat/event sockets and hand foreground notification monitoring to the service only in background | Removes duplicate channel subscribers and off-screen radio/CPU use | L |
| P1 | Debounce Room draft persistence and split composer state from the whole `ChatUiState` | Smoother typing and far fewer SQLite writes | M |
| P1 | Stream/downsample image paths; remove data URLs from state; cap pixel dimensions and reduce attachment peak | Prevents image OOM and main-thread decode jank | M/L |
| P1 | Run artifact extraction off main with node/depth/byte budgets and incremental per-session processing/cache | Faster Artifacts open, bounded memory, no recursive-stack hazard | M |
| P1 | Lazy-init TTS/Room/repositories and defer WorkManager/channel maintenance until after first draw or configuration events | Better cold start and lower idle footprint | M |
| P1 | Stop voice/terminal/chat producers below the appropriate lifecycle state | Prevents background microphone, polling, and socket drain | S/M |
| P2 | Bound/evict `ResponseCache`, cancel file previews, avoid double listing, and paginate/cache directories | Lower long-session memory and file-browser IO | S/M |
| P2 | Precompute chat/session lookup maps and render terminal as keyed chunks | Removes remaining O(n x rows) and whole-buffer layout costs | S/M |
| P2 | Tune WorkManager requests by enabled notification categories and explicit backoff/constraints | Modest idle-battery improvement | S/M |

Recommended validation after implementation: Macrobenchmark cold-start and chat scroll/frame timing on a release-like build; Perfetto/FrameTimeline during a high-rate PTY stream; Android Studio allocation profiling for a 10-minute turn and 25 MiB image; Room query/write tracing; and a Battery Historian comparison for 30 minutes foreground, 30 minutes background, one tab versus five tabs. Track p95/p99 and missed-frame count in addition to p50.
