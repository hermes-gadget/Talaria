# Changelog

All notable changes to Talaria are documented here.

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
