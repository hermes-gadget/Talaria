# Roadmap

Current released version: `0.3.0`. Development baseline: Hermes Agent `v0.19.1` (`470cf66`, 2026-08-01).

Talaria’s target is feature parity for remote-capable Hermes chat and management workflows, expressed as a native Android product. “Parity” does not mean copying Electron-only window, tray, local-shell, or pet-overlay behavior onto a phone.

## Completed foundation

- Native Compose app for API 28–36 with adaptive navigation, edge-to-edge layouts, theme controls, app shortcuts, Photo Picker, SAF sharing, widget, Quick Settings tile, and actionable notifications.
- Multiple encrypted saved connections with password-cookie, session-token, bearer, and native OIDC PKCE authentication; WebSocket tickets and optional HTTPS certificate pins.
- Scoped Room/cache/draft/chat/widget/worker state for every connection and Hermes management profile.
- Multi-tab PTY chat with reading and terminal modes, process-death restoration, reconnect/resume, image attachments, voice dictation/TTS, share-to-chat, and model switching.
- Structured `/api/ws` and `/api/events` handling for tools, usage, prompts, approvals, clarification, sudo, secrets, lifecycle, foreground/background completion, and thread-aware Android notifications.
- Predictive slash-command palette backed by live `commands.catalog` and `complete.slash`, with alias/fuzzy ranking and a compatibility fallback catalog.
- Core management surfaces: status, sessions, config, env/API keys, models, cron, Skills Hub, toolsets, MCP catalog/OAuth, channels, pairing, webhooks, profiles, workspace files, learning, memory, curator, logs, analytics, operations, and system controls.

## Audit hardening completed after 0.3.0

- Replaced Basic headers with the current JSON password-login/cookie contract and implemented RFC 8252 native OIDC token refresh.
- Corrected current Hermes request methods and payloads for profiles, models, config, cron, search, pairing, and sidecar prompts.
- Added strict URL/deep-link/cookie/TLS-pin validation and cross-scope rejection for notification actions.
- Migrated Room storage without destructive fallback and made all cached data connection/profile-scoped.
- Fixed share/draft/transcript races, reconnect lifecycles, stale sidecar responses, worker retries, notification completion semantics, and status-bar inset duplication.
- Added signature-validated, bounded image attachments and safe session-export filenames.
- Replaced unused microphone/general-sync foreground services with a scoped active-turn monitor that exists only while a user-started agent task needs permission or completion tracking.
- Added MCP OAuth and approved-catalog installation, and brought profile CRUD/SOUL/description behavior up to the current API.
- Expanded unit/contract coverage and kept debug unit tests, Android lint, and APK assembly green.

## Next remote-capable parity work

Priorities are ordered by user value and destructive-operation risk.

1. Guided provider onboarding: validation, provider OAuth, custom endpoints, credential pools, recommended/auxiliary model selection, and MoA.
2. Session administration: bulk/empty cleanup, imports, stats, descendants, and explicit conflict-safe restore flows.
3. Managed file transfer: authenticated upload/download, mkdir/delete, media previews, progress, and user confirmation for destructive operations.
4. Cron run history, delivery targets, and blueprint instantiation.
5. Skill authoring/content editing and deeper toolset provider/model/env configuration.
6. Guided Telegram/WhatsApp onboarding and diagnostics.
7. Operations imports, backup download, hooks, checkpoints, raw config, and debug-share controls.
8. Remote Git/review workflows designed for a small-screen review experience.
9. Optional Hermes-hosted audio transcription/speech as an alternative to Android STT/TTS.
10. Dashboard agent-plugin marketplace/management where the extension provides a remote-safe UI contract.

The route-level backlog is maintained in [docs/API.md](docs/API.md). A future `1.0.0` should require verified interoperability tests against a pinned Hermes release, physical-device smoke coverage for the supported authentication modes, migration tests from every public Talaria database version, and no undocumented remote-capable gaps—not a documentation-only “parity freeze.”
