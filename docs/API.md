# Hermes Dashboard API map (Talaria)

Baseline: `BuildConfig.HERMES_API_BASELINE` = `dashboard-v0.17+`.  
Upstream references: Hermes `web/src/lib/api.ts`, `hermes_cli/web_server.py`.

## Auth

| Call | Notes |
|------|--------|
| REST | Session token header / basic / bearer / cookie jar (OIDC Custom Tabs) |
| `POST /api/auth/ws-ticket` | Used when `auth_required` — WS query `ticket=` |
| Loopback / token mode | WS query `token=` |
| Close `4401` / `4403` | Surface via `WsAuthHelper.explainCloseCode` + Connection doctor |

## Chat sockets

| Socket | Role |
|--------|------|
| `/api/pty?channel=&resume=&profile=&cols=&rows=` | TUI bridge; resize `\x1b[RESIZE:cols;rows]` + JSON |
| `/api/ws` | JSON-RPC: model state, `prompt.respond`, notify |
| `/api/events?channel=` | Tool start/progress/complete fan-out |

Sidecar sockets stop when the process backgrounds (`HermesForegroundObserver`). Chat reconnects on next open with a fresh ticket.

## Profile scope

`ProfileQueryInterceptor` appends `?profile=` for management-profile-scoped prefixes including status, sessions, config, env, skills, toolsets, mcp, messaging, model, pairing, logs, analytics, cron, webhooks, gateway, ops, hermes, portal, memory, curator, system.

## Implemented REST (UI wired)

Status, sessions (+ search/prune/patch/delete/messages), config (+ schema/defaults, enum dropdowns from `enum`/`choices`/`oneOf`, SAF file import + paste import + share export), env, logs, analytics, cron CRUD/lifecycle, skills toggle, toolsets, MCP CRUD/test, messaging platforms update/test, pairing approve/revoke/clear-pending (+ notification **Approve** action), webhooks create/enable/delete, profiles active get/set, system stats, portal/memory/curator, ops doctor/audit/backup, hermes update check, model info/options/set.

## Gaps / wontfix

All remaining entries are blocked upstream or explicit non-goals — the `1.0.0` parity-freeze
exit criteria ("Gaps table empty or wontfix-only") are met.

| Area | Status |
|------|--------|
| Skills Hub install/uninstall API | **wontfix (blocked)** — dashboard routes unstable; Custom Tabs docs fallback only |
| MCP OAuth / catalog install | **wontfix (blocked)** — until upstream exposes a stable flow |
| Pixel xterm.js / VT emulator | **non-goal** — Compose Terminal + Reading modes instead |
| `/api/files*` browser / share targets | **wontfix** — not exposed by the dashboard |
| Dashboard plugins / themes | **non-goal** — out of scope for a remote client |
| OpenAI-compatible `/v1/chat/completions` | **non-goal** — not used by the dashboard client |
| On-device Whisper/Vosk STT module | **non-goal (by design)** — on-device `SpeechRecognizer` ships; heavier engines are opt-in, not in the default APK |

When Hermes adds or renames endpoints, update `HermesApi.kt`, this file, and `BuildConfig.HERMES_API_BASELINE`.
