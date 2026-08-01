# Talaria roadmap — Web Dashboard + Hermes Desktop parity

Goal: make Talaria feel like a first-class mobile client for the [Hermes Agent Web Dashboard](https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard) and the [Hermes Desktop app](https://hermes-agent.nousresearch.com/docs/developer-guide/desktop-plugin-sdk) — every major page and workflow, adapted for touch, offline, and battery — while staying a remote client (no embedded Python runtime).

**Current baseline:** Talaria `1.0.0` (parity freeze) · Hermes API `dashboard-v0.17+`  
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

## Snapshot — where `1.0.0` stands

| Web Dashboard page | Talaria today |
|--------------------|---------------|
| Status | Done — sections + 5s refresh + recent sessions |
| Chat | Done — PTY + sidecar (model/tools/slash/approvals/rail/resize/markdown reading); Compose, not xterm |
| Config | Done — schema categories, bool switches, JSON escape hatch, reset/export/import |
| API Keys | Done — `env_catalog.json` merge, signup links, `/reload` tip |
| Sessions | Done — filters/search/rename/export/delete/prune |
| Logs | Done — compact filter row + poll tail + share; virtualized `LazyColumn` |
| Analytics | Done — daily bars + totals + model breakdown + 7/30/90-day range |
| Cron | Done — create/edit + presets + last/next + lifecycle |
| Profiles | Done — switch active + skills/config shortcuts |
| Skills | Done — search/categories + toolset activate (PUT) + Hub docs Custom Tab |
| MCP | Done — CRUD + test |
| Webhooks | Done — create / enable / delete |
| Pairing | Done — approve / revoke / clear-pending + sync notify |
| Channels | Done — configure sheet + test |
| System | Done — doctor / audit / backup / portal |
| Memory / Curator | Done — dedicated typed screens (was raw JSON in System) |
| Profile switcher (global) | Done — compact top-bar chip; stops sidecar on switch |
| Auth (gated WS tickets) | Done — tickets + OIDC Custom Tabs + paste fallback |
| Events / live fan-out | Done — `HermesEventClient` + `SidecarFrameParser`; live Activity + Chat status |
| Connection doctor | Done — status + ticket + PTY probe + 4401/4403 |

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

| Item | Status |
|------|--------|
| Notification richness | **Done** — sync maps gateway/pairing/cron/error diffs → channels; actionable **Approve** on pairing notifications (`PairingApproveWorker`) |
| Offline | **Done** — `SettingsStore` caches last Status snapshot + pending-pairing count; widget shows `· cached` when unreachable; Room already caches Sessions for read-only browse |
| Widgets | **Done** — Glance widget shows cached status + pending pairing badge |
| Large screens | **Done** — `NavigationSuiteScaffold` adapts bottom bar ↔ navigation rail by width (list-detail dual-pane chat remains an optional future enhancement) |
| Share targets | **wontfix** — `/api/files*` / chat attachments not exposed by the dashboard |
| Voice | **Done (by design)** — on-device `SpeechRecognizer` + TTS ship; heavier Whisper/Vosk engines are opt-in, documented, not in the default APK |

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
  (auto-reconnect with fresh WS tickets + backoff — added 2026-08)

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
  (markdown messages, role colors, collapsible tool JSON, LIVE chip + token
  counts in header — added 2026-08)
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
  (webhook url/secret copy-to-clipboard + one-time secret card — added 2026-08)
- [x] 11.x profiles + system/doctor/portal/memory

Phase 12 Auth polish
- [x] 12.1 OIDC/Portal Custom Tabs (Custom Tabs + cookie jar; paste-token fallback documented)
- [x] 12.2 in-app connection doctor (+ PTY probe)

Phase 13 Mobile-only excellence
- [x] Notification richness (pairing Approve action + gateway/cron/error diffs)
- [x] Offline snapshot cache (Status + pending pairing; Room Sessions cache)
- [x] Widget: cached status + pending pairing badge
- [x] Large screens: adaptive NavigationSuite rail/bar
- [~] Share targets — wontfix (files API not exposed upstream)
- [x] Voice on-device (heavier engines opt-in, not default APK)
```

**M5 — Parity freeze (`1.0.0`) reached:** all Phase 0–12 done-when criteria met, Phase 13
essentials shipped, and `docs/API.md` Gaps table contains only wontfix/non-goal items.

---

## Phase 14 — Density + feature expansion (post-1.0.0)

Parity was the floor; this phase reclaims screen space and surfaces backend signal the app was
already receiving but discarding. All items verified on-device against a running Hermes v0.19.0.

### 14.1 Design system + space consolidation
- [x] Spacing/density token scale (`ui/theme/Spacing.kt` + `LocalSpacing`).
- [x] Collapse the triple-stacked header: global profile strip → compact top-bar chip
  (`ProfileSwitcherChip`); single dense title line in `ScreenScaffold`.
- [x] Density passes: Manage grouped sections, Logs single filter row + virtualized list,
  Sessions compact filter header, You segmented theme + denser toggles, Privacy folded into You.

### 14.2 Live agent status (Chat)
- [x] Type the sidecar `session.info` frame (was dropped as `Raw`); show
  `model · reasoning · approval · yolo` (+ token/cost when a provider emits usage).
- [x] Fix `event`-envelope parsing (real type is `params.type`, not the outer method).

### 14.3 Event-driven Activity
- [x] Foreground sidecar subscription writes gateway/session/approval rows live
  (`HermesForegroundObserver`), complementing WorkManager polling.

### 14.4 Memory & Curator screens
- [x] Typed `MemoryState` / `CuratorState`; structured screens via `SimpleManageViewModel`;
  removed the raw-JSON sections from System.

### 14.5 Toolset activation + analytics range
- [x] Toolset enable/disable via `PUT /api/tools/toolsets/{name}`.
- [x] Analytics 7/30/90-day range selector.

### 14.6 Quality
- [x] Fix `SettingsStore.cloudSttOptIn` setter; extract pure `SidecarFrameParser` with unit tests;
  add `MainDispatcherRule` + typed-model decode tests.

**Not done — blocked upstream on v0.19.0** (re-check when Hermes updates):
- Backend slash-command discovery — no RPC method (`commands.list`/`slash.list` → unknown method);
  Chat keeps `SlashCommands.defaults`.
- Live log streaming — no upstream event stream; Logs polls into the virtualized list.

---

## Phase 15 — Hermes Desktop parity & beyond (post-1.0.0)

Reference: `NousResearch/hermes-agent/apps/desktop` (routes, panes, settings). API grounding verified against `hermes_cli/web_server.py` (2026-08): `/api/fs/*`, `/api/files/*`, `/api/media`, `/api/chat/image-upload`, `/api/audio/*`, `/api/learning/*`, `/api/ops/*`, `/api/gateway/drain`.

### Snapshot — where Talaria stands vs the Desktop app

Legend for this phase: **Done** shipped & verified on-device · **Blocked** endpoint returns
404 on Hermes v0.19.0 (re-check when the gateway updates) · **Deferred** larger effort, tracked.

| Desktop surface | Talaria today | Item |
|-----------------|---------------|------|
| Chat composer (images, rich input) | **Blocked** — `/api/chat/image-upload` 404 on v0.19.0 | 15.2 |
| Files pane (`/api/fs`) | **Done** — browse + text preview | 15.1 |
| Artifacts page | **Deferred** — no artifacts dir on install; Files pane covers browsing | 15.3 |
| Starmap (learning graph) | **Done** — stats + clusters + node list | 15.4 |
| Command center (usage + maintenance) | **Partial** — Analytics covers usage; ops maintenance 404 | 15.5 |
| Agents / subagent monitoring | **Deferred** — needs upstream `agent.*` event frames (unverified) | 15.6 |
| Command palette (⌘K) | **Done** — Manage quick-jump search | 15.7 |
| Multi-profile live streaming | **Deferred** — single-profile sidecar works; pool is large | 15.8 |
| Quick entry + floating HUD | **Deferred** — widget reply exists; PiP is large | 15.9 |
| Server voice (TTS/STT) | **Blocked** — `/api/audio/*` 404 on v0.19.0 | 15.10 |
| System ops (drain, prompt-size, debug-share) | **Blocked** — `/api/ops/*`, `/api/gateway/drain` 404 | 15.11 |
| Model management (visibility, fallbacks, endpoints) | **Done** — provider catalog + set active model | 15.12 |
| Terminal pane | **Done** — interrupt (Ctrl-C) + selectable output | 15.13 |
| Keybinds / themes / i18n | **Partial** — dynamic color + light/dark/system shipped; i18n/keybinds deferred | 15.14 |

### 15.1 Files pane (`Done` — browse + preview; upload/SAF pending)

**Why:** Desktop's Files pane browses the host tree; it is the most-used surface after chat for verifying agent work.

**Shipped (2026-08):** `FilesScreen` + `FilesViewModel` browse `/api/fs/list` from the
`/api/fs/default-cwd` root, dirs-first sorted, with an up-nav + cwd shortcut path bar;
tapping a file opens a `/api/fs/read-text` preview sheet (language + size + monospace body,
binary-safe). Typed models `FsEntry`/`FsListResponse`/`FsCwd`/`FsTextFile` with decode tests;
listings cached 10s via `ResponseCache`. Routed under Manage → System → Files. **Still open:**
share sheet, SAF download, and upload to cwd (`/api/files/upload`) — follow-up.

**Done when:** Tree browser over `/api/fs/list` + `/api/fs/read-text` + `/api/fs/git-root`; text preview, share sheet, SAF download; upload to cwd via `/api/files/upload`; "open in Files" from Artifacts/Status.

**How:**
1. `HermesApi`: `fsList`, `fsReadText`, `fsGitRoot`, `fsDefaultCwd`, `filesUpload` (Retrofit multipart).
2. `FilesViewModel`: lazy tree state, breadcrumb path, text preview cache, upload queue.
3. Compose: two-pane list/preview (`ModalNavigationDrawer`-style), path bar with cwd badge from `default-cwd`.
4. Test: decode `fs/list` fixture; path traversal guard test.

### 15.2 Attach images in chat (`Blocked` — upstream)

**Blocked (2026-08):** `/api/chat/image-upload` returns 404 and `/api/media` 422 (no id) on
Hermes v0.19.0, and `model/info` reports `supports_vision:false` for the active model.
Deferred until the gateway exposes image upload; re-probe on update.

**Why:** Desktop composer sends images (`/api/chat/image-upload` + `/api/media`); mobile users expect camera/gallery attach.

**Done when:** Gallery/camera pick → upload → inline thumbnail in transcript; assistant messages with media refs render images; tap = full-screen viewer.

**How:**
1. `HermesApi`: `chatImageUpload` (multipart), `mediaFetch` (auth'd `GET /api/media`).
2. `ChatViewModel`: attach state, upload progress, `image_upload` sidecar event handling.
3. Compose: picker row in composer, `AsyncImage` thumbnails, viewer dialog (pinch zoom).
4. Better-than-desktop: direct camera capture + share-to-chat intent filter.

### 15.3 Artifacts browser (`Deferred`)

**Deferred (2026-08):** the `HERMES_HOME/artifacts` dir does not exist on the test install
(`fs/list` → ENOENT) and there's no dedicated artifacts endpoint, so there's nothing to
verify against; the general **Files pane (15.1)** already browses any path incl. an artifacts
dir when present. Revisit as a specialized grid (thumbnails via `/api/media`) once artifacts exist.

**Why:** Desktop's Artifacts page lists files the agent produced; on mobile this is the fastest way to grab screenshots/reports.

**Done when:** Artifacts grid via `/api/fs` on the artifacts dir (HERMES_HOME/artifacts); image/PDF/HTML preview via `/api/media`; share + save-to-device; refresh + "new" badge.

**How:**
1. Resolve artifacts dir: `fsGitRoot`-style probe of `HERMES_HOME/artifacts` (fallback: `fsDefaultCwd` scan for `artifacts/`).
2. `ArtifactsViewModel`: flat list + grid, mtime sort, thumbnail cache (LRU).
3. Compose: gallery grid (StaggeredGrid), preview screen, share sheet.
4. Test: fs-list fixture decode; thumbnail cache eviction unit test.

### 15.4 Learning graph / Starmap (`Done` — structured view)

**Shipped (2026-08):** Manage → System → **Learning** (`LearningScreen` via `SimpleManageViewModel`
+ typed `LearningGraph`/`LearningNode`/`LearningStats`, decode-tested). Renders an overview
stats card (learned skills, categories, linked/isolated %, used, agent-created), category
cluster chips, and the skill/memory node list (kind, category, use-count, state, creator).
Cached via `ResponseCache`. **Still open:** Canvas radial render + node edit/delete + share-code import.

**Why:** Desktop's Starmap visualizes what Hermes learned (`/api/learning/graph` + `/api/learning/node`); mobile canvas is a natural fit.

**Done when:** Graph fetch → radial-time render (Canvas), node tap → detail sheet (PUT/DELETE node via existing endpoints), pasted share-code import.

**How:**
1. `HermesApi`: `learningGraph`, `learningNode`, `updateLearningNode`, `deleteLearningNode`.
2. `LearningViewModel`: node/edge caching, share-code encode/decode.
3. Compose: `Canvas` radial layout (port of `starmap/geometry.ts`), scrubber + legend, pinch zoom.
4. Test: geometry port unit tests (ported from desktop `starmap` tests where present).

### 15.5 Command center: usage + maintenance (`Partial`)

**Status (2026-08):** the Usage half is largely covered by the existing **Analytics** screen
(daily bars, totals, per-model breakdown, 7/30/90-day range). The Maintenance half is
**blocked** — `/api/ops/prompt-size` and `/api/ops/debug-share` return 404 on v0.19.0.
A dedicated tabbed Command Center is deferred until ops endpoints exist.

**Why:** Desktop's command center separates Usage (live list, debounced search) from Maintenance (memory files, cache ops). Talaria's Analytics shows totals only.

**Done when:** Usage tab = per-session/per-model rows w/ search + date filter; Maintenance tab = memory file browse + ops (`/api/ops/prompt-size`, `/api/ops/debug-share`) + cache prune where API allows.

**How:**
1. Extend `HermesApi` with `opsPromptSize`, `opsDebugShare`.
2. `CommandCenterViewModel`: usage aggregation from `/api/analytics` + sessions; maintenance ops.
3. Compose: tabbed page (Usage / Maintenance), `LazyColumn` rows, debounced search field.
4. Test: usage aggregation unit tests.

### 15.6 Subagent monitoring (`Deferred`)

**Deferred (2026-08):** requires upstream `agent.spawn`/`agent.done` sidecar frames whose
existence/names on v0.19.0 are unverified (the current sidecar emits tool/prompt/session.info
only). Building the parser + screen blind would be untestable; revisit once the event names
are confirmed against a run that spawns subagents.

**Why:** Desktop's Agents view shows spawned subagents live (status dots, streams). Users want to watch delegation from their pocket.

**Done when:** Subagent rows from session tree + sidecar events (`agent.spawn`/`agent.done` frames); status dot (running/done/failed); tap → transcript; optional push when a delegation finishes.

**How:**
1. Extend `SidecarFrameParser` with agent lifecycle frames (verify upstream event names first).
2. `AgentsViewModel`: live map of subagents (id → status), merged with `/api/sessions` tree.
3. Compose: Agents page (route + nav row), status-dot rows, stream preview.
4. Better-than-desktop: finish-notification via `TalariaNotifier`.

### 15.7 Global command palette (`Done` — v1: Manage quick-jump)

**Why:** Desktop ⌘K is the fastest way anywhere; phones need the same muscle memory.

**Shipped (2026-08):** a `CommandPalette` `ModalBottomSheet` opens from the search icon
in the Manage top bar — a fuzzy-filtered list (title/subtitle/section) of every Manage
destination; tapping a result navigates there and dismisses. Directly serves fast menu
navigation. **Still open:** reach-from-anywhere trigger (edge swipe / app-icon shortcut),
profile/session actions, and recents — follow-up.

**Done when:** Palette sheet from anywhere (edge swipe / FAB / long-press app icon): search + run — open screen, switch profile, jump to session, run system action; recent actions first.

**How:**
1. `CommandPaletteState`: action registry (screens, profiles, sessions, ops), fuzzy filter, recents (Room table).
2. Compose: full-screen sheet, haptic on pick, deep links (`talaria://<screen>`).
3. Better-than-desktop: app-icon shortcut menu (Android ShortcutManager) for top 4 actions.

### 15.8 Multi-profile live streaming (`Deferred`)

**Deferred (2026-08):** single-profile sidecar + management-profile switch (with cache clear)
already works; a bounded multi-sidecar pool with merged, profile-tagged lists is a large,
lifecycle-sensitive change best done as its own focused effort.

**Why:** Desktop keeps background profiles streaming and merges lists; Talaria stops the sidecar on switch.

**Done when:** One sidecar per profile (bounded pool, e.g. 3), merged session list with profile tag, tap row = foreground switch; stopped sidecars reconnect on foreground.

**How:**
1. `ProfileSidecarPool`: N `HermesEventClient`s keyed by profile; lifecycle on app foreground/background.
2. Merge in `SessionsViewModel` + `ActivityViewModel` (profile tag on rows).
3. Compose: profile chip on session rows; switch = `ProfileSwitcherBar` behavior, no full reconnect.
4. Test: pool eviction + merge unit tests.

### 15.9 Quick-entry widget + PiP chat (`Deferred`)

**Deferred (2026-08):** a Glance status widget already ships; adding text-input quick entry
and a Picture-in-Picture chat surface (streaming into a PiP window) is a sizable, device-
capability-sensitive effort tracked for a dedicated pass.

**Why:** Desktop's quick entry + floating HUD; a phone can beat it: type into the agent from the home screen, or float the chat over any app.

**Done when:** Glance widget gains a text input (quick entry → new session); Picture-in-Picture mode floats the active chat (PiP button in chat top bar); PiP shows streaming text + mic.

**How:**
1. Widget: `RemoteViews` input (Glance action → WorkManager → PTY send to last session; create if none).
2. PiP: `PictureInPictureParams` on ChatScreen; `RemoteAction`s (mic, close); stream renders into PiP surface via sidecar events.
3. Test: widget action → session-create unit test; PiP eligibility check on devices.

### 15.10 Server voice (`Blocked` — upstream)

**Blocked (2026-08):** `/api/audio/speak` and `/api/audio/transcribe` return 404 on v0.19.0
(only `/api/audio/elevenlabs/voices` responds). On-device STT/TTS remain shipped; server voice
is deferred until the audio endpoints are enabled.

**Why:** `/api/audio/speak` + `/api/audio/transcribe` + `/api/audio/elevenlabs/voices` exist; desktop plays assistant audio. On-device STT is done; server TTS opens ElevenLabs-quality replies.

**Done when:** Assistant replies can render as audio attachments (`/api/audio/speak`, voice picker); server-transcribe fallback when on-device STT missing.

**How:**
1. `HermesApi`: `audioSpeak`, `audioTranscribe`, `elevenlabsVoices`.
2. `AudioPlayer` (Media3) + voice picker in Settings → Voice.
3. Compose: audio bubble in transcript (play/pause, duration); long-press reply → "Play aloud".
4. Test: bubble state machine unit tests.

### 15.11 System ops (`Blocked` — upstream)

**Blocked (2026-08):** `/api/gateway/drain`, `/api/ops/prompt-size`, `/api/ops/doctor` and
`/api/ops/debug-share` all return 404 on v0.19.0. Existing gateway start/stop/restart remain.
Re-probe and wire the ops rows when the endpoints appear.

**Why:** Desktop exposes gateway drain + ops; support/debug workflows need them on the go.

**Done when:** System screen gains: gateway drain, `/api/ops/prompt-size` checker, debug-share (copies a shareable bundle link/JSON).

**How:**
1. `HermesApi`: `gatewayDrain`, `opsPromptSize`, `opsDebugShare`.
2. SystemViewModel actions with confirm dialogs (drain warns like restart).
3. Compose: rows in System screen; share sheet for debug bundle.

### 15.12 Model management (`Done` — catalog + set active)

**Shipped (2026-08):** Manage → Capabilities → **Models** (`ModelsScreen` + `ModelsViewModel`,
typed `ModelProvider`/`ModelOptionsResponse`, decode-tested). Lists every provider from
`/api/model/options` (name, model count, source, auth state, warnings), auto-expands the
current provider, highlights the active model (from `/api/model/info`), and sets a model via
`PUT /api/model/set` (invalidates the cached catalog). **Still open:** fallback-chain editor,
visibility toggles and custom-endpoint CRUD (config-schema writes).

**Why:** Desktop settings manage providers, fallback models, visibility, custom endpoints; the schema editor exposes the fields but curated forms beat raw JSON on mobile.

**Done when:** Models screen: provider list (enabled/disabled), per-model fallback chain editor, visibility toggle (hide from picker), custom-endpoint CRUD — all via config schema PUT + `/api/model/*`.

**How:**
1. Map schema paths: `providers.*`, `models.fallback`, custom endpoints keys (re-check schema).
2. `ModelsViewModel`: read/merge/write with schema validation (reuse ConfigScreen save path).
3. Compose: grouped forms with switches + reorderable fallback list.
4. Test: schema round-trip tests (existing fixture coverage extended).

### 15.13 Terminal pane (`Done` — interrupt + select)

**Shipped (2026-08):** in Chat terminal mode a **Stop** action sends Ctrl-C (`\x03`) to the
active PTY (`ChatViewModel.sendInterrupt` → `PtyWebSocketSession.sendRaw`), and terminal
output is wrapped in a `SelectionContainer` so users can select/copy PTY text. **Still open:**
font-size control and alternate-screen-aware rendering.

**Why:** Desktop ships a real terminal pane; Talaria's PTY view is text-only.

**Done when:** Terminal view gains: font size control, copy/paste (select mode), send-interrupt key row, and (where the backend supports it) alternate-screen-aware rendering.

**How:**
1. Extend `PtyWebSocketSession` output handling for terminal controls.
2. Compose: selectable text + action bar (copy, interrupt `\x03`, clear).
3. Test: ANSI control parsing (extend `AnsiStripper` tests).

### 15.14 Keybinds / themes / i18n (`Partial`)

**Status (2026-08):** themes are **done** — Material You dynamic-color toggle + light/dark/system
selector ship on the You screen (`Theme.kt`, `SettingsStore`). i18n string extraction and
hardware-keyboard shortcuts remain **deferred** (low priority for a touch-first client).

**Why:** Desktop has rebindable keybinds, multiple themes, ja/en i18n; a phone mostly needs theme + locale.

**Done when:** Material You dynamic color toggle; light/dark/auto; `strings.xml` restructure for future locales (en baseline); hardware-keyboard shortcuts for palette + send.

**How:**
1. Theme: dynamic color option in Settings → Appearance.
2. i18n: extract all user-facing strings to resources (en only for now; structure ready).
3. Keybinds: `onPreviewKeyEvent` for connected keyboards (palette, new chat, send).

---

## References

- Dashboard feature docs: https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard  
- Upstream API helper: `NousResearch/hermes-agent` → `web/src/lib/api.ts`  
- Chat implementation reference: `web/src/pages/ChatPage.tsx`, chat sidecar PR history (`/api/pty` + `/api/ws` + `/api/events`)  
- Talaria maps: [docs/API.md](docs/API.md), [ARCHITECTURE.md](ARCHITECTURE.md)  
- Contributing: [CONTRIBUTING.md](CONTRIBUTING.md)
