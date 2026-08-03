# Talaria code quality and architecture review

Review target: `main` at `48fc05d` (`v0.8.2`). Scope: `app/src/main/java`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `settings.gradle.kts`, and `app/src/main/AndroidManifest.xml`. This was a read-only source review: no source files were changed, no build was run, and no mutating Git command was used.

## Summary

The `audit.md` header is no longer an accurate statement of current risk. The remediation commits after the audit resolved 41 of its 52 Medium/Low findings. Four remain present and seven are only partially resolved. By original severity, 34/42 Medium findings are resolved, three remain, and five are partial; 7/10 Low findings are resolved, one remains, and two are partial.

| Original severity | Resolved | Partial | Still present | Total |
|---|---:|---:|---:|---:|
| Medium | 34 | 5 | 3 | 42 |
| Low | 7 | 2 | 1 | 10 |
| **Total** | **41** | **7** | **4** | **52** |

The most important remaining audit work is Room cache reconciliation/atomicity (BUG-006/007), terminal frame-safe ANSI handling (BUG-027), bounded/off-main image decoding (BUG-034), cache-file ownership (BUG-039), stale profile choices (BUG-030), mutation serialization (BUG-050), localization (BUG-060), signed-release reporting (BUG-062), documentation drift (BUG-063), and behavioral coverage (BUG-065).

The independent scan found five P1 improvements: preserve coroutine cancellation throughout suspend APIs, bound the WebSocket event queue, bound and clean up backup downloads, serialize/generation-guard Kanban state transitions, and surface encrypted-store corruption rather than silently replacing it with empty state. At the structural level, Talaria remains a 48,581-line single `:app` module with several very large UI/network facades and multiple screens that own repositories and asynchronous state directly.

No actionable `TODO`, `FIXME`, `XXX`, `HACK`, or `WIP` comment was found under `app/src/main/java`; the apparent `KANBAN_TODO` match is a domain status constant, not technical debt.

## Verified-remaining-findings

Status meanings: **Resolved** means the reported failure mode is no longer present; **Partial** means the remediation addresses part but not all of the original failure mode; **Still present** means the current source still exhibits it. The evidence below cites current `main`, not the old line numbers in `audit.md`.

| ID | Severity | Status | Current evidence |
|---|---|---|---|
| BUG-002 | Medium | **Resolved** | WebSockets use the non-logging client at `app/src/main/java/com/hermesgadget/talaria/core/network/HermesClientFactory.kt:79-81`; the BODY logger is confined to the REST client at `HermesClientFactory.kt:125-145`. |
| BUG-004 | Medium | **Resolved** | One `mutationLock` protects the logical profile/secret record and synchronous commits precede StateFlow publication: `app/src/main/java/com/hermesgadget/talaria/core/data/prefs/SecureConnectionStore.kt:52-54`, `SecureConnectionStore.kt:93-107`, `SecureConnectionStore.kt:118-173`. |
| BUG-006 | Medium | **Still present** | Session refresh only upserts returned rows, while delete/prune do not remove Room rows: `app/src/main/java/com/hermesgadget/talaria/core/data/repo/HermesRepository.kt:128-155`, `HermesRepository.kt:586-599`. `SessionDao` only exposes whole-connection clear, not per-session delete/reconciliation: `app/src/main/java/com/hermesgadget/talaria/core/data/db/Daos.kt:27-36`. |
| BUG-007 | Medium | **Still present** | Message replacement is still two separate DAO calls (`clearSession`, then `upsertAll`) with no `@Transaction`: `app/src/main/java/com/hermesgadget/talaria/core/data/repo/HermesRepository.kt:216-239`, `app/src/main/java/com/hermesgadget/talaria/core/data/db/Daos.kt:38-48`. Cancellation/failure between calls leaves an empty cache. |
| BUG-008 | Medium | **Resolved** | Failed per-profile requests retain last-good sessions and record per-profile freshness/errors; cancellation is rethrown: `app/src/main/java/com/hermesgadget/talaria/core/network/ProfileRegistry.kt:119-178`. |
| BUG-013 | Medium | **Resolved** | The XML still permits cleartext globally, but every factory client installs the cleartext policy interceptor (`app/src/main/java/com/hermesgadget/talaria/core/network/HermesClientFactory.kt:120-123`). Policy restricts HTTP to verified local/private hosts plus an explicit saved setting: `app/src/main/java/com/hermesgadget/talaria/core/network/ConnectionSnapshot.kt:97-137`; saves validate that policy at `app/src/main/java/com/hermesgadget/talaria/core/data/repo/ConnectionRepository.kt:95-103`. Defense now lives in the transport layer rather than XML. |
| BUG-014 | Medium | **Resolved** | OAuth authorization URLs require HTTPS except loopback HTTP, and the validator is invoked before launch: `app/src/main/java/com/hermesgadget/talaria/feature/manage/mcp/McpScreen.kt:186-187`, `McpScreen.kt:815-839`. |
| BUG-015 | Low | **Resolved** | Webhook secrets use sensitive clipboard metadata on supported Android versions, schedule clipboard clearing, and are masked with timed cleanup: `app/src/main/java/com/hermesgadget/talaria/feature/manage/webhooks/WebhooksScreen.kt:68-85`, `WebhooksScreen.kt:105-115`, `WebhooksScreen.kt:256-288`. |
| BUG-019 | Medium | **Resolved** | A FIFO event channel feeds a replaying SharedFlow before collectors attach: `app/src/main/java/com/hermesgadget/talaria/core/network/HermesEventClient.kt:83-120`. A separate new finding below addresses that channel's unbounded capacity. |
| BUG-020 | Medium | **Resolved** | Reconnect attempts reset only after a stability window, with terminal-close handling and bounded backoff: `app/src/main/java/com/hermesgadget/talaria/core/network/HermesEventClient.kt:295-336`, `HermesEventClient.kt:463-505`. |
| BUG-022 | Medium | **Resolved** | Reading polls cancel/join their predecessor and use generation plus mutex ownership before publication: `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatViewModel.kt:2375-2405`, `ChatViewModel.kt:2431-2458`. |
| BUG-023 | Medium | **Resolved** | Prompt UI state is keyed by tab/session/kind/request/instance and text is cleared when identity changes: `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatScreen.kt:255-329`. |
| BUG-024 | Medium | **Resolved** | Server-STT capabilities are cached per connection/profile scope, have a TTL, and are reset on scope changes: `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatViewModel.kt:2133-2217`. |
| BUG-025 | Medium | **Resolved** | Recorder ownership is canceled on scope/reset and `onCleared`: `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatViewModel.kt:355-383`, `ChatViewModel.kt:2156-2169`, `ChatViewModel.kt:2790-2807`. |
| BUG-026 | Medium | **Resolved** | Server STT now routes through the same draft update pipeline as typed input: `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatViewModel.kt:1684-1724`, `ChatViewModel.kt:2239-2289`. |
| BUG-027 | Medium | **Partial** | `AnsiStripper.Stream` handles fragmented escape sequences and PTY delivery uses it (`app/src/main/java/com/hermesgadget/talaria/core/util/AnsiStripper.kt:21-102`, `app/src/main/java/com/hermesgadget/talaria/core/network/PtyWebSocketSession.kt:127-143`). The terminal then discards the cleaned event text and reprocesses each raw frame with stateless `AnsiStripper.strip`: `app/src/main/java/com/hermesgadget/talaria/feature/terminal/TerminalViewModel.kt:293-296`, `app/src/main/java/com/hermesgadget/talaria/feature/terminal/TerminalOutput.kt:26-35`. Split escapes can still corrupt terminal output. |
| BUG-028 | Medium | **Resolved** | PiP snapshots are capped to 48 KiB and 128 messages; display also retains only the last 24: `app/src/main/java/com/hermesgadget/talaria/feature/pip/PipChatActivity.kt:95-184`, `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatScreen.kt:739-750`. |
| BUG-029 | Medium | **Resolved** | Deep-link intent state is owned above the keyed navigation host and consumed only after graph creation: `app/src/main/java/com/hermesgadget/talaria/ui/navigation/TalariaNavRoot.kt:121-200`, `TalariaNavRoot.kt:439-475`. |
| BUG-030 | Medium | **Still present** | `hermesNames` is remembered independently of connection identity, and failure deliberately retains the prior list: `app/src/main/java/com/hermesgadget/talaria/ui/components/ProfileSwitcherBar.kt:64-75`. A failed refresh after switching connections therefore exposes stale options. |
| BUG-031 | Medium | **Resolved** | The one-shot recognizer uses a terminal `finish` path and `awaitClose` cleanup: `app/src/main/java/com/hermesgadget/talaria/core/voice/SpeechCoordinator.kt:180-229`. |
| BUG-032 | Medium | **Resolved** | Voice availability treats STT and TTS independently and guards each action by its own capability: `app/src/main/java/com/hermesgadget/talaria/feature/voice/VoiceViewModel.kt:71-108`, `VoiceViewModel.kt:147-160`, `VoiceViewModel.kt:247-262`. |
| BUG-034 | Medium | **Partial** | Voice audio and file/artifact input now have encoded-size bounds and voice decoding is off-main. However artifact and managed-file images are still decoded synchronously during composition (`app/src/main/java/com/hermesgadget/talaria/feature/manage/artifacts/ArtifactsScreen.kt:318-320`, `ArtifactsScreen.kt:442-449`, `app/src/main/java/com/hermesgadget/talaria/feature/manage/files/FilesScreen.kt:643-647`) with no decoded-pixel budget or sampling, leaving ANR/decompression-OOM risk. |
| BUG-035 | Medium | **Resolved** | Car session loading now uses a six-request semaphore and ten-second aggregate timeout: `app/src/main/java/com/hermesgadget/talaria/car/CarSessionsRepository.kt:66-91`, `CarSessionsRepository.kt:246-248`. |
| BUG-036 | Medium | **Resolved** | The executor is shut down on lifecycle destruction and late callbacks are guarded: `app/src/main/java/com/hermesgadget/talaria/car/SessionListScreen.kt:55-106`, `SessionListScreen.kt:299-321`. |
| BUG-037 | Medium | **Resolved** | Artifact loading owns a cancellable generation and limits concurrent work: `app/src/main/java/com/hermesgadget/talaria/feature/manage/artifacts/ArtifactsViewModel.kt:145-178`, `ArtifactsViewModel.kt:282-298`. |
| BUG-038 | Medium | **Resolved** | Preview requests have job/generation/path identity checks and close cancels ownership: `app/src/main/java/com/hermesgadget/talaria/feature/manage/artifacts/ArtifactsViewModel.kt:189-255`, `ArtifactsViewModel.kt:300-311`. |
| BUG-039 | Medium | **Partial** | Artifact shares now use a TTL/count/size-managed cache (`app/src/main/java/com/hermesgadget/talaria/feature/manage/artifacts/ArtifactsViewModel.kt:136-142`, `app/src/main/java/com/hermesgadget/talaria/feature/manage/files/ShareFileManager.kt:20-127`). System backup/debug shares still create unmanaged raw cache files and consuming a share only clears UI state: `app/src/main/java/com/hermesgadget/talaria/feature/manage/system/SystemViewModel.kt:544-631`, `SystemViewModel.kt:712-714`, `SystemViewModel.kt:779-785`. Their expiry is not scheduled or owned. |
| BUG-040 | Medium | **Resolved** | Directory navigation cancels the previous job and validates both generation and requested path: `app/src/main/java/com/hermesgadget/talaria/feature/manage/files/FilesViewModel.kt:149-198`, `FilesViewModel.kt:834-835`. |
| BUG-041 | Medium | **Resolved** | Inline uploads have a hard cap and larger transfers use a streaming request body; preview reads are bounded: `app/src/main/java/com/hermesgadget/talaria/feature/manage/files/FilesTransfer.kt:26-28`, `FilesTransfer.kt:93-156`, `app/src/main/java/com/hermesgadget/talaria/feature/manage/files/FilesViewModel.kt:879-893`. |
| BUG-042 | Medium | **Resolved** | Failed SAF saves retain the pending cache file for retry and `onCleared` deletes it: `app/src/main/java/com/hermesgadget/talaria/feature/manage/files/FilesViewModel.kt:615-688`, `FilesViewModel.kt:837-862`. |
| BUG-043 | Low | **Resolved** | Progress is throttled by 64 KiB or 100 ms, with a terminal update: `app/src/main/java/com/hermesgadget/talaria/feature/manage/files/FilesTransfer.kt:30-67`. |
| BUG-044 | Low | **Resolved** | Child names reject separators, control characters, and dot segments: `app/src/main/java/com/hermesgadget/talaria/feature/manage/files/FilesTransfer.kt:158-169`. |
| BUG-046 | Medium | **Resolved** | Hook mutations validate semantic response success rather than treating every HTTP 200 as success: `app/src/main/java/com/hermesgadget/talaria/feature/manage/system/SystemViewModel.kt:392-459`, `SystemViewModel.kt:798-807`. |
| BUG-047 | Medium | **Resolved** | System import now has cancellation/generation ownership, a bounded copy, IO dispatch, and temp cleanup: `app/src/main/java/com/hermesgadget/talaria/feature/manage/system/SystemViewModel.kt:461-541`, `SystemViewModel.kt:723-785`. |
| BUG-048 | Medium | **Resolved** | Config imports read a bounded document on IO and parse on Default: `app/src/main/java/com/hermesgadget/talaria/feature/manage/config/ConfigScreen.kt:126-149`, `ConfigScreen.kt:491-519`. |
| BUG-049 | Low | **Resolved** | Runtime schema editing keeps independent display drafts and field errors, including intermediate invalid text: `app/src/main/java/com/hermesgadget/talaria/feature/manage/config/ConfigScreen.kt:83-124`. The obsolete helper left behind is reported as a new dead-code improvement below. |
| BUG-050 | Medium | **Partial** | The create form retains input on failure and clears only after successful mutation/reload (`app/src/main/java/com/hermesgadget/talaria/feature/manage/cron/CronScreen.kt:56-90`, `CronScreen.kt:135-143`). UI buttons respect `busy`, but `CronViewModel.mutate` itself never rejects a call while already busy, so rapid or programmatic re-entry can overlap: `app/src/main/java/com/hermesgadget/talaria/feature/manage/cron/CronViewModel.kt:258-282`. |
| BUG-051 | Medium | **Resolved** | Session administration uses response-derived counts, fails closed on semantic ambiguity, and retains only failed selections: `app/src/main/java/com/hermesgadget/talaria/feature/manage/sessions/SessionAdminViewModel.kt:226-295`, `SessionAdminViewModel.kt:452-498`. |
| BUG-052 | Medium | **Resolved** | Search/reload is a single effect keyed by all inputs and uses generation plus tuple validation before publication: `app/src/main/java/com/hermesgadget/talaria/feature/manage/sessions/SessionsScreen.kt:99-154`. |
| BUG-053 | Medium | **Resolved** | Session import performs a bounded `limit + 1` read before allocating the complete input: `app/src/main/java/com/hermesgadget/talaria/feature/manage/sessions/SessionsScreen.kt:156-174`, `SessionsScreen.kt:578-600`. |
| BUG-054 | Medium | **Resolved** | The provider menu explicitly includes the default/blank value and the save action accepts it: `app/src/main/java/com/hermesgadget/talaria/feature/manage/plugins/PluginsScreen.kt:542-606`. |
| BUG-055 | Medium | **Resolved** | Skill-write parsing now fails closed on missing/malformed success evidence: `app/src/main/java/com/hermesgadget/talaria/feature/manage/skills/SkillsViewModel.kt:553-565`. |
| BUG-056 | Medium | **Resolved** | Pairing approval tracks a busy key and folds failure into visible state: `app/src/main/java/com/hermesgadget/talaria/feature/manage/pairing/PairingScreen.kt:125-153`. |
| BUG-057 | Medium | **Resolved** | Analytics cancels the previous reload, generation-checks publication, and runs independent endpoints concurrently: `app/src/main/java/com/hermesgadget/talaria/feature/manage/analytics/AnalyticsScreen.kt:75-129`. |
| BUG-058 | Medium | **Resolved** | Widget Talk emits `talaria://voice` and explicitly targets `MainActivity`: `app/src/main/java/com/hermesgadget/talaria/widget/TalariaQuickEntryWidget.kt:48-69`; navigation recognizes Voice at `app/src/main/java/com/hermesgadget/talaria/ui/navigation/TalariaNavRoot.kt:99-118`. External implicit Voice links remain a separate new manifest finding below. |
| BUG-059 | Low | **Resolved** | The review path is encoded into the route and passed as the Files initial path: `app/src/main/java/com/hermesgadget/talaria/ui/navigation/TalariaNavRoot.kt:95-140`, `TalariaNavRoot.kt:407-427`. |
| BUG-060 | Low | **Partial** | Locale catalogs are now populated, but substantial English UI literals remain. Representative surfaces: `app/src/main/java/com/hermesgadget/talaria/feature/manage/cron/CronScreen.kt:93-110`, `CronScreen.kt:219-287`, `app/src/main/java/com/hermesgadget/talaria/feature/manage/system/SystemScreen.kt:237-413`; the quick-entry widget label is also hard-coded in `app/src/main/AndroidManifest.xml:121-125`. |
| BUG-061 | Low | **Resolved** | Every analytics bar supplies a content description through semantics: `app/src/main/java/com/hermesgadget/talaria/feature/manage/analytics/AnalyticsScreen.kt:185-211`. |
| BUG-062 | Low | **Still present** | `assembleSignedRelease` reports signing solely from `keystore.properties`, ignoring valid CI signing selected by `useCiSigning`: `app/build.gradle.kts:186-197` versus `app/build.gradle.kts:25-34`, `app/build.gradle.kts:90-95`. |
| BUG-063 | Low | **Partial** | The checklist/status section is reconciled (`plan.md:8-49`, `plan.md:157-217`), but the technical reference still names/value-documents `minCarAppApiLevel = 2` while the manifest uses `minCarApiLevel = 7`: `plan.md:229-243`, `app/src/main/AndroidManifest.xml:28-35`. |
| BUG-064 | Low | **Resolved** | Dependency/plugin versions are centralized in `gradle/libs.versions.toml:4-100`, and the app consumes catalog aliases at `app/build.gradle.kts:3-9`, `app/build.gradle.kts:120-183`. |
| BUG-065 | Medium | **Partial** | Targeted tests were added for PTY delivery, STT scope, session auto-open, worker input, and profile transitions (`PtyDeliveryBehaviorTest.kt:65-66`, `ServerSttScopeBehaviorTest.kt:50-79`, `SessionAutoOpenOwnershipBehaviorTest.kt:29-48`, `ReplyWorkerDeliveryBoundaryTest.kt:34-35`, `ProfileRegistryTest.kt:24-25`). Missing behavioral boundaries still include late/replayed sidecar events and reconnect, equal-length reading races, notification restoration, car delivery, and active-profile switching during in-flight transport. |

## New-improvements

These do not duplicate the unresolved portion of the old audit. Priority is implementation order/value, not a claim of security severity.

### P1 — Preserve structured cancellation at every suspend boundary

`HermesRepository` wraps many suspend API/Room calls in `runCatching`, which captures `CancellationException` as a normal failure; representative cases are `app/src/main/java/com/hermesgadget/talaria/core/data/repo/HermesRepository.kt:121-125` and `HermesRepository.kt:216-239`. Chat collectors likewise catch all `Throwable` and publish “Chat connection failed” when their owning job is intentionally cancelled: `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatViewModel.kt:563-570`, `ChatViewModel.kt:647-654`, `ChatViewModel.kt:828-835`. Similar patterns occur in Kanban mutations at `app/src/main/java/com/hermesgadget/talaria/feature/manage/kanban/KanbanScreen.kt:227-238`.

Why: cancellation is a control signal, not a domain/network error. Swallowing it permits work after navigation/profile changes, writes misleading error state, and weakens `viewModelScope` ownership. Introduce a small cancellation-transparent `resultOfSuspend` helper (rethrow `CancellationException`) and use it consistently; in `catch (Throwable)`, always rethrow cancellation first.

### P1 — Bound the pre-SharedFlow WebSocket event queue

`HermesEventClient` uses `Channel.UNLIMITED` at `app/src/main/java/com/hermesgadget/talaria/core/network/HermesEventClient.kt:81-83`. Although the downstream SharedFlow is bounded and drops oldest items (`HermesEventClient.kt:108-112`), the unlimited FIFO ahead of it can grow without bound if socket callbacks outpace the dispatcher (`HermesEventClient.kt:118-120`).

Why: a noisy or malfunctioning server can convert transient event pressure into process memory growth. Use a bounded channel with an explicit overflow policy, coalesce replaceable status/catalog events, and retain guaranteed delivery only for prompt/terminal events that truly require it.

### P1 — Bound, serialize, and clean up System backup downloads

`downloadAndShareBackup` permits repeated launches, streams an unbounded response to disk, and only gains the temp-file reference after the `runCatching` succeeds: `app/src/main/java/com/hermesgadget/talaria/feature/manage/system/SystemViewModel.kt:544-588`. If copying fails, the partial file remains; if two calls overlap, either result can win. The same files also lack the managed TTL lifecycle noted under BUG-039.

Why: remote archive size is server-controlled and repeated actions can consume cache storage or publish stale share state. Own a single job/generation, enforce content-length and streaming byte limits, delete partials in `catch/finally`, and hand completed files to the existing `ShareFileManager`.

### P1 — Serialize and generation-guard Kanban refresh/mutation state

Every refresh launches a new job without cancelling or versioning the old one (`app/src/main/java/com/hermesgadget/talaria/feature/manage/kanban/KanbanScreen.kt:227-238`); board switches and generic mutations launch independently (`KanbanScreen.kt:251-260`, `KanbanScreen.kt:364-381`). Late refreshes can overwrite newer board/mutation results, and internal entry points do not enforce `busy`.

Why: Compose button disabling is not a concurrency boundary. Move request ownership into explicit `Job`/generation fields or a serialized intent reducer; compare board/request identity before publishing, and reject or queue mutations while busy.

### P1 — Do not silently convert encrypted-store corruption into “no connections”

Profile and secret JSON decode failures fall back to empty collections/credentials with no signal: `app/src/main/java/com/hermesgadget/talaria/core/data/prefs/SecureConnectionStore.kt:193-201`. This can make valid connections disappear or turn a storage/migration problem into unexplained authentication failures.

Why: silent data substitution prevents diagnosis and may encourage the user to overwrite recoverable data. Preserve the raw encrypted preference, expose a typed corruption state to connection UI/telemetry, and offer an explicit recovery/reset path. Secrets should fail closed with a distinguishable error rather than an empty credential object.

### P2 — Limit per-profile refresh fan-out

`ProfileRegistry.refresh` creates one IO `async` request per returned profile and awaits all without a semaphore: `app/src/main/java/com/hermesgadget/talaria/core/network/ProfileRegistry.kt:116-138`.

Why: profile counts are server-controlled and a large catalog can burst sockets, memory, and dispatcher work. Reuse the bounded-parallelism pattern already applied to car sessions and artifacts, ideally with per-request and aggregate timeouts.

### P2 — Move repository/API ownership and durable state out of Composables

Several screens fetch the global container directly and own asynchronous state with `rememberCoroutineScope`: Config (`app/src/main/java/com/hermesgadget/talaria/feature/manage/config/ConfigScreen.kt:78-96`), Channels (`app/src/main/java/com/hermesgadget/talaria/feature/manage/channels/ChannelsScreen.kt:69-91`), and Analytics (`app/src/main/java/com/hermesgadget/talaria/feature/manage/analytics/AnalyticsScreen.kt:75-131`) are representative.

Why: screen-local request logic is lost on activity recreation, complicates SavedStateHandle use and tests, and produces inconsistent cancellation/error/race handling. Give each screen a ViewModel with injected gateway interfaces and one immutable `UiState`; leave short-lived visual state in Compose.

### P2 — Split the largest state/UI/network facades

The largest hotspots are `ChatViewModel.kt` (2,830 lines; class starts at `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatViewModel.kt:229`), `ChatScreen.kt` (1,449 lines; the main composable starts at `ChatScreen.kt:139` and spans about 1,193 lines), and `HermesApi.kt` (1,075 lines; interface starts at `app/src/main/java/com/hermesgadget/talaria/core/network/HermesApi.kt:109`). TokenSave's structural scan measured the `ChatScreen` function at cyclomatic complexity 119/cognitive complexity 200 and `McpScreen` at 51/136.

Why: these files mix unrelated state machines and make changes hard to isolate or test. Extract chat runtimes/reading/voice/prompt/session-control coordinators, split screen sections into state-hoisted composables, and divide `HermesApi` into feature-specific Retrofit interfaces composed by the client factory.

### P2 — Establish real Gradle module boundaries

The project declares only `:app`: `settings.gradle.kts:23-24`. `AppContainer` eagerly wires persistence, all transports, notifications, voice, repositories, and lifecycle in one graph: `app/src/main/java/com/hermesgadget/talaria/di/AppContainer.kt:42-70`.

Why: package names provide naming boundaries but no dependency enforcement; every feature can reach the global app graph, and car/widget/voice changes invalidate the whole application module. A staged split into `core:model`, `core:network`, `core:data`, and high-change features (`feature:chat`, `feature:manage`, `feature:voice`, `feature:car`) would enforce direction and reduce build/test scope. Keep Android resources/API DTOs at deliberate boundary modules rather than sharing the app container.

### P2 — Plan removal of the alpha credential-persistence dependency

Credential storage is built on `androidx.security:security-crypto:1.1.0-alpha06`: `gradle/libs.versions.toml:22`, `gradle/libs.versions.toml:82`; `SecureConnectionStore` directly uses `EncryptedSharedPreferences` at `app/src/main/java/com/hermesgadget/talaria/core/data/prefs/SecureConnectionStore.kt:43-48`.

Why: an alpha dependency sits directly on the release credential path and makes storage migration part of future dependency upgrades. Isolate the store behind a versioned persistence interface, add migration/corruption tests, and define a Keystore-backed replacement/migration plan before changing the library.

### P3 — Make external Voice deep links resolvable

Navigation handles `talaria://voice` and the widget works because it explicitly targets `MainActivity`, but the exported VIEW filter lists chat/session/pairing/connect/status/activity/manage and omits voice: `app/src/main/AndroidManifest.xml:57-69`; the workaround is visible at `app/src/main/java/com/hermesgadget/talaria/widget/TalariaQuickEntryWidget.kt:50-67`.

Why: implicit links from shortcuts, adb, browsers, or other apps will not resolve even though internal routing supports them. Add the Voice host to the manifest and retain explicit component targeting for the widget.

### P3 — Remove the test-only duplicate config updater from production

`updateConfigKey` remains in production at `app/src/main/java/com/hermesgadget/talaria/feature/manage/config/ConfigScreen.kt:545-550`, but the runtime uses `updateSchemaField`; the only references are unit tests at `app/src/test/java/com/hermesgadget/talaria/feature/manage/config/ConfigJsonEditorTest.kt:20`, `ConfigJsonEditorTest.kt:28`.

Why: the helper silently returns unchanged input on parse failure, unlike the new field-error path, so its tests can give false confidence about runtime behavior. Test `parseConfigDraft`/`setConfigValueAtPath` or extract the real reducer and delete the duplicate helper.

## Quick-wins

1. Fix `assembleSignedRelease` to report `useCiSigning || keystorePropertiesFile.exists()` and state the selected signing config.
2. Clear `hermesNames` when the connection key changes and expose refresh failure instead of retaining another server's options.
3. Add `@Transaction suspend fun replaceSessionMessages(...)` and use it in `loadMessages`.
4. Replace `Channel.UNLIMITED` with a measured bounded capacity and add a burst/overflow test.
5. Introduce one cancellation-transparent suspend-result helper, then migrate repository and ViewModel `runCatching` call sites mechanically.
6. Add `android:host="voice"` to the VIEW filter and a manifest/deep-link resolution test.
7. Guard `CronViewModel.mutate` and Kanban mutations against re-entry even when called outside the current buttons.
8. Move artifact/file bitmap decode to `Dispatchers.Default`, read bounds first, sample to the display size, and reject excessive decoded pixels.
9. Route System backup/debug output through `ShareFileManager`; delete partial files on every failure path.
10. Delete `updateConfigKey` after moving its tests onto the actual runtime config-edit reducer.

TokenSave's semantic graph was used to cover the full source tree and to prioritize call-graph, complexity, coupling, dead-code, TODO, and test-risk checks; targeted source reads then verified every cited line. Its reported context reduction for this review was approximately 68,800 tokens. Because graph-only dead-code reports can misclassify Compose/Android entry points, only the source-and-reference-confirmed config helper is reported as dead code.
