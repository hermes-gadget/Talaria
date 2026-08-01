# Changelog

All notable changes to Talaria are documented here.

## [0.2.1] — 2026-08-01 — Obtainium update fix

### Fixes

- **Obtainium `failureConflict` fixed for real.** GitHub releases now ship a
  **signed release** APK (`com.nousresearch.talaria`, `versionCode` from tag,
  floor 202) instead of the debuggable `*.debug` APK signed with each runner’s
  ephemeral cert. Those debug builds are what Android/Obtainium reported as
  `failureConflict [Talaria]`.
- **One-time migration:** uninstall the old Talaria debug app, remove + re-add
  the app in Obtainium, then install 0.2.1. Later tagged updates apply in-place.

## [Unreleased] — Parity-gap closure

### Fixes

- **STT error 5 (mic):** Chat now requests `RECORD_AUDIO` at tap time (manifest-only
  was not enough). `SpeechRecognizer` is created on the main looper, continuous
  restarts are delayed, and transient `ERROR_CLIENT` / busy are retried with a
  clear message when offline speech isn't available.
- **STT "error 13" now actionable:** missing on-device language pack
  (`ERROR_LANGUAGE_UNAVAILABLE` / `ERROR_LANGUAGE_NOT_SUPPORTED`) previously showed a
  cryptic "STT error 13". It now explains the two fixes — install the on-device speech
  pack, or enable cloud STT in You — without silently falling back to cloud (which would
  break the offline-by-default privacy promise).
- **Chat stuck Disconnected after closing the app:** dead PTY tabs are reopened on
  foreground (`repeatOnLifecycle`) and via tap-to-reconnect, resuming the prior
  Hermes session id when known.
- **Chat forgot the conversation after a full force-close:** a process kill wiped the
  in-memory tabs, so a cold start opened a blank new agent even though it reconnected.
  The active tab's Hermes session id is now persisted per connection profile
  (`SettingsStore.lastSessionId`) and resumed on cold start — verified on-device:
  after force-stop + relaunch the transcript reappears **and** the agent still answers
  from prior context (asked to recall a word set before the kill, it did).
- **Loopback WS 403 `no_credential`:** when `auth_required=false`, auto-fetch
  `__HERMES_SESSION_TOKEN__` from the dashboard HTML and attach `?token=` (REST
  worked without it; `/api/pty` / `/api/ws` did not).
- **Emulator `10.0.2.2` Host rejection:** network interceptor rewrites `Host` to
  `127.0.0.1` so Hermes loopback DNS-rebinding guards accept the upgrade.
- **Sidecar JSON-RPC:** quote `method` in `sendRpc` — unquoted `model.info` was
  parse-rejected on every connect (`gui.log`).

### Performance

- **Polling no longer runs off-screen:** a new lifecycle-gated `PollEffect` replaces the
  `LaunchedEffect { while (isActive) … }` loops in Status (5s) and Logs (3s). A plain
  `LaunchedEffect` keeps firing while the app is backgrounded; `PollEffect` uses
  `repeatOnLifecycle(RESUMED)` so the network poll pauses on background and resumes on
  return — saving battery, CPU and network. Foreground cadence verified unchanged (exact 5s).
- **Fewer background recompositions:** Chat's UI state now collects via
  `collectAsStateWithLifecycle()` (was `collectAsState()`), so it stops recomposing when
  the screen isn't visible.
- **Stable list keys** added to the Pairing (pending/approved), model-picker and
  key/value lists so item updates reuse composables instead of rebuilding the list.
- Profiled with `dumpsys gfxinfo` across menu navigation: 50th %ile 16ms, 90th 19ms,
  95th 21ms, no frame over 32ms (60fps) — the read-through cache removed the load stalls.
- **Snappy menus:** added a per-connection read-through cache (`ResponseCache`)
  in `HermesRepository`. Re-opening a Manage screen within the TTL (20s; 5min for
  static config schema/defaults) now returns the last decoded value synchronously
  — no network round-trip, no loading spinner on revisit. Covers skills, toolsets,
  MCP, channels, webhooks, profiles, cron, memory, curator, system, env, config,
  portal and analytics(days). Live surfaces (Status polling, pairing) deliberately
  bypass it; every paired mutation invalidates its key, and a profile/management
  switch clears the whole cache. Unit-tested (`ResponseCacheTest`).
- **Chat keyboard:** the composer now hugs the keyboard — the bottom navigation
  bar/rail hides while the IME is open (`NavigationSuiteType.None`), removing the
  dead band that appeared between the input field and the keyboard.
- Chat transcript: streaming turns live in a dedicated `streamingText` field
  instead of rebuilding the whole line list per PTY chunk; markdown parsing
  memoized per message (`remember`); reading-mode poll skips no-op refreshes
  (equality guard); auto-follow scroll is instant and keyed on the last line's
  actual content. Measured: transcript frame time 150ms → 85ms p50 on the
  emulator (at the software-GPU floor; launcher itself measures 101ms).
- Launcher icon zoomed out further (~42% fill, was ~50%) so the winged sandal
  sits comfortably inside adaptive-icon masks on every launcher.

### Fixes

- **Chat no longer dumps the full TUI on send.** Reading mode (the default) now shows
  a single live **working indicator** — a spinner plus the *current* tool the model is
  running ("Running · <tool>") instead of the raw terminal or a growing list of tool
  cards. It appears when you send and clears itself the moment the assistant's reply
  lands, leaving just the clean message. (`ChatTab.working`, `WorkingIndicator`.)

### Features

- **Models screen (roadmap 15.12 — Desktop parity):** Manage → Capabilities → **Models**
  lists every provider from `/api/model/options` (model count, source, auth state,
  warnings), highlights the active model, and sets a model via `PUT /api/model/set`.
- **Learning graph (roadmap 15.4 — Desktop parity):** Manage → System → **Learning**
  shows what Hermes has learned — overview stats, category cluster chips, and the
  skill/memory node list (kind, category, use-count, state) from `/api/learning/graph`.
- **Terminal pane upgrades (roadmap 15.13):** Chat terminal mode gains a **Stop**
  (Ctrl-C interrupt) action and selectable/copyable PTY output.
- **Command palette (roadmap 15.7 — Desktop parity):** a search icon in the Manage
  top bar opens a fuzzy-filtered quick-jump sheet over every Manage destination
  (matches title, subtitle or section); tap a result to navigate. Reaches any
  settings screen in two taps.
- **Launcher app-icon shortcuts (roadmap 15.7):** long-press the Talaria icon for
  **New chat / Status / Activity / Manage**. Static `res/xml/shortcuts.xml` using
  implicit `talaria://` VIEW intents (build-variant-safe, no hardcoded applicationId),
  routed in `TalariaNavRoot` with the correct bottom-nav highlight.
- **Files pane (roadmap 15.1 — Desktop parity):** new Manage → System → **Files**
  browser over `/api/fs`. Starts at the gateway's default cwd, lists directories
  first, and navigates in/out with an up button + `cwd` shortcut; tapping a file
  opens a preview sheet (`/api/fs/read-text`) showing language, size and a
  monospace body (binary-safe). Typed `Fs*` models + decode tests; listings
  cached 10s.
- **Rename agent tabs:** long-press a Chat agent tab to rename it
  (`ChatViewModel.renameTab`); the tab strip is now a custom chip supporting the
  long-press affordance.

### App icon

- Launcher icon replaced with the official winged-sandal logo (adaptive icon:
  black background + cream sandal foreground, Android 13 monochrome layer).

### Fixes

- **Session detail (3.2):** messages now render with markdown formatting
  (`SimpleMarkdownText`) instead of plain text; new summary header card shows
  model, source, message/tool counts, token accounting and last-active, with a
  **● LIVE** chip when the dashboard reports the session running.
- **Event client (0.4):** `/api/ws` + `/api/events` sockets now auto-reconnect
  with exponential backoff (1s→30s, 6 attempts) and a freshly minted WS ticket
  per attempt; attempt counters reset on successful open; `stop()` still wins.
- **Webhooks (10.3):** each webhook row gained **Copy URL**; the create
  response is parsed for a one-time secret / final url and shown in a
  dismissible card with copy buttons (dashboard may not echo one). Added the
  missing platform-level **Enable** workflow (`POST /api/webhooks/enable`,
  with gateway-restart warning) that the web page has.
- **Model state (1.3):** the sidecar now sends a proactive `model.info` RPC
  probe on `/api/ws` connect so the badge is fresh even when the dashboard
  does not push model notifies.
- **Tests:** session filter rules extracted into a pure `SessionFilters`
  object with 6 unit tests (tab classification + case-insensitivity).
- **Status (2.1):** gateway state now falls back to the top-level
  `gateway_running` flag when the dashboard doesn't nest it (v0.19+), fixing
  a false "Stopped" readout; unknown state renders neutrally instead.

## [Unreleased] — Density + feature expansion

### Space efficiency (verified on-device)

- New shared spacing/density scale (`ui/theme/Spacing.kt` + `LocalSpacing`); screens read
  tokens instead of hardcoded dp literals.
- Collapsed the triple-stacked header: the global profile-switcher strip is gone — it's now a
  compact chip in each top-level screen's top bar (`ProfileSwitcherChip`). `ScreenScaffold`
  renders a single dense sans-serif title line; redundant subtitles dropped.
- **Manage** hub grouped into Agents / Capabilities / Messaging / System sections with a denser
  row; **Logs** merged 3 filter rows into one (file chips + level/component dropdown chips) and
  virtualized the output (`LazyColumn`); **Sessions** folded its filter/search/banner chrome into
  a compact row; **You** uses a segmented theme control and denser toggles; **Privacy** folded
  into You as an expandable section (standalone screen removed).

### Features

- **Live agent status bar (Chat).** The sidecar `session.info` frame (previously dropped as
  `Raw`) is now typed and surfaced inline: `Live · model · reasoning · approval · ⚡yolo`, plus
  token/cost when a provider emits usage. Fixed the `event`-envelope parser that clobbered the
  real event type with the outer method name.
- **Event-driven Activity feed.** While foregrounded, the shared sidecar is subscribed and
  gateway/session/approval lifecycle frames are recorded into the Activity timeline live
  (`HermesForegroundObserver`), not just via periodic WorkManager polling.
- **Memory & Curator screens.** `/api/memory` and `/api/curator` now have typed models and
  dedicated structured screens (provider cards with status chips; curator schedule) instead of
  raw-JSON dumps in System. Driven by the shared `SimpleManageViewModel`.
- **Toolset activation.** Toolsets are now toggleable (`PUT /api/tools/toolsets/{name}`), with
  unavailable toolsets disabled.
- **Analytics date range.** 7 / 30 / 90-day selector wired to the existing `days` param.

### Fixed / quality

- `SettingsStore.cloudSttOptIn` setter wrote a hardcoded `false` before the real value.
- Extracted sidecar frame classification into a pure, unit-tested `SidecarFrameParser`; added
  `MainDispatcherRule` test scaffolding and coverage for frame parsing + typed-model decoding.

## [1.0.0] — 2026-07-31 — Parity freeze

### Changed (UI refresh)

- Fixed top-of-screen layout: the status-bar inset is applied once at the root, so the
  profile switcher no longer overlaps the system clock and screens no longer show a large
  empty band of duplicated inset below it. More content is visible above the fold.
- Slimmed the global profile switcher from a full-width button + light-amber banner to a
  compact, theme-aware chip (neutral on default, amber when a non-default profile is active).
- Decluttered the Chat header: model moved into a single-line "Live · <model>" subtitle with
  a compact model-picker icon; actions trimmed to icon buttons; "All sessions" moved into the
  sessions sheet. The composer placeholder ("Message Hermes…") no longer wraps to two lines.
- Modern typography: buttons, tabs, and chips are now clean sans-serif (was monospace);
  serif is reserved for display/headline/title for an editorial, branded feel.
- Ships with the curated **Hermes brand palette** by default (amber primary + wing-blue
  secondary on a deep blue-black) — Material You dynamic color now defaults OFF but is
  still one toggle away in the You screen.
- Brighter primary text for contrast; softer, larger corner radii on cards/sheets.
- Manage hub rows gained leading icon chips (icon · title · subtitle · chevron) so the
  dashboard surfaces are scannable instead of a plain text list.

### Fixed (live-chat, verified against a running Hermes agent on-device)

- **Messages now submit.** The PTY sent the message body and Enter in one frame,
  which the Hermes TUI read as a bracketed paste (the newline landed as literal
  text and nothing was submitted). Send the body and a standalone `\r` as separate
  frames so Enter registers — the agent now actually receives typed messages.
- **Terminal no longer floods with resize frames.** `resize()` sent a JSON
  `{"type":"resize"}` frame in addition to the `\x1b[RESIZE:…]` escape; Hermes only
  consumes the escape, so the JSON was echoed into the transcript as garbage and
  corrupted input. Send only the escape, deduped to real dimension changes.
- **Chat defaults to Reading mode** (clean message bubbles from the sessions REST
  API) instead of Terminal mode, and Reading now works for the *live* session by
  discovering the active/most-recent session and polling its messages — so Chat
  shows just the conversation, not the whole `hermes --tui` screen. Terminal mode
  stays available for power users.

### Added

- Config schema **enum dropdowns** (`enum` / `choices` / `oneOf`) alongside bool switches and text fields.
- Config **import from file** via Storage Access Framework, next to paste-import and share-export.
- Pairing **Approve** action directly on the pairing notification (`PairingApproveWorker`).
- Offline snapshot cache: `SettingsStore` stores the last Status summary + pending-pairing count.
- Home-screen widget shows the cached status (`· cached` when unreachable) and a pending-pairing badge.

### Changed

- Version bumped to `1.0.0`; `docs/API.md` Gaps table now contains only wontfix / non-goal items.
- ROADMAP: Phase 13 essentials checked off; **M5 parity-freeze** milestone reached.
- Fixed a stale unit test to assert the emulator-host default URL (`http://10.0.2.2:9119`).

### Notes

- All Phase 0–12 done-when criteria and the Phase 13 essentials are complete. Remaining
  gaps (Skills Hub install, MCP OAuth catalog, `/api/files*`, dashboard plugins) are blocked
  upstream or explicit non-goals, documented in `docs/API.md`.
- Verified via `./gradlew :app:assembleDebug` (APK produced) and `:app:testDebugUnitTest` (green).
  On-device smoke test was blocked: the local Android emulator (emulator 37.1.11 on this host)
  segfaults in its host GL renderer in windowed mode and never opens the adb console port
  headless — an environment/SDK-install issue, not an app defect.

## [0.2.1] — 2026-07-31

### Added

- ProcessLifecycleOwner stops chat sidecar in background; fresh WS tickets on resume.
- Contract fixtures under `app/src/test/resources/fixtures/` + expanded decode tests.
- `env_catalog.json` merge into API Keys; signup links; `/reload` tip after save.
- OIDC Custom Tabs “Open portal login”; `talaria://connect?profile=` deep link.
- Connection doctor PTY probe; clarify/sudo text prompts; reading-mode markdown helper.
- Activity typed sync rows + tap-to-navigate; Logs search/share; Analytics model breakdown.
- Cron schedule presets; Skills Hub docs Custom Tab; Profiles skills/config shortcuts.
- Config boolean Switch widgets; broader `ProfileQueryInterceptor` prefixes.

### Notes

- Phase 0–12 roadmap checkboxes met for primary done-when criteria; remaining gaps are documented in `docs/API.md` (Hub install API, enum dropdowns, pairing Approve action).
- Phase 13 mobile-only excellence is still backlog.

## [0.2.0] — 2026-07-31

### Added

- WebSocket gated auth via `POST /api/auth/ws-ticket` (`ticket=` vs `token=`).
- Global Hermes management-profile switcher + amber “managing profile” banner.
- Chat sidecar (`/api/ws` + `/api/events`): model badge/picker, tool-call cards, slash palette, approval dialogs, session rail, Terminal/Reading modes, PTY resize.
- Status auto-refresh, Sessions filters/search/prune/export, schema-driven Config, API key catalog (`env_catalog.json`), Logs tail/filters, Analytics bars, Cron edit, Skills/toolsets, MCP CRUD+test, Channels/Webhooks forms, System doctor/portal/memory/curator.
- In-app Connection doctor on Connect; Activity filters (All/Pairing/Cron/Gateway/Chat).

### Notes

- Emulator default dashboard URL remains `http://10.0.2.2:9119`.


## [0.1.0] — 2026-07-31

### Added

- Initial production-ready project scaffold (Kotlin, Compose, Material 3, minSdk 28, targetSdk 35).
- Multi-profile secure connections (session token, basic, bearer, OIDC-browser mode, optional cert pins).
- Chat via Hermes `/api/pty` WebSocket with ANSI stripping, drafts, share intent, deep links.
- Notifications pipeline (channels, reply/open/dismiss, WorkManager sync, optional FGS).
- On-device speech dictation + optional TTS; cloud STT opt-in only.
- Management screens: Status, Sessions, Config, API Keys, Cron, Skills, MCP, Channels, Pairing, Webhooks, Profiles, Logs, Analytics, System.
- Activity feed, You/Privacy settings, Glance status widget, Quick Settings tile.
- Room offline cache for sessions/messages/activity.
- Docs: README, ARCHITECTURE, PRIVACY, SETUP, CONTRIBUTING, docs/API.md.
- Unit tests for ANSI stripping, Retrofit contract smoke tests, Connect defaults.
- Gradle `assembleSignedRelease` convenience task + keystore.properties signing support.

### Notes

- Hermes API baseline: `dashboard-v0.17+`.
- PTY chat is a mobile adaptation of the dashboard TUI bridge — not full xterm parity.
