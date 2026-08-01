# Talaria ↔ Hermes Desktop — Feature Parity Audit

**Date:** 2026-08-01 · **Auditor:** Hermes Agent (with 2 delegated inventory agents)
**Reference:** Hermes Desktop `v0.17.0` (`~/.hermes/hermes-agent/apps/desktop`) vs Talaria `main` @ `b173d8e`
**Method:** Source-grounded inventories of both trees (desktop: ~700 files walked, 100+ features; Talaria: 99 Kotlin files walked, ~95 features), then cross-mapped.
**Legend:** ✅ present · 🟡 partial · ❌ missing · ⚙️ not applicable on phone (desktop/Electron-local; adaptation suggested where sensible)

---

## 1. Chat & composer

| Desktop feature | Desktop evidence | Talaria | Talaria evidence / notes |
|---|---|---|---|
| Multi-tab sessions | `src/app/chat/index.tsx` | ✅ | `feature/chat/ChatScreen.kt` (SessionTabStrip, per-tab claiming, rename, persistence) |
| Live PTY/WebSocket bridge | `src/app/session/hooks/use-message-stream/` | ✅ | `core/network/PtyWebSocketSession.kt` (ticket/token auth, resume, resize) |
| Reading + terminal transcript modes | `session-view.tsx` | ✅ | `ChatScreen.kt` TranscriptMode; clean-transcript policy (turn lands on `message.complete`) |
| Markdown rendering | `markdown-text.tsx`, `shiki-block.tsx` | 🟡 | `SimpleMarkdown.kt` — offline bold/italic/inline-code only; **no syntax-highlighted code, no KaTeX math, no mermaid, no rich URL embeds** (YouTube/Spotify/Twitter/maps on desktop) |
| Image attachments | `composer/attachments.tsx`, `lib/embedded-images.ts` | ✅ | `ChatImageAttachments.kt` — magic-byte validated, 25MB cap, `image.attach_bytes` RPC |
| File/folder pickers (native dialogs) | `electron/preload.ts` (selectPaths) | 🟡 | Photo Picker + SAF share only — no generic file attach |
| Drag & drop files into chat | `use-file-drop-zone.ts`, `chat-drop-overlay.tsx` | ⚙️ | Phone equivalent = share sheet (✅ ACTION_SEND intake) |
| Slash commands (live catalog + fuzzy) | `use-slash-completions.ts` | ✅ | `ChatViewModel.kt` — live `commands.catalog` + `complete.slash` RPC, aliases, fallback |
| @-mention / inline file refs | `inline-refs.ts`, `use-at-completions.ts` | ❌ | Missing |
| URL refs / linkify chips | `url-refs.ts`, `url-dialog.tsx` | ❌ | Missing |
| Path refs (typed paths → chips) | `path-refs.ts` | ❌ | Missing |
| Emoji completions | `use-emoji-completions.ts` | ❌ | Missing |
| Composer queue (send while busy) | `queue-panel.tsx` | ❌ | Missing |
| Composer input history (↑/↓) | `composer-input-history.ts` | ❌ | Missing (drafts persist, history doesn't) |
| Rich editor undo/redo + context menu | `rich-editor.ts`, `context-menu.tsx` | ❌ | Missing |
| Voice input (STT, barge-in, stop-word) | `use-composer-voice.ts`, `voice-barge-in.ts` | 🟡 | `SpeechCoordinator.kt` — continuous on-device STT, partials; **no barge-in/stop-word** |
| Voice playback / auto-speak | `use-auto-speak-replies.ts` | 🟡 | `TtsSpeaker.kt` opt-in read-out; no auto-speak toggle per session |
| Model pill + trigger popover | `model-pill.tsx`, `trigger-popover.tsx` | 🟡 | Model picker ✅; **no steer/trigger UI** (mode shown read-only in header) |
| Composer popout (floating window) | `use-composer-popout.ts` | ⚙️ | PiP chat suggested |
| Session-scoped drafts | `store/composer.ts` | ✅ | Room draft persistence per connection |
| Thread: reactions, timestamps, timeline | `thread/list.tsx`, `message-reactions.tsx` | 🟡 | Messages + timestamps; **no reactions** |
| Message edit + rewind + branch | `use-prompt-actions/rewind.ts` | ❌ | Missing |
| Tool call rendering (approval UI, delegate rows, run tickers, changed-files cards) | `assistant-ui/tool/*` | 🟡 | Prompt dialogs (approve/deny/clarify/sudo/secret) ✅; single `WorkingIndicator` spinner instead of per-tool blocks; **no changed-files cards** |
| Scroll-to-bottom button | `scroll-to-bottom-button.tsx` | ✅ | auto-follow + jump |
| Interrupt generation | session controls | ✅ | `vm.sendInterrupt()` (raw `\x03`) |
| Clarify prompts | `clarify-tool.tsx` | ✅ | `clarify.respond` |
| Session info (model, effort, approval mode, yolo, tokens, cost) | shell panels | ✅ | live header via sidecar `session.info` |

## 2. Right sidebar & preview (desktop's biggest gap area)

| Desktop feature | Desktop evidence | Talaria | Notes |
|---|---|---|---|
| Files pane (project tree, lazy, cwd-rooted) | `right-sidebar/files/tree.tsx` | 🟡 | `FilesScreen.kt` — browse/read/edit (git-root aware), refresh, up, preview; **no rename/delete/reveal, no upload/download, no mkdir** |
| Remote file picker | `files/remote-picker.tsx` | ❌ | Missing |
| Drag & drop file management | `files/dnd-manager.ts` | ⚙️ | SAF covers local side |
| Review pane (git diff, stage/unstage/revert, commit, push, PR) | `right-sidebar/review/*` | ❌ | ROADMAP next-work #8 |
| Terminal pane (node-pty, tabs, persistent) | `right-sidebar/terminal/*` | ❌ | Chat PTY bridge exists — standalone persistent terminal pane suggested |
| Agent terminal stream (live tool output) | `terminal/agent-terminal-stream.ts` | ❌ | Missing |
| Preview rail (HTML/file/artifact/console, live reload) | `chat/right-rail/preview*.tsx` | ❌ | Missing |
| Logs pane (live agent log tail) | `contrib/panes.tsx` | 🟡 | LogsScreen (file viewer w/ filters) — not live-tail |
| Preview file watching (native fs watch) | `electron/preload.ts` | ⚙️ | Server-side fs watch needed |

## 3. Pages & overlays

| Desktop feature | Desktop evidence | Talaria | Notes |
|---|---|---|---|
| Command palette (⌘K) | `command-palette/index.tsx` | 🟡 | ManageHome has a fuzzy quick-jump palette — narrower (manage destinations only) |
| Command Center (status/logs/usage/maintenance, restart GW, update) | `command-center/*` | 🟡 | Split across Status + System + Logs screens; no unified center |
| Starmap / learning graph (radial viz, timeline scrubber, node edit/delete, share codes) | `starmap/*` | 🟡 | `LearningScreen.kt` — stats, clusters, node list, edit/delete via `/api/learning/*`; **no radial graph, no timeline, no share codes** |
| Agents page (subagent tree, live status, stream entries, timing) | `agents/index.tsx` | ❌ | Only single WorkingIndicator — **no subagent monitoring** |
| Artifacts browser (filters, image previews, download) | `artifacts/*` | ❌ | Missing |
| Skills/Capabilities page (master-detail, archive, usage badges) | `skills/*` | 🟡 | SkillsScreen ✅ (enable/disable, toolsets, Hub install); **no archive, no usage badges** |
| MCP tab | `skills/mcp-tab.tsx` | ✅ | McpScreen — add/test/enable/delete, catalog install, OAuth flow |
| Messaging page + pairing | `messaging/index.tsx` | ✅ | ChannelsScreen + PairingScreen |
| Cron page (CRUD, pause/resume/trigger, **run history, blueprints**) | `cron/*` | 🟡 | CronScreen CRUD ✅; **no run history, no delivery targets, no blueprints** (next-work #4) |
| Webhooks page | `webhooks/index.tsx` | ✅ | WebhooksScreen — create/delete/enable, secret + URL copy |
| Profiles page (+ soul editor) | `profiles/*` | ✅ | ProfilesScreen — create/rename/delete, soul, auto-describe |
| Session picker / model picker / model visibility overlays | `*overlay*.tsx` | 🟡 | Model picker sheet ✅; visibility ❌ |
| Updates overlay (check/apply + changelog) | `updates-overlay.tsx` | 🟡 | SystemScreen update check ✅; no apply/changelog |
| Pet generate/hatch, pet overlay, floating pet | `pet-*/*` | ⚙️ | Desktop-window concepts — n/a on phone |

## 4. Shell & chrome

| Desktop feature | Talaria | Notes |
|---|---|---|
| Pane shell (splittable/tabbed/floating panes) | ⚙️ | n/a |
| Titlebar/statusbar contribution system | ⚙️ | n/a |
| Gateway menu panel (live log tail, restart) | 🟡 | StatusScreen gateway state + SystemScreen restart |
| Context usage panel (live) | 🟡 | tokens/cost in chat header |
| Approval mode menu | ❌ | Approval MODE switching not exposed (prompt dialogs work) |
| Notification toast stack | 🟡 | Android notifications cover this natively |
| Onboarding flow (provider/model/OAuth) | 🟡 | ConnectScreen + doctor; no guided provider onboarding (next-work #1) |
| Gateway connecting / boot failure overlays | ✅ | Connection states + doctor |
| Find-in-page (Ctrl+F) | ⚙️ | n/a (Android has native find) |
| Language switcher | ❌ | **No i18n at all** — desktop ships 5 locales |

## 5. Sessions & streaming

| Desktop feature | Talaria | Notes |
|---|---|---|
| Virtualized session list (search, date groups, reorder) | 🟡 | SessionsScreen search/filter/prune; no reorder |
| Session pin/split/open-in-window/delete/export | 🟡 | Delete ✅, export markdown ✅ (FileProvider); pin/split/window n/a |
| Session search across sessions | ✅ | `/api/search` |
| Session branching / lineage | ❌ | Missing |
| Compaction UI | ❌ | Missing |
| YOLO session support | 🟡 | ⚡yolo shown in header; no YOLO control |
| Session switcher HUD (Ctrl+Tab) | ⚙️ | Tabs serve this |
| Secondary session windows | ⚙️ | Multi-window Android suggested |
| Multi-profile streaming (background profiles keep streaming) | ❌ | Profile switching ✅ (ProfilesScreen + switcher chip); **live multi-profile streaming missing** |
| Remote/SSH backend connections | ⚙️ | Electron-local (SSH bootstrap) |
| Hermes Cloud login | ❌ | SystemScreen shows portal JSON; no cloud sign-in |

## 6. Settings

| Desktop section | Talaria | Notes |
|---|---|---|
| Config (schema-driven sections) | ✅ | ConfigScreen — schema + defaults + validation |
| Providers/API keys | ✅ | ApiKeysScreen (catalog overlay, redacted) |
| Model settings (fallback models, provider config) | 🟡 | ModelsScreen; deep provider/fallback = next-work #5 |
| Gateway settings (local/remote/cloud, test conn) | 🟡 | ConnectScreen + doctor; no SSH hosts |
| Appearance (themes, translucency, zoom) | 🟡 | Dark/light/system + Material You; **no preset themes, no backend skin sync** (desktop: 6 presets + VS Code marketplace + user themes) |
| Keybinds | ⚙️ | n/a (launcher shortcuts exist) |
| Notifications settings | ✅ | YouScreen toggles + per-kind channels |
| Billing | ❌ | Server-side only |
| Plugins settings | ❌ | next-work #10 |
| Computer-use panel | ❌ | Missing |
| Terminal backend panel | ❌ | Missing |
| Toolset config panel | 🟡 | SkillsScreen toolset enable; no deep config |
| Memory provider panel | ✅ | MemoryScreen (list/activate/reset) |
| Custom endpoints | ❌ | next-work #1 |
| Sessions settings + uninstall | ❌ | Missing (server-side sessions settings) |
| Quick entry settings / pet settings | ⚙️ | n/a |
| About (version, logs, update channel) | 🟡 | YouScreen + SystemScreen |

## 7. Platform capabilities — Talaria EXCEEDS desktop

These have no desktop equivalent (native Android wins):

- ✅ Glance status widget + Quick Settings tile (`widget/`)
- ✅ Actionable notifications: inline Reply, Approve Pairing, 10 channels, agent-task FGS monitor (`core/notifications/`)
- ✅ Launcher shortcuts (4 static)
- ✅ Share-sheet intake (text + image → prefilled chat)
- ✅ Keystore-encrypted credentials (AES256-GCM/SIV) vs desktop safeStorage
- ✅ TLS cert pinning per profile
- ✅ Room offline cache + doze-aware sync workers
- ✅ Connection doctor (preflight, WS ticket probe, close-code explainer)
- ✅ Activity feed (Room-persisted, filterable)
- ✅ Adaptive nav suite + edge-to-edge + Material You
- ✅ OIDC PKCE with silent token refresh (RFC 8252)

## 8. ROADMAP verification (claims vs code)

### Completed foundation — verified ✅ except where noted
1. Native Compose app, adaptive nav, edge-to-edge, themes, shortcuts, Photo Picker, SAF, widget, QS tile, actionable notifications — **✅ VERIFIED**
2. Multi-connection auth (password-cookie, session-token, bearer, OIDC PKCE), WS tickets, TLS pins — **✅ VERIFIED**
3. Scoped Room/cache/draft/chat/widget/worker state per connection+profile — **✅ VERIFIED**
4. Multi-tab PTY chat, modes, death-restore, reconnect, image attach, dictation/TTS, share-to-chat, model switch — **✅ VERIFIED**
5. `/api/ws` + `/api/events` handling (tools, usage, prompts, approvals, clarification, sudo, secrets, lifecycle, completion, thread notifications) — **✅ VERIFIED**
6. Predictive slash palette — **✅ VERIFIED** (live catalog + RPC)
7. Core management surfaces (status, sessions, config, env, models, cron, skills, toolsets, MCP, channels, pairing, webhooks, profiles, files, learning, memory, curator, logs, analytics, operations, system) — **🟡 PARTIAL — screens exist, depth gaps:** Files = browse/read/edit only (no upload/download/mkdir/delete); Learning = list/stats (no graph viz/share); Cron = no run history/blueprints; Skills = no archive/usage badges; System ops = backup/checkpoint present but imports/backup-download = next-work #7.

### Audit hardening — **✅ ALL VERIFIED** (password-login contract, RFC 8252 refresh, payload corrections, strict validation, scoped Room migration, race fixes, signature-validated images, scoped FGS, MCP OAuth + approved catalog, profile SOUL, green tests/lint/APK)

### Next remote-capable parity work — all 10 items **❌ NOT COMPLETE** (confirmed still missing in code):
1. Guided provider onboarding ❌ · 2. Session administration (bulk cleanup, imports, stats, descendants, restore) ❌ · 3. Managed file transfer (upload/download/mkdir/delete, media previews, progress, confirm) ❌ · 4. Cron run history/delivery targets/blueprints ❌ · 5. Skill authoring + deep toolset config ❌ · 6. Telegram/WhatsApp onboarding ❌ · 7. Operations imports/backup download/hooks/checkpoints/raw config/debug-share ❌ (partial: backup + checkpoint exist in SystemScreen) · 8. Remote Git/review ❌ · 9. Hosted audio transcription/speech ❌ · 10. Plugin marketplace ❌

## 9. Additional features suggested from the audit

**Parity closures (highest value first):**
1. **Artifacts browser** — desktop has it; Talaria has nothing (`/api/artifacts`-style, pending API check)
2. **Subagent/agent monitor** — expand WorkingIndicator into a live agent/tool tree (desktop `agents/`)
3. **Markdown upgrade** — syntax-highlighted code blocks + KaTeX + mermaid on-device (desktop `shiki`, `katex-memo`)
4. **Message edit + rewind + branch-in-new-chat** (desktop `rewind.ts`)
5. **Composer queue** + **input history** — cheap, high-value chat UX (desktop `queue-panel`, `composer-input-history`)
6. **Starmap view** — radial graph + timeline for LearningScreen (desktop `starmap/`)
7. **Changed-files cards** in thread — agent file-change summaries (desktop `changed-files-card.tsx`)
8. **i18n** — 5 locales on desktop (en/ja/zh/zh-Hant/ar); Talaria is English-only
9. **Themes** — preset theme palettes + optional backend skin sync (desktop 6 presets)
10. **Steer/trigger popover** — set model+effort+mode from chat (desktop `trigger-popover.tsx`)
11. **Command Center unification** — one screen: status/logs/usage/maintenance (desktop `command-center/`)
12. **URL refs + path refs + @mentions** in composer
13. **Approval-mode switching** from the app
14. **Session compaction + branching** controls
15. **YOLO mode control** (currently display-only)

**Platform-native ideas (beyond desktop):**
16. **Quick-entry widget** — Glance widget that composes + sends directly (desktop Quick Entry adapted)
17. **PiP floating chat** — picture-in-picture chat for multitasking (desktop pet-overlay adapted)
18. **Server voice** — Hermes-hosted STT/TTS as fallback when Android speech unavailable (next-work #9)
19. **Multi-profile streaming** — background profiles keep streaming; merge lists (desktop `gateway.ts` secondary registry)
20. **Terminal pane tab** — standalone persistent terminal using the existing PTY bridge
21. **Session import** — import exported markdown/JSON sessions (export exists)
22. **Notification quiet hours / per-agent channels** — polish on today's agent-task notifications
23. **Find-in-session** — search within a session transcript
24. **Backend skin/theme sync** — pull server-side skin into the app

---

*Inventories: deleg_c8a27fe5 (2 agents, source-grounded, all paths verified). Desktop has no system tray (checked). Talaria has no i18n, no artifact/subagent surfaces, no git review, no terminal pane, no composer queue/rewind — the headline gaps.*
