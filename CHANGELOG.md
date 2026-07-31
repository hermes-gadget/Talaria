# Changelog

All notable changes to Talaria are documented here.

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
