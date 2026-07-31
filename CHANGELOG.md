# Changelog

All notable changes to Talaria are documented here.

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
