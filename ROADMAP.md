# Roadmap

Current released version: `0.5.0` (2026-08-02). Development baseline: Hermes Agent `v0.19.1` (`470cf66`, 2026-08-01).

Talaria’s target is feature parity for remote-capable Hermes chat and management workflows, expressed as a native Android product. “Parity” does not mean copying Electron-only window, tray, local-shell, or pet-overlay behavior onto a phone.

## Completed foundation (verified against `main` @ `84768b6`, 2026-08-02)

- Native Compose app for API 28–36 with adaptive navigation, edge-to-edge layouts, theme controls, app shortcuts, Photo Picker, SAF sharing, widget, Quick Settings tile, and actionable notifications.
- Multiple encrypted saved connections with password-cookie, session-token, bearer, and native OIDC PKCE authentication; WebSocket tickets and optional HTTPS certificate pins.
- Scoped Room/cache/draft/chat/widget/worker state for every connection and Hermes management profile.
- Multi-tab PTY chat with reading and terminal modes, process-death restoration, reconnect/resume, image attachments, voice dictation/TTS, share-to-chat, and model switching.
- Structured `/api/ws` and `/api/events` handling for tools, usage, prompts, approvals, clarification, sudo, secrets, lifecycle, foreground/background completion, and thread-aware Android notifications.
- Predictive slash-command palette backed by live `commands.catalog` and `complete.slash`, with alias/fuzzy ranking and a compatibility fallback catalog.
- Core management surfaces: status, sessions, config, env/API keys, models, cron, Skills Hub, toolsets, MCP catalog/OAuth, channels, pairing, webhooks, profiles, workspace files, learning, memory, curator, logs, analytics, operations, and system controls.

### Landed in the 0.4 → 0.5 window (previously next-work backlog, now implemented)

- Guided provider onboarding: provider validation, provider OAuth, custom endpoints, credential pools, recommended/auxiliary model selection groundwork, and MoA-aware flows.
- Session administration: bulk/empty cleanup, stats, import, latest-descendant helpers, and explicit conflict-safe restore flows.
- Cron run history, delivery targets, and blueprint instantiation.
- Skill authoring/content editing with frontmatter-aware editor.
- Operations imports (JSON + upload), backup download, hooks, debug-share, and raw config controls. (Remaining ops depth: checkpoints, config-migrate, dump, prompt-size.)
- Remote Git/review workflows: status, branches, worktrees, diff, stage/unstage/revert, commit, push, PR creation, and branch switching.
- Standalone terminal pane (persistent PTY, input history, scrollback) alongside the chat PTY bridge.
- Hermes-hosted audio transcription/speech (server voice) with capability probing and Android STT/TTS fallback.
- Artifacts browser (transcript-grounded extraction, image/text/archive preview, share) and live subagent/tool monitor.
- Composer queue (send-while-busy, FIFO drain) and per-session input history (↑/↓, 50-entry cap).
- Steer/trigger popover: model, reasoning effort, approval mode, and YOLO session controls.
- Command Center (unified status/logs/usage/maintenance view), theme presets + server skin sync, and i18n (ja/zh/zh-TW/ar).
- Notification quiet hours and per-agent channels; quick-entry widget; PiP chat; multi-profile streaming with session merge; changed-files cards; learning graph visualization + timeline; chat rewind and session controls.

## Audit hardening completed after 0.3.0

- Replaced Basic headers with the current JSON password-login/cookie contract and implemented RFC 8252 native OIDC token refresh.
- Corrected current Hermes request methods and payloads for profiles, models, config, cron, search, pairing, and sidecar prompts.
- Added strict URL/deep-link/cookie/TLS-pin validation and cross-scope rejection for notification actions.
- Migrated Room storage without destructive fallback and made all cached data connection/profile-scoped.
- Fixed share/draft/transcript races, reconnect lifecycles, stale sidecar responses, worker retries, notification completion semantics, and status-bar inset duplication.
- Added signature-validated, bounded image attachments and safe session-export filenames.
- Replaced unused microphone/general-sync foreground services with a scoped active-turn monitor that exists only while a user-started agent task needs permission or completion tracking.
- Added MCP OAuth and approved-catalog installation, and brought profile CRUD/SOUL/description behavior up to the current API.
- Expanded unit/contract coverage and kept debug unit tests, Android lint, and APK assembly green. *(Note: lint is currently red on `main` — 34 `MissingTranslation` errors from the i18n merge; CI does not run lint. See “v0.6 release floor” below.)*

## Where Talaria already exceeds desktop

- Glance status widget + Quick Settings tile, launcher shortcuts, share-sheet intake, and a quick-entry widget.
- Actionable, thread-aware notifications (inline reply, pairing approval, 10 channels, quiet hours, per-agent channels, scoped active-turn foreground monitor).
- Keystore-encrypted credentials (AES256-GCM/SIV), optional TLS pinning per profile, Room offline cache, doze-aware sync workers, connection doctor, OIDC PKCE with silent refresh, edge-to-edge Material You UI.

## Design direction: progressive disclosure (decluttered, app-wide)

**Nothing on screen at once.** Talaria surfaces hide secondary detail behind groups, collapsible sections, and overflow menus; a screen should read at a glance and drill in on demand. This is the app-wide design standard, not a per-screen preference.

Patterns (established in the `3de87d8` UI decluttering merge, 2026-08-02):

- **Group → drill down.** Manage was a 25-row flat list; it is now five categories (Agents / Capabilities / Workspace / Messaging / System) that open focused sub-lists. Every destination stays one command-palette search away, so the extra tap only affects browsing, not power use.
- **Overflow, don't stack.** Chat's top bar dropped 8 icon buttons to a contextual slot (interrupt while working, transcript toggle while idle) plus sessions/steer, with the rest (agent activity, PiP, compact, edit title) behind one overflow menu.
- **Section, don't wall.** You's flat wall of switches is grouped into Appearance / Notifications / Voice & speech / Data & privacy, with alert toggles moved to the dedicated notification screen and a uniform row height.
- **Collapse by default.** System's maintenance groups (Doctor, Security audit, Backup, Import, Hooks, Debug share, Raw YAML, Update check, Portal) are collapsible and start collapsed.
- **Right home for settings.** Themes and Voice moved from Manage to You — device-level preferences live with the device; Manage owns the connected Hermes host.

### UX backlog for the declutter rollout

| # | Item | Status | Priority |
|---|------|--------|----------|
| 18 | Extract the collapsible `Section` composable (currently private in SystemScreen) into `ui/components` and reuse it across Manage detail screens | ✅ | MEDIUM |
| 19 | Apply progressive disclosure to remaining flat surfaces: Status, Config, Command Center, Cron, Skills, MCP, Files, Logs, Analytics, Sessions | ✅ | MEDIUM |
| 20 | Audit every screen for "everything at once" violations before new features land (design gate for the v0.6 UX backlog) | 🟡 | MEDIUM |

## Next remote-capable parity work (v0.6 plan)

> Status per item verified against source and the live Hermes `v0.19.1` API (2026-08-02). All items below were implemented and shipped in v0.6.0 (2026-08-02) via a parallel MissionDeck agent wave; verified on the Android emulator against the live backend.

### API-backed features

| # | Item | Status | Priority |
|---|------|--------|----------|
| 1 | Managed file transfer — `/api/files` upload/download/mkdir/delete/read plus `/api/media` listing with media previews and progress. Files today is text browse/edit only (`/api/fs`) | ✅ | HIGH |
| 2 | Dashboard plugin surface — `/api/dashboard/plugins` + hub/rescan/visibility, agent-plugin install/enable/disable/update, plugin-providers. Live server also exposes Kanban and Achievements plugin routes (a Kanban board view is a natural phone surface) | ✅ | HIGH |
| 3 | Guided Telegram and WhatsApp onboarding — `/api/messaging/{telegram,whatsapp}/onboarding/start`, `/{pairing_id}`, `/{pairing_id}/apply` | ✅ | MEDIUM |
| 4 | MCP server edit — collection `PUT /api/mcp/servers` (Talaria can add/delete/enable/test but not edit) | ✅ | MEDIUM |
| 5 | Memory provider config/setup/OAuth — `/api/memory/providers/{name}/config`, `/setup`, `/oauth/start` + `/oauth/status` | ✅ | MEDIUM |
| 6 | Computer-use status/permission grant and terminal backend panel — `/api/tools/computer-use/status`, `/permissions/grant`, `/api/tools/terminal/backends`, `/backend` | ✅ | MEDIUM |
| 7 | Toolset deep config — `/api/tools/toolsets/{name}/config`, `/env`, `/model`, `/models`, `/provider`, `/post-setup` | ✅ | MEDIUM |
| 8 | Model MoA/auxiliary/recommended-default — `/api/model/moa`, `/auxiliary`, `/recommended-default` | ✅ | LOW |
| 9 | Update apply + gateway drain — `POST /api/hermes/update` (check exists; apply does not), `POST /api/gateway/drain` before restart | ✅ | LOW |
| 10 | Ops depth — `/api/ops/checkpoints` + `/prune`, `/config-migrate`, `/dump`, `/prompt-size` | ✅ | LOW |
| 11 | Minor surfaces — `/api/analytics/models`, `/api/audio/elevenlabs/voices`, `/api/audio/speak-stream`, `/api/egress/status`, `/api/cron/fire`, `/api/profiles/sessions`, `/api/profiles/{name}/model\|open-terminal\|setup-command` | ✅ | LOW |

### UX backlog (no server work required)

| # | Item | Status | Priority |
|---|------|--------|----------|
| 12 | Composer refs — @-mentions, URL/path chips, emoji completions (desktop parity) | ✅ | MEDIUM |
| 13 | Find-in-session — search within a transcript | ✅ | MEDIUM |
| 14 | Markdown upgrade — syntax-highlighted code blocks (offline); current `SimpleMarkdown` renders bold/italic/inline-code only | ✅ | HIGH (chat is the primary surface) |
| 15 | Message edit + branch-in-new-chat (rewind exists) | ✅ | LOW |
| 16 | Session pin + compaction UI | ✅ | LOW |
| 17 | i18n completion — ~40 strings missing per locale (command center/themes/voice titles, notification actions); widget and PiP strings are hardcoded English | 🟡 | MEDIUM |

## v0.6 release floor (bug fixes to land with v0.6)

| Item | Detail | Fix |
|------|--------|-----|
| Lint red on `main` | 34 `MissingTranslation` errors from the i18n merge; CI runs tests + release build but not lint, so it ships | Add `:app:lintDebug` to CI; translate or mark `translatable="false"` the outstanding strings |
| Stale version/docs | Gradle defaults `0.4.0`/400 vs v0.5 tag; `CHANGELOG.md` has no 0.5.0 section; this file previously claimed 0.3.0 | Bump defaults, add v0.5.0 changelog entry at release time |
| Release security posture | `network_security_config.xml` permits cleartext base-config and trusts user certificates in release builds (lint `InsecureBaseConfiguration`, `AcceptsUserCertificates`) | Scope cleartext to user-configured hosts; move user-cert trust into `<debug-overrides>`; TLS pinning remains the release mechanism |
| Dependency drift | 17 outdated `GradleDependency` warnings; Gradle 8.13 deprecations (incompatible with Gradle 9) | Version bump pass (AGP/Gradle/Compose BOM/WorkManager/Glance) |
| Dead SDK guards | `ObsoleteSdkInt` — minSdk 28 makes pre-O checks dead code | Remove |
| Test gaps | `SystemViewModel` (514 LOC), `SessionAdminViewModel`, `ArtifactsViewModel` have zero tests; all three sit on destructive/ops surfaces | Add ViewModel unit coverage |

The route-level backlog is maintained in [docs/API.md](docs/API.md). A future `1.0.0` should require verified interoperability tests against a pinned Hermes release, physical-device smoke coverage for the supported authentication modes, migration tests from every public Talaria database version, and no undocumented remote-capable gaps—not a documentation-only “parity freeze.”
