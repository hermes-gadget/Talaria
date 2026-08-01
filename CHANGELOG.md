# Changelog

All notable Talaria changes are documented here. Versions below correspond to repository tags; there has never been a `1.0.0` release.

## [Unreleased]

## [0.4.0] — 2026-08-01

### Added

- Predictive slash-command completion backed by live `commands.catalog` and `complete.slash`, with ranked aliases/fuzzy fallback and stale-response protection.
- Chat image attachments via Android Photo Picker and Android `ACTION_SEND`, with signature validation, a 25 MB bound, ordered `image.attach_bytes` staging, retry state, and image-only prompts.
- Current Hermes native OIDC login with PKCE, state validation, loopback callback, encrypted refresh tokens, and proactive refresh.
- MCP browser OAuth plus approved-catalog browsing/install with required environment values and background-action progress.
- Full profile create/clone/rename/delete, SOUL/description editing, automatic description, and explicit host-active selection.
- Memory, curator, learning-node, workspace file-edit, channel environment, Skills Hub, session export, and current system-action workflows.
- Unit and contract coverage for authentication, cookies, URL/WS construction, deep links, timestamps, sidecar shapes, slash ranking, attachments, workers, database migration, and safe export names.

### Fixed

- Updated REST methods and payloads to the verified Hermes Agent `v0.19.1` contract, including password login, profiles, model selection, config, cron, search, pairing, and structured prompt responses.
- Isolated Room rows, drafts, restored chat tabs, response caches, widget state, sync fingerprints, sockets, and notification actions by saved connection and Hermes management profile.
- Preserved databases with an explicit composite-key migration instead of destructive fallback.
- Hardened connection URLs, cookie identity/expiry matching, TLS pins, native OIDC callbacks, deep links, worker retries, RPC timeouts, and reconnect cancellation.
- Fixed share/draft/transcript races, stale session loads, reconnection state, notification-on-partial-output, profile switching, top insets, and session-export path handling.
- Refreshed loopback WebSocket tokens after Hermes dashboard restarts and kept the adaptive navigation scaffold stable while the keyboard opens, fixing live `403` reconnect failures and an on-device Compose crash.
- Removed unused voice/sync foreground services, obsolete manifest declarations, and the special battery-optimization permission.

### Changed

- Replaced the former `com.nousresearch.talaria` application ID and Kotlin namespace with the independent `com.hermesgadget.talaria` identity. Android treats this as a separate app, so installations of the former package require a one-time uninstall.
- Replaced the inherited release certificate with a stable independent signing identity owned by Hermes Gadget.
- API baseline is now `hermes-v0.19.1` (`470cf66`, 2026-08-01).
- Documentation now distinguishes implemented remote parity, known upstream-supported backlog, Android adaptations, and Desktop-only host integrations. The erroneous unreleased `1.0.0` parity claim was removed.

## [0.3.0] — 2026-08-01

- Restored every open chat tab, title, active tab, draft, and Hermes session after process death.
- Reconnected dead PTY tabs after foreground resume and fixed loopback token discovery/host rewriting.
- Requested microphone permission at use time and added actionable on-device speech-language errors.
- Added launcher shortcuts and unsaved-edit guards for Config and API Keys.

## [0.2.1] — 2026-08-01

- Changed tagged GitHub artifacts to the stable release application ID and CI signing key so Obtainium upgrades no longer conflict with rotating debug certificates.
- Derived monotonically increasing version codes from release tags.

## [0.2.0] — 2026-08-01

- Added current dashboard management surfaces, sidecar-driven chat status/tools/prompts, sessions and model controls, schema-driven configuration, Skills/toolsets/MCP/channels/pairing/webhooks, system operations, files, learning, memory, curator, analytics, and logs.
- Added management-profile switching, gated WebSocket tickets, response caching, lifecycle-aware polling, event-driven activity, command palette, terminal controls, and a denser adaptive UI.
- Added the winged-sandal adaptive launcher icon and the tagged APK release workflow.

## [0.1.0] — 2026-07-31

- Initial Kotlin/Compose Android client with encrypted saved connections, PTY chat, Room cache, activity/sessions, dashboard management screens, notifications, WorkManager sync, speech/TTS, widget, Quick Settings tile, tests, signing support, and project documentation.
