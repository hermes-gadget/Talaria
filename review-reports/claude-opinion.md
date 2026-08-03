# Talaria — Independent Principal-Engineer Opinion

**Reviewer:** Claude (Opus 5), acting as an independent principal Android engineer
**Date:** 2026-08-04
**Scope:** `app/src/main/java/**`, `app/build.gradle.kts`, `AndroidManifest.xml`, `app/src/test/**`, `app/src/main/res/**`, CI
**Basis:** source code only. I deliberately did not read `review-reports/` or any other reviewer's notes; every claim below traces to a file and line I read.
**Version reviewed:** v0.8.3 (`app/build.gradle.kts:21`), Hermes API baseline `hermes-v0.19.1`

---

## Overall-verdict

This is a **strong, unusually thoughtful hobby-scale product that has outgrown its architecture.** Roughly 53k lines of Kotlin across 235 files, single Gradle module, manual DI, no annotation processors beyond Room. For a one-app client to a self-hosted agent gateway, the *breadth* is genuinely impressive: chat over a raw PTY WebSocket, a ~25-screen management console, files, git review, kanban, learning graph, voice (server STT/TTS + on-device fallback), widgets, a QS tile, PiP, and a working Android Auto / AAOS car app in one APK.

What makes it stand out is not the feature count — it's the **comment quality**. The codebase is full of comments that encode hard-won protocol knowledge rather than restating the code. `PtyWebSocketSession.kt:244-249` explains *why* the message body and `\r` must be separate frames (bracketed paste vs. an Enter keypress). `PtyWebSocketSession.kt:264-269` cites Hermes' `_RESIZE_RE` by name to explain why a JSON resize frame corrupts the transcript. `AndroidManifest.xml:28-32` explains why `minCarApiLevel` must sit on `<application>` and not the service. These are the notes of someone who debugged the real thing, and they are worth more than most of the tests.

But the same codebase has a **2,830-line `ChatViewModel`** that reaches into a process-wide singleton 134 times across 50 files, a per-tab polling loop that multiplies with server-side activity, and CI that runs the unit tests **only on release tags**. The engineering care is real; the engineering *structure* is what a fast-moving solo project accumulates, and it is now the binding constraint on both correctness and velocity.

My verdict: **ship-worthy for its intended sideloaded, single-operator audience; not yet structurally sound enough to add another major surface without paying down the chat core first.**

---

## What-is-genuinely-good

**1. The connection-snapshot model is the best idea in the codebase.**
`ConnectionSnapshot` (`core/network/ConnectionSnapshot.kt:35`) makes transport identity *immutable and value-typed*: URL, profile, credentials, TLS pin, cleartext decision, and logging policy are captured together. `HermesClientFactory` keys its OkHttp/Retrofit bundles on that value (`HermesClientFactory.kt:44`), and evicts any bundle whose `connectionId` matches but whose snapshot differs (`:86-90`). `AuthInterceptor.ensureSnapshotStillStored()` (`:85`) then throws `IOException` rather than let an in-flight request carry credentials from a profile the user just edited.

This is the right answer to a genuinely hard problem (multi-profile, multi-server, credentials that rotate mid-flight), and it is implemented consistently — including the deliberate decision that WebSocket clients get no HTTP logger because the URL carries the auth ticket (`HermesClientFactory.kt:79`). I would keep this design in a rewrite.

**2. Layered defence on cleartext, done with taste.**
The `network_security_config.xml` comment (lines 3-18) is the most honest security rationale I have read in an Android app this size: it explains *why* the static XML can't be the gate (arbitrary LAN IPs), what replaces it (`CleartextPolicy.check`, `ConnectionSnapshot.kt:130`), and that user CAs are trusted **only** in debug overrides. `CleartextPolicy.isVerifiedDestination` (`:99`) correctly enumerates RFC1918 + link-local + loopback, and `isAutoApprovedLocalHost` (`:121`) draws a sensible line between "no confirmation needed" and "explicit opt-in". `CertificatePinnerFactory.normalizePin` (`security/CertificatePinnerFactory.kt:32`) rejects `sha1/` pins and validates the digest is exactly 32 bytes. That is more rigour than most commercial apps apply.

**3. Pure logic is extracted and tested; the author knows where the seams are.**
62 test files / ~4,685 LOC cover the things that *can* be tested without a device: `SidecarFrameParser`, `AnsiStripper`, `HermesWebSocketUrlBuilder`, `CleartextPolicy` scoping, `QuietHoursPolicy`, `SimpleMarkdown`, `ComposerRefs`, `TalariaDeepLinkParser`, `CertificatePinnerFactory`. The split of `HermesEventClient` into a stateful socket manager plus a **side-effect-free** `SidecarFrameParser` object (`HermesEventClient.kt:540`, explicitly documented as "Kept side-effect-free so it can be unit-tested without sockets") is exactly the right instinct.

`ChatTranscriptPolicy.kt` is a small gem: three pure functions that encode a real product rule — raw PTY output must never be visible during an active turn, because it would leak model reasoning (`:22`, `:26`).

**4. `ShareFileManager` is production-grade.**
`feature/manage/files/ShareFileManager.kt` bounds share files three ways (16 MiB per file, 32 MiB cache, 15-minute TTL), sanitises both prefix and suffix against path/extension injection (`:53-63`), sweeps legacy directories, and cleans up on a `@Synchronized` path with an mtime fallback that survives process death (`:26-31`). This is the standard I'd want applied across the app.

**5. The Room migration is real.**
`TalariaDatabase.MIGRATION_1_2` (`core/data/db/TalariaDatabase.kt:43`) rebuilds `cached_sessions`/`cached_messages` with a compound `(connectionId, id)` primary key because two Hermes servers can legitimately reuse session ids. Rename → create → copy → drop, per table, with the reasoning in the KDoc. Many apps at this stage would have shipped `fallbackToDestructiveMigration()`.

**6. The Android Auto integration is not a checkbox.**
`SessionListScreen` is designed around glanceability: a distinct `+` avatar for "create agent", three one-tap canned prompts so the driver never has to dictate, and live conversations with voice reply via `ConversationCallback`. `MainActivity.kt:71-77` handles the AAOS-vs-projection split correctly (car launcher owns MAIN/LAUNCHER, hand off to `CarAppActivity`), with a documented `--ez force_phone_ui true` test hook. The `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` decision (`car/TalariaCarService.kt:50`) is wrong-by-default but *argued*, with the actual failure mode (OEM-signed gearhead variants silently dropped from the launcher) and the accepted risk written down. I disagree with the conclusion (see below) but I respect the reasoning.

**7. Delivery honesty on a lossy transport.**
`PtySendReceipt` / `PtySendException` (`PtyWebSocketSession.kt:42-55`) refuse to pretend a raw PTY has delivery semantics. The KDoc at `:178-184` says plainly that `WebSocket.send()` returning true means "entered the writer queue" and is "the strongest delivery signal available on this raw PTY protocol", which is why the background paths follow it with a TUI ack. That is the correct engineering posture for an unreliable protocol.

---

## What-concerns-me

### C1. `ChatViewModel` is a 2,830-line god object that the project's own tests admit is untestable

`feature/chat/ChatViewModel.kt` owns: PTY lifecycle, sidecar lifecycle, session-ownership arbitration, a reading-transcript poller, voice dictation (two engines), image attachment + base64 upload, slash-command completion, branch/compact/rename RPCs, notification-service registration, tab persistence, and profile-scope binding. Thirteen responsibilities in one class.

The constructor *looks* injectable (`:229-234`) — and then line 235 defeats it:

```kotlin
private val container = TalariaApp.instance.container
```

Everything real (`clientFactory`, `connectionStore`, `settingsStore`, `notifier`, `agentAlertDispatcher`) comes through that singleton, plus `TalariaApp.instance.getString(...)` for localised copy (`:1194`, `:1374`, `:1402`, …). Across the app that's **134 `TalariaApp.instance` references in 50 files**.

I don't have to argue this point — the codebase argues it for me. From `app/src/test/.../SessionAutoOpenOwnershipBehaviorTest.kt:21-27`:

> *"ChatViewModel.syncActiveSessions is private and constructs its transport from the process singleton, so its claim/creation race is not directly injectable without changing a production interface. These tests still lock down the ownership invariants available through current public APIs…"*

That is an author writing down, in a test file, that the most intricate concurrency in the product — the race where two pollers can claim the same server session — **cannot be tested**. The test that exists instead asserts on `SessionFilters` and `MultiProfileSessionMerger`, which is honest but is not coverage of the thing that breaks.

Symptom of the same problem: the ownership lock is cargo-culted. `claimSession` (`:879`) guards `sessionOwners`/`claimedSessions` with `synchronized(sessionOwnershipLock)`, and its KDoc says the lock covers "when two refresh completions arrive in the same frame" — but callbacks run on `viewModelScope` (`Dispatchers.Main.immediate`), so that race can't occur, while `runtimes`, `pendingLocalCreations`, `autoOpenedTabs`, and `pendingImages` (`:239-270`) are *unsynchronised* mutable maps touched by the same paths. The lock protects the one map that doesn't need it and skips the four that share its lifecycle.

### C2. The chat transport does not scale with server-side activity, and the user doesn't control N

`syncActiveSessions` (`:758`) **auto-opens a tab for every active non-automation session on the server**, including ones the user started on Discord, Telegram, or the CLI. Each tab gets:

- a PTY WebSocket (`chatRepository.openPty`, `:796`)
- a `HermesEventClient` → `/api/events` **and** `/api/ws` sockets (`HermesEventClient.kt:156-157`), each its own `CoroutineScope(SupervisorJob() + Dispatchers.IO)` and a `MutableSharedFlow(replay = 64, extraBufferCapacity = 256)` (`:108`)
- a **2.5-second** REST poll of the full message list (`startReadingPoll`, `:2404`)
- optionally a foreground-service watcher with a *fourth* socket (`AgentTaskNotificationService.watch`, `:138-146`)

On top of that a 30-second registry poll (`SESSION_POLL_INTERVAL_MS`, `:2820`) and a 30-minute widget refresh.

So a user with eight live agent sessions gets **~24 WebSockets and 8 full-transcript HTTP GETs every 2.5 seconds**, on a phone, with N chosen by the *server*, not by the user. There is no cap, no visibility-based suspension (the poll runs off `viewModelScope`, not `repeatOnLifecycle`), and no backoff for idle sessions. `loadReading` refetches and re-maps every message, then diffs whole lists (`:2456-2496`).

The full-list comparison at `:2482` (`lines != tab.readingMessages`) is a decent guard against needless recomposition, but it's paying O(messages) per tab per 2.5s to discover "nothing changed". A `sessions.changed` sidecar frame already exists in the protocol (`SidecarFrameParser` mentions it at `HermesEventClient.kt:546-547`) — the poll should be the fallback, not the primary.

### C3. A real data race in the foreground notification service

`AgentTaskNotificationService.runtimes` is a plain `linkedMapOf()` (`core/notifications/AgentTaskNotificationService.kt:51`). It is mutated from **two different threads**:

- `onStartCommand` → `watch()` / `update()` / `stopWatch()` / `stopAllInternal()` — **main thread**
- `handle()` (`:219`), collected on `serviceScope` = `Dispatchers.IO` (`:50`), which calls `stopWatch()` (`:235`, `:241`) — **IO thread**
- `verifyRuntimeScopes()` (`:168`), also on `serviceScope`, which iterates `runtimes.values` and removes — **IO thread**

`verifyRuntimeScopes` iterating `runtimes.values` while `onStartCommand` inserts is a `ConcurrentModificationException` waiting for a user who switches connections while an agent turn is running. This one I'd class as a genuine bug, not a style objection.

Secondary: `persistAndRefreshForeground()` (`:265`) calls `startForeground(...)` again on **every** event that updates a session id (`:224`), and writes SharedPreferences each time. That's notification churn plus IO on a hot path.

### C4. Two EncryptedSharedPreferences decrypts + JSON parses on **every HTTP request**

- `HermesClientFactory.snapshot()` (`:47`) → `connectionStore.activeSnapshot()` → `readSecrets(id)` → `prefs.getString` on an `EncryptedSharedPreferences` (AES-256-GCM decrypt) + `Json.decodeFromString<ConnectionSecrets>` (`SecureConnectionStore.kt:198-202`).
- `AuthInterceptor.ensureSnapshotStillStored()` (`:85-101`) does it **again** on the OkHttp thread, via `connectionStore.secretsFor(...)` at `:98`.

Plus `HermesClientFactory.bundle()` (`:86-90`) runs a linear scan + filter allocation over the bundle map on every `api()`/`okHttp()` call.

Individually cheap. Multiplied by C2's poll volume, this is a measurable battery and jank cost, and the whole thing is protecting against an edit-during-request race that could be handled by a single generation counter compared under one lock.

### C5. i18n is 29% real

Four locales ship — `values-ar`, `values-ja`, `values-zh`, `values-zh-rTW` — each with **235 strings**. The base locale has **~800** across `strings.xml` plus sixteen `strings_*.xml` files (chat, files, kanban, mcp, memory, messaging, models, plugins, sessions, system, tools, toolset, …). So every feature module added after the initial translation pass is **English-only in all four locales**, and Arabic users get a mostly-English RTL app.

Worse, whole surfaces bypass the resource system entirely:

- **Car** — `SessionListScreen.kt`: `"Create new agent"` (`:138`), `"Talaria agents"` (`:184`), `"Untitled agent"` (`:171`), `"Loading your agents…"` (`:124`), `"Agent created — it's in your list."` (`:219`), and all three quick-start prompts (`:85-98`). Zero `stringResource` in the entire `car/` package.
- **Widget** — `TalariaStatusWidget.kt:63` `"Talaria · connect Hermes"`, and `:89` builds an English plural by hand: `"pairing request${if (n == 1) "" else "s"}"`. That's `plurals` in `strings.xml`, and it is unlocalisable as written.
- **ViewModels** — 18 hardcoded user-visible `error = "…"` literals, e.g. `ChatViewModel.kt:1817` `"Selected images exceed the 25 MB attachment limit"`, `:1881` `"Wait for Hermes to finish starting before sending an image"`, `:1651` `"${key.replace('_',' ')} needs an active Hermes session"` — the last one *constructs English grammar from a protocol key*, which cannot be translated at all.

Meanwhile the app has 765 correct `stringResource` call sites and a full `LocaleManager`. The infrastructure is there; the discipline lapsed. Half-done i18n is worse than none — it advertises support it doesn't deliver.

### C6. CI runs tests only on release tags

`.github/workflows/apk-release.yml` triggers on `push: tags: ["v*"]` and `workflow_dispatch`. Nothing runs on push-to-main or on pull requests. So 62 test files gate exactly one event: cutting a release. There is also **no** Android Lint invocation, **no** ktlint/detekt (root `build.gradle.kts` is 7 lines of `apply false`), and **no** instrumentation run against a minified build — despite `isMinifyEnabled = true` + `isShrinkResources = true` on release (`app/build.gradle.kts:84-85`) and heavy reflection through Retrofit, kotlinx-serialization, Room, and the car app library.

`app/proguard-rules.pro` shows the risk concretely: line 6 keeps `com.hermesgadget.talaria.core.network.dto.**`, a package that **does not exist** (models live in `domain.model`). That rule has been dead since it was written, which tells me nobody has audited what R8 is actually removing. Combined with only 1 `androidTest` file total, a release-only R8 crash would reach users before it reached a test.

### C7. `CleartextPolicyInterceptor` is an application interceptor, so it can be skipped

`HermesClientFactory.kt:122` registers it via `addInterceptor` (application), not `addNetworkInterceptor`. Application interceptors run **once per call**, before redirects and retries. If a configured `https://` host issues a 30x to `http://`, OkHttp follows it without re-entering `CleartextPolicy.check`. The static `network_security_config.xml` permits cleartext at the base config (deliberately, per its own comment), so nothing downstream catches it either. Narrow, but it is precisely the case the policy exists to prevent.

`EmulatorLoopbackInterceptor` (`:123`) is installed in release builds too, and `ConnectionSnapshot.anonymous()` hardcodes `http://10.0.2.2:9119` with `allowCleartext = true` (`ConnectionSnapshot.kt:86-88`). Dev scaffolding shipping in the production client — low impact, wrong on principle.

### C8. Streaming does O(n²) string work and copies the whole UI state per chunk

`appendAssistant` (`:2544-2554`) appends to a `StringBuilder`, then calls `rt.assistantBuffer.toString()` **on every PTY frame** and writes it into state. For a 40 KB assistant turn arriving in ~1 KB chunks, that is ~40 full string copies totalling ~800 KB of garbage. Each one also goes through `updateTab` (`:2321`), which `map`s the entire tab list and copies `ChatUiState`.

The class comment at `:121-124` says the streaming text was moved *out* of `lines` precisely to stop rebuilding the transcript at stream rate — the right diagnosis, but the fix stopped one level short.

On the Compose side, `ChatScreen.kt:220-221`:

```kotlin
val displayLines = visibleTranscriptLines(active, transcriptMode)
val searchedLines = filterTranscriptLines(displayLines, ui.transcriptQuery)
```

Both run on **every recomposition** with no `remember`, allocating a filtered list per frame during streaming. `ChatScreen` also reads `TalariaApp.instance.container` directly at `:152`, `:168`, and `MainActivity.kt:203` — composables bound to a process singleton can't be previewed or screenshot-tested.

### C9. The car app has three structural problems

1. **`runBlocking` on a single-thread executor.** Four sites (`SessionListScreen.kt:211, 240, 257, 281`) block a thread for up to `PROMPT_TIMEOUT_MS = 20_000` (`CarSessionsRepository.kt:248`). With `Executors.newSingleThreadExecutor()` (`:57`), a queued refresh sits behind a 20-second send. `CarSessionsRepository` is already fully `suspend` — the bridge should be a `lifecycleScope`, not `runBlocking`.
2. **Side effects inside `onGetTemplate()`.** `:111` calls `loadConversations()`, which sets `loading = true` and calls `invalidate()` **synchronously from within the template getter** (`:278-279`). Re-entrant invalidation during template construction is exactly what the car host's template quota (5 per step) punishes; exceeding it gets the app killed by the host.
3. **No refresh path.** `conversations` is only reset to `null` after the user sends something (`:225`, `:245`, `:271`). An agent replying while the phone is in the dock **never** updates the car screen. For a `category.MESSAGING` app whose entire purpose is showing live agent conversations, that's a functional gap, not a polish item.

### C10. Protocol classification by substring matching

`SidecarFrameParser.parse` routes on `type.contains("tool")` (`HermesEventClient.kt:598`), `type.contains("usage") || type.contains("cost")` (`:674`), and `type.contains("model")` (`:689`). A future `toolset.changed`, `models.refreshed`, or `cost_center.updated` frame silently becomes a tool card, a usage counter, or a model-label overwrite. The `when` is ordered so earlier loose matches shadow later exact ones. This is the one place where the otherwise careful protocol handling gets sloppy, and it's the place most likely to break on a Hermes upgrade.

Minor, same file: `sendRpc` builds the JSON envelope by **string concatenation** (`:202`) with a hand-rolled escape for `method` only. `params.toString()` happens to be valid JSON so it works, but there's no reason not to use `buildJsonObject` + `json.encodeToString`.

### C11. Car service exposure

`AndroidManifest.xml:145-152` exports `TalariaCarService`, and `createHostValidator()` returns `ALLOW_ALL_HOSTS_VALIDATOR` in **release** (`TalariaCarService.kt:50`). The comment weighs "a hostile host could drive the session UI" and concludes "session data is already on the device" — but the relevant threat isn't a hostile *car host*, it's **any installed app** binding an exported `CarAppService` with no validation and reading agent session titles and message bodies through `ConversationItem`. For a personal sideload that may be an acceptable trade; it should at least be stated as *that* trade. An allowlist that unions the AndroidX sample with a user-approved OEM signature captured on first connect would keep the CUPRA/SEAT case working without opening the service to every app on the device.

### C12. Smaller things worth naming

- **`androidx.datastore.preferences` is declared** (`app/build.gradle.kts:157`) and **never used** — zero references in `src/main`. Meanwhile `SettingsStore` is synchronous SharedPreferences read on whatever thread calls it, including `loadChatState` from the main thread (`ChatViewModel.persistChatState`, `:462`).
- **`exportSchema = false`** (`TalariaDatabase.kt:36`) — after writing a careful hand-rolled `MIGRATION_1_2`, there's no schema JSON to write a `MigrationTestHelper` test against.
- **`material-icons-extended`** (`app/build.gradle.kts:138`) pulls ~10k vector icons for the ~40 actually referenced in `ManageCatalog.kt`. R8 handles most of it, but it's a needless build-time and size tax.
- **`HermesEventClient.start()` doesn't re-check `stopped` after its suspension point.** At `:152` the coroutine suspends in `wsAuth.authQueryParam()`; between resuming and calling `openEvents`/`openRpc` (`:156-157`) there is no suspension point, so a concurrent `stop()`'s `job.cancel()` cannot take effect and two WebSockets are opened that nothing will ever close. One `if (stopped) return@launch` after line 155 closes it.
- **TTS speaks every completed turn on every tab.** `ChatViewModel.kt:2740` calls `tts.speak(full)` from `completeSidecarMessage` with no check that the tab is the active one — a background auto-opened Discord session finishing will read itself aloud over whatever the user is looking at.

---

## What-I-would-change-first

If I owned this for one sprint, in order:

**Week 1 — make the core testable.** Introduce a `ChatDependencies` interface (clientFactory, connectionStore, settingsStore, notifier, a `StringProvider`) and pass it through `ChatViewModel.factory()`. Nothing else changes yet. Then extract `SessionOwnershipRegistry` — `sessionOwners`, `claimedSessions`, `pendingLocalCreations`, `runtimes`, and the `claimSession`/`releaseSession`/`reconcilePendingLocalCreations`/`pendingLocalCandidateIds` logic — into a plain Kotlin class with no Android dependency, and write the test the codebase currently says it can't. That is ~450 lines out of `ChatViewModel` and it's the 450 lines most likely to be wrong.

**Week 1, same day — turn on CI.** A `pull_request` + `push: [main]` trigger running `:app:testDebugUnitTest` and `:app:lintDebug`. Then `assembleRelease` on PRs so R8 breakage surfaces before a tag. That is a 20-line YAML change that immediately makes the existing 4,685 lines of tests load-bearing. Delete the dead `core.network.dto` keep rule while you're in there.

**Week 2 — fix the transport economics.** Extract the reading poll into a `SessionTranscriptStore` shared across tabs, driven primarily by the `sessions.changed` / `message.complete` sidecar frames with the REST poll as a backoff-driven fallback (2.5s → 10s → 30s while idle). Cap concurrently-socketed tabs; keep the rest as cheap list entries that open sockets on focus. Bind the poll to `repeatOnLifecycle(STARTED)` so a backgrounded app stops polling. This is the change that turns "impressive demo" into "app I'd leave installed."

**Week 2 — the concurrency bug.** `AgentTaskNotificationService.runtimes` → `ConcurrentHashMap`, or confine all mutations to a single-threaded dispatcher. Twenty minutes, and it removes a crash that will otherwise show up as an unreproducible user report.

---

## Surprises-and-ideas

**Surprises**

- *Positive:* the resize escape sequence comment (`PtyWebSocketSession.kt:264-269`) cites the Hermes server's own regex by variable name. Someone read the Python source to find out why the transcript was flooding. That level of cross-stack curiosity is rare and it shows everywhere in this file.
- *Positive:* `PtySendReceipt` distinguishing "body frame accepted" from "Enter frame accepted" (`:42-49`). Most clients would have returned `Boolean`. This one models a partial send, and `ReplyWorker` uses it to decide retry-vs-fail (`worker/ReplyWorker.kt:100`).
- *Positive:* the ownership-race test that documents its own inability to test the race (`SessionAutoOpenOwnershipBehaviorTest.kt:21`). I disagree with shipping the untestable design, but writing that down instead of quietly asserting on something easier is real professional honesty.
- *Negative surprise:* the gap between how careful the *transport* layer is and how casual the *car* and *widget* layers are. `ConnectionSnapshot` is textbook; `SessionListScreen` calls `runBlocking` four times and hardcodes English. It reads like two different engineers, or the same engineer at two different energy levels.
- *Negative surprise:* `ChatViewModel.kt:1651` generating user-facing English from a protocol key (`key.replace('_', ' ')`) in an app that ships four locales.

**Ideas**

1. **A `SessionTranscriptStore` is the missing abstraction.** Right now every tab independently fetches, maps, filters, and diffs the same shape of data. One store keyed by `(profile, sessionId)` exposing `StateFlow<List<ChatLine>>` would collapse C2, C8, and half of `loadReading`'s generation/mutex machinery (`:2446-2500`, which exists only because multiple pollers race on the same tab).
2. **Ship a `StringProvider` and fail the build on the gap.** Add a Gradle check that diffs `values/strings*.xml` against each locale and fails above a threshold. The 565-string gap didn't happen because anyone decided it should — it happened because nothing measured it.
3. **The car app should be an event *subscriber*, not a poller.** Reuse `AgentTaskNotificationService`'s existing `/api/events` subscription to push conversation updates into the car screen via `invalidate()`. That fixes C9.3 and removes the `runBlocking` calls in the same change.
4. **Baseline Profile.** With Compose + Glance + this much navigation, an `androidx.baselineprofile` module would measurably improve cold start on the mid-range hardware this app's users actually run. Cheap and mechanical.
5. **Consider extracting the manage console.** Twenty-five screens of Hermes administration share almost nothing with chat except the API client. If this were multi-module (`:core:network`, `:feature:chat`, `:feature:manage`, `:feature:car`), the manage surface's iteration wouldn't recompile the chat core, and the `TalariaApp.instance` shortcut would stop compiling — which is the real benefit.
6. **`SimpleMarkdown` (903 lines) is a liability worth a second look.** A hand-rolled block+inline parser with a code tokenizer (`ui/components/SimpleMarkdown.kt:505`, `:665`, `:796`) rendering *untrusted LLM output*. It's tested and it avoids a dependency, but pathological input (deeply nested emphasis, huge tables) on the main thread during streaming is a plausible ANR. At minimum, cap block count and parse off the main thread.

---

## Top-10-recommendations

Ranked by (impact × likelihood) ÷ effort. My honest priority order, not a checklist.

| # | Recommendation | Why it's here | Effort |
|---|---|---|---|
| **1** | **Enable CI on PRs and `main`**: `testDebugUnitTest` + `lintDebug` + `assembleRelease`. Delete the dead `core.network.dto` ProGuard rule (`proguard-rules.pro:6`). | 62 test files currently gate only tag pushes (`.github/workflows/apk-release.yml`). R8 + reflection ships untested. Highest ratio in the whole list. | Hours |
| **2** | **Break the `TalariaApp.instance` dependency in `ChatViewModel`** (`:235`) and extract `SessionOwnershipRegistry` from the ~450 lines of claim/discovery logic (`:879-999`, `:2414-2429`). | The project's own test file documents that its hardest concurrency is untestable (`SessionAutoOpenOwnershipBehaviorTest.kt:21-27`). Everything else in chat is blocked behind this. | 3-5 days |
| **3** | **Fix the transport economics**: a shared `SessionTranscriptStore`, event-driven with backoff-polling fallback, socket cap, and `repeatOnLifecycle(STARTED)` binding. | 8 auto-opened sessions ⇒ ~24 sockets + 8 full-transcript GETs per 2.5s (`:2404`, `:758`), with N chosen by the server. This is the difference between a demo and a daily driver. | 4-6 days |
| **4** | **`AgentTaskNotificationService.runtimes` → `ConcurrentHashMap`** or confine to one dispatcher (`AgentTaskNotificationService.kt:51`, mutated from main at `:76-79` and from IO at `:219`/`:168`). | A genuine `ConcurrentModificationException`, triggered by switching connections during an active turn. Cheapest real-bug fix available. | 20 min |
| **5** | **Finish or retire i18n.** Translate the 16 `strings_*.xml` files (565 missing per locale), move `car/` and `widget/` to resources, convert the 18 hardcoded ViewModel errors, and fix the hand-built plural at `TalariaStatusWidget.kt:89`. Add a CI check for the gap. | Four advertised locales at 29% coverage, with Arabic RTL mostly English. Half-done i18n over-promises. | 2-3 days + translation |
| **6** | **Cut per-request crypto**: cache the decrypted `ConnectionSecrets` behind a generation counter; replace `AuthInterceptor.ensureSnapshotStillStored()`'s full re-read (`:98`) with a generation compare; make `HermesClientFactory.bundle()` (`:86-90`) not allocate per call. | Two EncryptedSharedPreferences decrypts + JSON parses on every HTTP call, multiplied by #3's poll volume. | 1 day |
| **7** | **Fix the car module**: replace the four `runBlocking` calls (`SessionListScreen.kt:211, 240, 257, 281`) with a lifecycle scope, remove the `invalidate()` side effect from `onGetTemplate()` (`:111`, `:279`), and add an event-driven refresh so agent replies actually appear. | Blocked threads, template-quota risk, and a `category.MESSAGING` app that doesn't show incoming messages. | 2 days |
| **8** | **Fix the streaming hot path**: stop calling `assistantBuffer.toString()` per frame (`:2551`), and `remember` the transcript filter chain in `ChatScreen.kt:220-221`. Gate `tts.speak` (`:2740`) on the active tab. | O(n²) copying plus a full `ChatUiState` copy per PTY chunk; per-frame list allocation in Compose; background tabs reading themselves aloud. | 1 day |
| **9** | **Move `CleartextPolicyInterceptor` to `addNetworkInterceptor`** (`HermesClientFactory.kt:122`) so redirects and retries are checked, and drop `EmulatorLoopbackInterceptor` + the `10.0.2.2` anonymous default (`ConnectionSnapshot.kt:86`) from release. | An https→http redirect currently bypasses the app's only real cleartext gate. Dev scaffolding in the production client. | Half a day |
| **10** | **Tighten `SidecarFrameParser`** to exact type matching with an explicit prefix allowlist (`HermesEventClient.kt:598, 674, 689`), and narrow the car service's `ALLOW_ALL_HOSTS_VALIDATOR` (`TalariaCarService.kt:50`) to a sample-allowlist ∪ user-approved-signature model. | Substring routing will misfire on the next Hermes protocol addition; an exported car service with no host validation is readable by any installed app. | 1-2 days |

---

### Closing note

I want to be clear about what I'm criticising. This is not a sloppy codebase — it is a **careful codebase with a structural problem**, and those are different diseases. The person who wrote `ConnectionSnapshot`, `ShareFileManager`, `MIGRATION_1_2`, and the PTY frame comments knows what good looks like. `ChatViewModel` didn't get to 2,830 lines through carelessness; it got there because every new capability had a legitimate reason to live next to the last one, and no forcing function ever said stop.

Recommendations #1 and #2 *are* that forcing function. Once CI runs on every PR and the chat core can be constructed in a test, the rest of this list becomes ordinary work. Until then, every item on it is optional, which is why none of them have happened.

*Prepared independently from source. No other reviewer's findings were consulted.*
