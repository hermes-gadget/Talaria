# Hermes API mapping

Talaria targets the **Hermes Agent Web Dashboard** REST + WebSocket surface (default port **9119**), not the optional OpenAI-compatible `hermes api` server (though that remains a future extension point for structured chat).

**Baseline:** `dashboard-v0.17+` (session token / gated auth). Source of truth used during implementation:

- https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard
- `NousResearch/hermes-agent` → `web/src/lib/api.ts`
- `hermes_cli/web_server.py`

## Auth headers & cookies

| Mechanism | Usage |
|-----------|--------|
| `X-Hermes-Session-Token` | Loopback / token mode REST |
| `Authorization: Basic` | Gated basic provider |
| `Authorization: Bearer` | Bearer mode |
| Cookie `hermes_session_at` | After `/auth/password-login` or OAuth |
| WS `?token=` | Loopback WS |
| WS `?ticket=` | Gated mode after `POST /api/auth/ws-ticket` |

## Implemented in Talaria client

| Method | Path | Talaria usage |
|--------|------|----------------|
| GET | `/api/status` | Status screen, widget, QS tile, sync |
| GET | `/api/auth/me` | Exposed on `HermesApi` |
| POST | `/api/auth/ws-ticket` | Exposed on `HermesApi` |
| GET | `/api/sessions` | Sessions list + cache |
| GET | `/api/sessions/{id}` | Session metadata |
| GET | `/api/sessions/{id}/messages` | Session detail + cache |
| PATCH | `/api/sessions/{id}` | API ready |
| DELETE | `/api/sessions/{id}` | API ready |
| GET | `/api/sessions/search` | API ready |
| POST | `/api/sessions/prune` | API ready |
| GET/PUT | `/api/config` | Config JSON editor |
| GET | `/api/config/defaults`, `/schema` | API ready |
| GET/PUT/DELETE | `/api/env` | API Keys screen |
| GET | `/api/logs` | Logs screen |
| GET | `/api/analytics/usage` | Analytics screen |
| GET/POST/PUT/DELETE | `/api/cron/jobs…` | Cron screen |
| GET/PUT | `/api/skills`, `/api/skills/toggle` | Skills screen |
| GET/POST/DELETE/PUT | `/api/mcp/servers…` | MCP list (+ mutate APIs) |
| GET/PUT/POST | `/api/messaging/platforms…` | Channels screen |
| GET/POST | `/api/pairing…` | Pairing screen |
| GET/POST/DELETE/PUT | `/api/webhooks…` | Webhooks screen |
| GET/PUT | `/api/profiles…` | Profiles screen |
| GET | `/api/system/stats` | System screen |
| POST | `/api/gateway/{start,stop,restart}` | System actions |
| GET | `/api/portal`, `/api/memory`, `/api/curator` | API ready |
| POST | `/api/ops/*` | Doctor/audit/backup APIs ready |
| GET | `/api/hermes/update/check` | API ready |
| POST | `/auth/password-login` | Basic auth bootstrap |
| WS | `/api/pty` | Chat |

Profile-scoped families automatically receive `?profile=` from `ProfileQueryInterceptor` when the connection’s management profile is set.

## Gaps / extension points

| Area | Status |
|------|--------|
| Full `/api/ws` JSON-RPC sidecar (tool cards, slash launcher) | Not wired in UI yet — PTY text bridge only |
| `/api/events` / `/api/pub` fan-out | Not subscribed |
| Skills Hub search/install UI | API methods partially present in dashboard; UI lists installed skills only |
| MCP catalog install / OAuth flows | List/test APIs present; rich install UX TBD |
| Channel configure forms (per-env fields) | Read-only status list in v0.1 |
| Webhook create wizard | List view; create via API method |
| Config schema-driven form widgets | Raw JSON editor (schema endpoint available) |
| OIDC Custom Tabs completion polish | Mode exists; operators may paste session after browser login |
| OpenAI-compatible `/v1/chat/completions` | Not used (dashboard contract preferred) |
| File browser `/api/files*` | Not exposed in UI |
| Dashboard plugins / themes | Out of scope for v0.1 |

When Hermes adds or renames endpoints, update `HermesApi.kt`, this file, and `BuildConfig.HERMES_API_BASELINE` / `ARCHITECTURE.md`.

## Compatibility notes

- Pre-0.17 dashboards may lack session-token auth; treat as untrusted on non-loopback binds.
- DNS-rebinding / Host guards on remote dashboards require the URL host you configure to match what the server expects.
- PTY chat requires the server `pty` extra and a POSIX host — native Windows Hermes installs may disable Chat while REST management still works.
