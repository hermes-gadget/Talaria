# Hermes API map

Verified baseline: Hermes Agent `v0.19.1`, upstream commit `470cf66b039c73bdd2c21d43094ce41a4db74eae` (2026-08-01). `BuildConfig.HERMES_API_BASELINE` is `hermes-v0.19.1`.

Primary contract sources are upstream `hermes_cli/web_server.py`, `hermes_cli/web_routers/*`, `hermes_cli/dashboard_auth/routes.py`, and `apps/desktop/src/hermes.ts`. This document records UI coverage, not every Retrofit annotation.

## Authentication and sockets

| Contract | Talaria behavior |
|---|---|
| `/auth/password-login`, `/api/auth/providers`, `/api/auth/me` | Provider discovery, JSON password login, persistent scoped cookies |
| Native OIDC discovery/token routes | RFC 8252 loopback callback, PKCE/state validation, encrypted refresh state |
| `POST /api/auth/ws-ticket` | Fresh ticket for gated WebSockets |
| `/api/pty` | One resumable Hermes TUI process per Android chat tab |
| `/api/ws` | JSON-RPC commands, completions, images, prompts, model/session state |
| `/api/events` | Tool, lifecycle, prompt, usage, completion, and notification events |

Talaria recognizes authentication close codes `4401` and `4403`, never sends expired OIDC access tokens after failed refresh, and scopes the cached `auth_required` result to the saved connection.

## Implemented mobile UI

| Area | Coverage |
|---|---|
| Status and system | `/api/status`, system stats, gateway start/stop/restart, action polling, update check |
| Sessions | List/paging, source filters, search, details/messages, rename, delete, prune, safe share export |
| Config and credentials | Typed schema/defaults, nested config editing, SAF import/share export, env set/delete, bundled provider catalog |
| Models and tools | Model info/options/set with expensive-model confirmation; toolset list/toggle |
| Cron | Job list/create/update/pause/resume/trigger/delete |
| Skills | Installed list/toggle; Hub search/preview/scan/install/uninstall |
| MCP | Server list/add/delete/enable/test; OAuth browser flow; approved catalog browse/install with required environment values |
| Messaging | Platform list, environment editing, enable/update, connection test |
| Pairing and webhooks | Pairing approve/revoke/clear; notification approval; webhook create/enable/toggle/delete and one-time result display |
| Profiles | List/create/clone/rename/delete, explicit host-active switch, SOUL and description editing, automatic description |
| Files and learning | Remote workspace `/api/fs` browse/read/edit with stale-write protection; learning graph/node inspect/edit/delete |
| Memory and curator | Provider/status/reset plus curator pause/run/status |
| Operations | Doctor, security audit, backup, action-log polling, logs, analytics |

`ProfileQueryInterceptor` explicitly scopes management calls to the selected Hermes profile. Pairing request bodies also include the effective profile because current Hermes requires it.

## Known remote-capable gaps

These APIs exist in the verified upstream baseline but do not yet have complete Talaria UI coverage. They are backlog, not “blocked upstream” and not part of a released parity claim.

| Area | Missing coverage |
|---|---|
| Session administration | Bulk/empty cleanup, import, stats, latest-descendant helpers |
| Cron | Run history, delivery targets, blueprints, direct fire |
| Skills and tools | Skill create/content edit/update; per-toolset provider/model/env setup; terminal backend/computer-use setup |
| MCP | Editing an existing server and catalog diagnostics (install and OAuth are implemented) |
| Managed files/media | `/api/files` upload/download/mkdir/delete and `/api/media`; workspace text `/api/fs` is implemented |
| Providers/models | Provider validation/OAuth/custom endpoints, credential pools, recommended/auxiliary models, MoA configuration |
| Messaging onboarding | Guided Telegram and WhatsApp onboarding flows |
| Operations | Imports/uploads, hooks, checkpoints, raw config, prompt-size/dump/migrate/debug-share, backup download |
| Audio | Hermes-hosted transcribe/speak/voice APIs; Talaria currently uses Android STT/TTS |
| Git/review | Remote Git status/worktrees/branches/review/stage/commit/push/PR routes |
| Dashboard extensions | Theme/font preferences and agent-plugin marketplace/management |

## Intentional Android adaptations

- Talaria does not embed an xterm.js pixel clone; it offers clean Reading mode and a selectable ANSI-stripped Terminal mode.
- OpenAI-compatible `/v1/chat/completions` is not used because Hermes chat parity runs through its PTY and sidecar protocols.
- Electron-only window, tray, pet overlay, native desktop terminal, and local filesystem integrations are replaced with Android notifications, app shortcuts, widgets, Quick Settings, Photo Picker, and SAF.
- Large bundled Whisper/Vosk engines remain out of the default APK. Android on-device speech recognition is preferred; cloud recognition requires opt-in.

When the Hermes contract changes, update `HermesApi.kt`, compatibility tests, this map, and `BuildConfig.HERMES_API_BASELINE` together.
