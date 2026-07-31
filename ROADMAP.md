# Talaria roadmap — Web Dashboard parity

Goal: make Talaria feel like a first-class mobile client for the [Hermes Agent Web Dashboard](https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard) — every major page and workflow, adapted for touch, offline, and battery — while staying a remote client (no embedded Python runtime).

**Current baseline:** Talaria `0.2.0` · Hermes API `dashboard-v0.17+`  
**Contract sources:** `docs/API.md`, upstream `web/src/lib/api.ts`, `hermes_cli/web_server.py`, dashboard docs.

Use this file as the working backlog. Each item has **why**, **done when**, and **how** (ordered steps). Check boxes as you ship.

---

## How to use this roadmap

1. Pick the next unchecked item in the lowest open phase (don’t skip foundations under Chat).
2. Re-diff against upstream Hermes before coding — endpoints and JSON shapes move.
3. Prefer extending `HermesApi` / repositories first, then ViewModels, then Compose UI.
4. Add or update a focused unit/UI test when behavior is non-obvious.
5. Update `docs/API.md` + this file when an item lands; bump `CHANGELOG.md`.

**Legend**

| Tag | Meaning |
|-----|---------|
| `API-ready` | Retrofit methods exist; UI thin or missing |
| `Partial` | Screen exists; missing web-parity workflows |
| `Missing` | Not started in the client |
| `Mobile-adapt` | Web does X differently; we need a phone-native equivalent |

---

## Snapshot — where `0.2.0` stands

| Web Dashboard page | Talaria today |
|--------------------|---------------|
| Status | Stronger — sections + 5s refresh + recent sessions |
| Chat | Stronger — PTY + sidecar (model/tools/slash/approvals/rail/resize); Compose, not xterm |
| Config | Stronger — schema categories + JSON escape hatch + reset/export/import |
| API Keys | Stronger — grouped catalog + redacted env merge |
| Sessions | Stronger — filters/search/rename/export/delete/prune |
| Logs | Stronger — level/component filters + poll tail |
| Analytics | Stronger — daily bars + totals |
| Cron | Stronger — create/edit + lifecycle actions |
| Profiles | Stronger — switch active + shortcuts |
| Skills | Stronger — search/categories + toolsets tab + Hub link |
| MCP | Stronger — CRUD + test sheet |
| Webhooks | Stronger — create / enable / delete |
| Pairing | Stronger — approve / revoke / clear-pending |
| Channels | Stronger — configure sheet + test |
| System | Stronger — doctor / audit / backup / portal / memory / curator |
| Profile switcher (global) | Done — Manage/You/Activity bar + amber banner |
| Auth (gated WS tickets) | Done for WS; OIDC Custom Tabs still open |
| Events / live fan-out | Done — `HermesEventClient` |
| Connection doctor | Done — Connect screen preflight |

---

## Phase 0 — Foundations (do these first)

Everything below Chat and live Manage depends on solid auth, profile scope, and API typing.

### 0.1 Harden WebSocket auth (gated dashboards)

**Why:** Remote Tailscale/VPN dashboards use password/OAuth gates. Web chat uses `POST /api/auth/ws-ticket` → `?ticket=` on `/api/pty`, `/api/ws`, `/api/events`. Token-only WS breaks there.

**Done when:** Chat + sidecar connect to a `--host 0.0.0.0` dashboard with basic auth using tickets; loopback still uses `?token=`.

**How:**
1. Read upstream `buildWsUrl` / `getWsTicket` in `web/src/lib/api.ts`.
2. In `HermesClientFactory` / a new `WsAuthHelper`, after REST session is warm call `POST /api/auth/ws-ticket`.
3. Teach `PtyWebSocketSession` (and future WS clients) to attach `ticket` when `auth_required`, else `token`.
4. Handle close codes `4401` / `4403` with clear UI copy (auth vs Host/peer guard).
5. Manual test: LAN + Tailscale basic-auth dashboard; verify PTY opens.

### 0.2 Global profile switcher (parity with sidebar `?profile=`)

**Why:** Web Config / Keys / Skills / MCP / Chat all follow one profile switcher + amber “managing profile X” banner.

**Done when:** Any connected session can switch management profile without re-entering Connect; all Manage + Chat calls send `?profile=`; banner visible when ≠ default.

**How:**
1. Load `GET /api/profiles` into `ConnectionRepository` / settings.
2. Add a top-of-app or You/Manage chip: profile picker → `PUT /api/profiles/active` (or local selection writing into `SecureConnectionStore.managementProfile`).
3. Confirm `ProfileQueryInterceptor` covers every PROFILE_SCOPED prefix from upstream `api.ts`.
4. On profile change: disconnect PTY, clear chat draft channel id, refresh Status/Sessions.
5. Deep link: `talaria://connect?profile=worker` preselects.

### 0.3 Typed models + error surfaces

**Why:** Many Manage screens stringify JSON. Web parity needs stable models for forms and lists.

**Done when:** Status, sessions, cron, skills, MCP, channels, analytics decode into kotlinx.serialization types with user-visible error/empty states.

**How:**
1. Capture real JSON fixtures from a live dashboard (`curl` + auth) into `app/src/test/resources/fixtures/`.
2. Expand `HermesModels.kt` (or per-feature models) to match fixtures.
3. Replace `SimpleManageViewModel` loaders that return `Any` with typed ViewModels where forms need mutations.
4. Add contract tests that decode fixtures (extend `HermesApiContractTest`).

### 0.4 Live event bus scaffolding

**Why:** Web Chat sidecar and Activity/Status freshness use `/api/events` (+ `/api/ws` JSON-RPC). Polling alone won’t match.

**Done when:** A single `HermesEventClient` can open `/api/events?channel=` and `/api/ws`, expose `SharedFlow`s, and survive reconnect with new tickets.

**How:**
1. Mirror upstream ChatPage channel-id generation (UUID per chat session).
2. Implement OkHttp WS client for `/api/ws` (JSON-RPC request/response + notify).
3. Implement `/api/events` subscriber for `tool.*` and status-ish frames.
4. Wire lifecycle to `ProcessLifecycleOwner` — disconnect in background unless FGS sync opted in.
5. Document message schemas in `docs/API.md`.

---

## Phase 1 — Chat parity (highest user impact)

Web Chat = real `hermes --tui` over `/api/pty` (xterm.js) **plus** `/api/ws` + `/api/events` sidecar for model badge, picker, tool list.

### 1.1 Pass `channel` into PTY + spawn sidecar

**Status:** Missing / Partial  
**Done when:** Opening chat creates `channel`, connects PTY with `channel=`, and opens `/api/events` + `/api/ws` for that channel.

**How:**
1. Study upstream `ChatPage.tsx` + PR notes on TeeTransport / `HERMES_TUI_SIDECAR_URL`.
2. Extend `PtyWebSocketSession.connect(...)` query: `channel`, `resume`, `profile`, auth.
3. Start `HermesEventClient` before or with PTY; tear down together.
4. Log/ignore unknown event types (forward-compat).

### 1.2 In-chat session rail (mobile)

**Status:** Partial (Sessions is a separate Manage page)  
**Mobile-adapt:** Web right rail → bottom sheet / drawer on phone; dual-pane on tablet.

**Done when:** From Chat you can New, refresh list, tap to resume, see active highlight — without leaving the Chats tab.

**How:**
1. Reuse `GET /api/sessions?profile=` (recent, profile-scoped).
2. Add `ChatSessionRail` Compose component + `ModalBottomSheet` on compact width.
3. On select: close PTY, navigate/`resume=` reconnect (same as web play button).
4. Keep rename/delete/export on Sessions tab (web does the same).

### 1.3 Model badge + model picker

**Status:** Missing (`/api/model/*` not in client UI)  
**Done when:** Chat shows current model/connection state; picker can list options and set model like the web sidebar.

**How:**
1. Add Retrofit: `GET /api/model/info`, `GET /api/model/options`, `POST/PUT /api/model/set` (confirm exact verbs in upstream `api.ts`).
2. Also subscribe to model state notifies on `/api/ws` if the dashboard pushes them.
3. UI: badge in Chat top app bar; `ModalBottomSheet` list of options.
4. After set: show toast; rely on TUI/sidecar for live badge update.
5. Respect `?profile=` on all model calls.

### 1.4 Tool-call cards from events

**Status:** Missing  
**Done when:** Live tool start/progress/complete appear as collapsible cards beside/above the transcript (not only buried in ANSI).

**How:**
1. Map event names from web `ChatSidebar` (`tool.start` / `progress` / `complete`).
2. Hold `List<ToolCallUi>` in `ChatViewModel`.
3. Render Material cards: name, args preview, status, duration.
4. Best-effort: if events WS fails, chat still works via PTY alone (web behavior).

### 1.5 Slash-command palette (mobile)

**Status:** Missing as first-class UI (raw `/…` via PTY may work)  
**Done when:** `/` in composer opens a filterable command list; picking one inserts/sends the command.

**How:**
1. Prefer discovering commands from `/api/ws` or a REST list if upstream exposes one; else ship a curated list matching TUI docs (`/model`, `/reload`, `/help`, …) and keep “send raw” fallback.
2. Intercept composer input starting with `/`.
3. On select, send the line through existing PTY write path.
4. Document that unknown commands still go to the TUI.

### 1.6 Approvals / clarify / sudo prompts

**Status:** Missing as structured UI  
**Done when:** Dangerous-command / clarify prompts surface as actionable dialogs (Approve / Deny / type response), not only as terminal text.

**How:**
1. Find prompt events on `/api/ws` or `/api/events` in upstream sidebar code.
2. Show `AlertDialog` / full-screen prompt; send response bytes/JSON per protocol.
3. Fallback: if only ANSI appears, keep PTY focus so users can still type `y`/`n`.

### 1.7 Markdown-friendly transcript mode (optional parallel UI)

**Status:** Partial — ANSI-stripped plain text  
**Mobile-adapt:** Full xterm.js WebGL is painful on Android; prefer hybrid.

**Done when:** Users can toggle **Terminal** (richer ANSI/spans) vs **Reading** (markdown bubbles from session messages REST).

**How:**
1. Short term: improve ANSI → Spannable (colors, bold) in Compose.
2. Parallel: when `resume`/session id known, also fetch `/api/sessions/{id}/messages` and render markdown (CommonMark) + tool blocks.
3. Long term (optional): evaluate a VT emulator View (cost/size) — only if Reading mode is insufficient.
4. Do **not** block Phase 1 on a full xterm port.

### 1.8 PTY resize + keyboard UX

**Status:** Missing  
**Done when:** IME + window size changes send `\x1b[RESIZE:cols;rows]` (or upstream’s current resize frame) so TUI layouts don’t break.

**How:**
1. Copy resize protocol from `ChatPage.tsx` `onResize`.
2. Estimate cols/rows from font metrics or fixed mobile grid.
3. Re-send on configuration change / foldable posture if needed.

---

## Phase 2 — Status & Activity (live overview)

### 2.1 Status page parity

**Status:** Partial  
**Done when:** Shows version, release date, gateway state/PID/platforms, active session count, recent 20 sessions with model/tokens/preview; auto-refresh ~5s while visible.

**How:**
1. Type `StatusResponse` from `GET /api/status` fixture.
2. Redesign `StatusScreen` into sections (Hero gateway, platforms chips, recent sessions list).
3. `whileIsActive` poll every 5s **or** push via events if available.
4. Tap recent session → Session detail or Chat resume.
5. Pull-to-refresh + last-updated timestamp.

### 2.2 Activity feed = real dashboard signals

**Status:** Partial (local Room activity)  
**Done when:** Activity merges local notifications with pairing pending, cron runs, gateway flips, errors from sync/events.

**How:**
1. Extend `HermesSyncWorker` to write typed `ActivityEntity` rows for pairing/cron/gateway diffs.
2. Optional: subscribe to a subset of `/api/events` when app is foregrounded.
3. Filters: All / Pairing / Cron / Gateway / Chat.
4. Deep link each row to the right Manage/Chat screen.

---

## Phase 3 — Sessions (browse like the web)

### 3.1 Filters, search, stats bar

**Status:** API-ready / Partial UI  
**Done when:** Chats / Automation / All tabs, source dropdown, FTS search with snippets, summary stats bar.

**How:**
1. Wire `GET /api/sessions` query params exactly as web (source filters).
2. Wire `GET /api/sessions/search` to search field (debounce 300ms).
3. Highlight snippets in results; jump-to-message in detail.
4. Stats row from list payload or status endpoint fields.

### 3.2 Rich session detail

**Status:** Partial  
**Done when:** Markdown messages, role colors, collapsible tool-call JSON, live badge, token counts.

**How:**
1. Decode tool_calls from messages JSON.
2. Add markdown renderer dependency (keep offline; no network image fetch unless user opts in).
3. Collapsible `ToolCallBlock` composable.

### 3.3 Rename, export, delete, prune

**Status:** API-ready  
**Done when:** Row actions match web: rename (`PATCH`), delete (`DELETE`), export (share file), prune (`POST /api/sessions/prune`) with confirm dialogs.

**How:**
1. Expose repo methods already backed by `HermesApi`.
2. Overflow menus on list + detail.
3. Export: write temp markdown/JSON via `FileProvider` + share sheet.
4. Prune: dangerous confirm + result snackbar.

---

## Phase 4 — Config (schema-driven forms)

### 4.1 Load schema + defaults

**Status:** API-ready  
**Done when:** App fetches `/api/config`, `/api/config/schema`, `/api/config/defaults` and builds category tabs.

**How:**
1. Inspect schema JSON shape from a live dashboard.
2. Build `ConfigFormViewModel` holding draft `JsonObject`.
3. Category tabs from schema sections (model, terminal, display, agent, …).

### 4.2 Field widgets

**Status:** Missing (raw JSON only today)  
**Done when:** Enums → dropdowns, bools → switches, numbers/text → fields; unknown → text; validation errors shown.

**How:**
1. Map schema property types → composables.
2. Keep “Advanced JSON” escape hatch (current editor) behind a toggle.
3. Dirty-state Save / Discard app bar actions.

### 4.3 Reset / import / export

**Status:** Missing  
**Done when:** Reset-to-defaults (local draft from defaults), export share JSON, import from file/clipboard, then Save → `PUT /api/config`.

**How:**
1. Reset: copy defaults into draft (don’t PUT until Save) — match web.
2. Export: share current draft/server config.
3. Import: Storage Access Framework → parse JSON → draft.
4. Snackbar reminding “restart gateway / new session for some keys”.

---

## Phase 5 — API Keys (catalog UX)

### 5.1 Grouped key catalog

**Status:** Partial  
**Done when:** Keys grouped like web (LLM / Tool APIs / Messaging / Agent); redacted preview; description; signup link; show-advanced toggle.

**How:**
1. Pull grouping metadata from upstream web env catalog (or hardcode mirrored catalog in `assets/env_catalog.json`, regenerate from Hermes when they change).
2. Merge with `GET /api/env` set/unset state.
3. Secure `TextField` for values; never log secrets.
4. `PUT` / `DELETE` already exist — wire per-row save/delete.
5. After save, offer “send `/reload` in Chat” helper (copy or deep-link tip).

---

## Phase 6 — Logs & Analytics

### 6.1 Logs viewer parity

**Status:** Partial  
**Done when:** Level + component filters, search, follow/tail while screen visible, share excerpt.

**How:**
1. Match `GET /api/logs` query params (level, component, search, limit) to web.
2. UI chips for filters; debounced search.
3. Tail: poll 2–5s while resumed **or** use events if log streaming appears upstream.
4. “Copy” / share selected lines.

### 6.2 Analytics charts

**Status:** Partial  
**Done when:** Daily token stacked bars, cost summary, per-model and per-provider breakdowns readable on phone.

**How:**
1. Type `GET /api/analytics/usage` response.
2. Use a lightweight chart lib or Canvas bars (avoid heavy trackers).
3. Range selector if API supports it; else show default window web uses.
4. Empty state when no usage yet.

---

## Phase 7 — Cron polish

### 7.1 Edit job + schedule UX

**Status:** Partial (create + lifecycle actions exist)  
**Done when:** Edit existing job (`PUT`), friendlier schedule helper, job detail with last/next run, attachables if web has them.

**How:**
1. Confirm cron job JSON fields vs web form.
2. Edit sheet pre-filled from job; `PUT /api/cron/jobs/{id}`.
3. Schedule presets (hourly/daily/cron expression) + validation.
4. Show errors from API inline.

---

## Phase 8 — Skills, toolsets, Hub

### 8.1 Installed skills UX

**Status:** Partial  
**Done when:** Search, category grouping, enable toggles (already), detail description.

**How:**
1. Client-side filter on `GET /api/skills`.
2. Category sections; sticky search bar.

### 8.2 Toolsets view

**Status:** Missing  
**Done when:** `GET /api/tools/toolsets` rendered with active/configured state and included tools.

**How:**
1. Add Retrofit method + models.
2. New Skills tab: Skills | Toolsets.
3. Deep link from Profiles “manage skills”.

### 8.3 Skills Hub install/browse

**Status:** Missing  
**Done when:** Browse/search Hub and install/uninstall if dashboard exposes those endpoints.

**How:**
1. Diff upstream Skills page + `api.ts` for Hub routes (names change — verify!).
2. Implement list/search/install/remove in `HermesApi` + UI.
3. If API is incomplete, document gap and ship “open Hub URL in browser” fallback only when a stable URL exists.
4. Never bundle third-party skill code into the APK.

---

## Phase 9 — MCP servers

### 9.1 Full MCP CRUD + test

**Status:** API-ready / Partial UI  
**Done when:** Add server form, enable toggle, delete, **Test** (connect/list tools/disconnect) with result UI.

**How:**
1. Build `McpViewModel` over existing POST/DELETE/PUT/POST test endpoints.
2. Test result bottom sheet: tool names or error.
3. OAuth/catalog install: follow upstream when present; else mark blocked in `docs/API.md`.

---

## Phase 10 — Channels, pairing, webhooks

### 10.1 Channel configure forms

**Status:** Partial (status list)  
**Done when:** Per-platform configure sheet with the fields web shows; secrets as password fields; blank = keep existing; test connection; enable/disable.

**How:**
1. Export field schema from upstream Channels page (or `/api/messaging/platforms` metadata).
2. `PUT /api/messaging/platforms/{id}` with `{enabled, env, clear_env}`.
3. `POST …/test` wired to a Test button.
4. Link “Setup guide” to Hermes docs URLs (Custom Tabs).

### 10.2 Pairing polish

**Status:** Stronger already  
**Done when:** Clear-pending, push notification on new pending (sync worker), deep `talaria://pairing`.

**How:**
1. Wire `POST /api/pairing/clear-pending`.
2. Ensure sync detects pending delta → `notifyPairing`.
3. Approve from notification action if feasible.

### 10.3 Webhooks wizard

**Status:** Partial  
**Done when:** Create / enable / delete with the same fields as web.

**How:**
1. Form → `POST /api/webhooks`; toggles → `PUT …/enabled`; delete confirm.
2. Show secret/url once on create if API returns it; allow copy.

---

## Phase 11 — Profiles & System / doctor

### 11.1 Profiles management

**Status:** Partial  
**Done when:** List profiles, show active, switch active, shortcuts to Skills/Config scoped to profile (sets global switcher).

**How:**
1. `GET /api/profiles`, `GET/PUT /api/profiles/active`.
2. Row actions: “Manage skills”, “Open config” → navigate + set profile.
3. Clarify in UI: gateway processes stay per-profile (as web docs say).

### 11.2 System page parity

**Status:** Partial  
**Done when:** Gateway controls, Nous Portal mirror, memory/curator status, doctor + security audit + backup actions, update check.

**How:**
1. Sections binding `GET /api/system/stats`, `/api/portal`, `/api/memory`, `/api/curator`.
2. Buttons: `POST /api/ops/doctor`, `security-audit`, `backup` — show returned report in a scrollable sheet.
3. `GET /api/hermes/update/check` → version badge + release notes link.
4. Gateway start/stop/restart already present — add confirmations + busy states.

---

## Phase 12 — Auth & connection polish

### 12.1 OIDC / Nous Portal browser login

**Status:** Partial  
**Done when:** Custom Tabs login completes via `talaria://` redirect / cookie capture without manual token paste for the common portal path.

**How:**
1. Trace web hosted-mode OAuth redirects.
2. Register redirect URIs; complete cookie jar session.
3. Then mint WS tickets like password login.
4. Document remaining manual cases in SETUP.md.

### 12.2 Connection doctor in-app

**Status:** Missing  
**Done when:** Connect screen can diagnose: status reachable, auth_required/providers, Host mismatch, PTY close codes — with copy-pasteable fixes (mirrors Desktop remote-backend checklist).

**How:**
1. Run `GET /api/status` preflight; show `auth_required` + `auth_providers`.
2. Attempt WS ticket + short PTY probe.
3. Surface 4401/4403 guidance from dashboard docs.

---

## Phase 13 — Mobile-only excellence (beyond web)

Not required for “web parity,” but expected of a phone client.

| Item | Steps |
|------|--------|
| Notification richness | Map more sync diffs → channels; actionable Approve on pairing |
| Offline | Cache last Status/Analytics snapshots; read-only Sessions when unreachable |
| Widgets | Expand widget: last reply snippet, pending pairing count |
| Share targets | Share images/files when `/api/files*` or chat attachments exist upstream |
| Voice | Optional on-device Whisper/Vosk module (documented, not default APK) |
| Large screens | NavigationSuite dual-pane: session list | chat |

---

## Suggested milestone cuts

Ship vertical slices users can feel; don’t wait for the whole roadmap.

| Milestone | Version target | Must include |
|-----------|----------------|--------------|
| **M1 — Connected chat** | `0.2.0` | Phase 0.1–0.2, 1.1–1.4, 1.8 |
| **M2 — Sessions & Status** | `0.3.0` | Phase 2, 3 |
| **M3 — Config & Keys** | `0.4.0` | Phase 4, 5 |
| **M4 — Gateway ops** | `0.5.0` | Phase 8–11 (Skills Hub may slip) |
| **M5 — Parity freeze** | `1.0.0` | All Phase 0–12 done-when criteria; API.md gaps empty or explicitly wontfix |

---

## Definition of “fully featured” (exit criteria)

Talaria `1.0.0` vs Web Dashboard is complete when:

1. Every dashboard **page** listed in the official docs has a Talaria destination with the same primary workflows (create/edit/toggle/test/delete as applicable).
2. Chat supports **PTY + sidecar**: model picker, tool cards, session rail, slash palette, approval prompts — even if rendering is Compose-native rather than xterm.js.
3. Gated remote auth works with **WS tickets** end-to-end.
4. Global **profile switcher** scopes Chat + Manage like the web sidebar.
5. `docs/API.md` “Gaps” table is empty or only contains wontfix items with rationale (e.g. dashboard plugin themes).
6. Automated tests cover auth helpers, session filters, and config schema parsing; manual checklist signed off on LAN + Tailscale.

**Explicit non-goals for 1.0**

- Embedding the Hermes Python agent or Node TUI inside the APK  
- Pixel-perfect xterm.js WebGL  
- Hosting Hermes for the user  
- Third-party analytics/trackers  

---

## Working checklist (copy into issues)

```text
Phase 0
- [x] 0.1 WS tickets for gated auth
- [x] 0.2 Global profile switcher + banner
- [x] 0.3 Typed models + fixtures
- [x] 0.4 /api/ws + /api/events clients

Phase 1 Chat
- [x] 1.1 channel + sidecar wiring
- [x] 1.2 in-chat session rail
- [x] 1.3 model badge/picker
- [x] 1.4 tool-call cards
- [x] 1.5 slash palette
- [x] 1.6 approval/clarify dialogs
- [x] 1.7 markdown reading mode
- [x] 1.8 PTY resize

Phase 2–3 Overview & Sessions
- [x] 2.1 Status parity + 5s refresh
- [x] 2.2 Activity from real signals
- [x] 3.1 filters/search/stats
- [x] 3.2 rich session detail
- [x] 3.3 rename/export/delete/prune

Phase 4–7 Config → Cron
- [x] 4.x schema-driven config
- [x] 5.x grouped API keys catalog
- [x] 6.1 logs filters/tail
- [x] 6.2 analytics charts
- [x] 7.1 cron edit UX

Phase 8–11 Extensibility & admin
- [x] 8.x skills + toolsets + Hub
- [x] 9.x MCP CRUD + test
- [x] 10.x channels forms, pairing polish, webhooks wizard
- [x] 11.x profiles + system/doctor/portal/memory

Phase 12 Auth polish
- [ ] 12.1 OIDC/Portal Custom Tabs
- [x] 12.2 in-app connection doctor
```

---

## References

- Dashboard feature docs: https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard  
- Upstream API helper: `NousResearch/hermes-agent` → `web/src/lib/api.ts`  
- Chat implementation reference: `web/src/pages/ChatPage.tsx`, chat sidecar PR history (`/api/pty` + `/api/ws` + `/api/events`)  
- Talaria maps: [docs/API.md](docs/API.md), [ARCHITECTURE.md](ARCHITECTURE.md)  
- Contributing: [CONTRIBUTING.md](CONTRIBUTING.md)
