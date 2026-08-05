# Changelog

All notable Talaria changes are documented here. Versions below correspond to repository tags; there has never been a `1.0.0` release.

## [0.9.0] — 2026-08-05 (unreleased)

### Added

- Connect screen: one-tap "Fetch token from dashboard" — reads the SPA-shell loopback token instead of requiring a manual paste (kills the stale/malformed-token bug class).
- Connection doctor now probes the token-gated REST surface (`/api/sessions`), so a stored-token mismatch is reported as "REST 401 · WS OK" instead of a confusing Live-but-stale chat.
- Explicit "Refresh now" action on the "Live updates delayed; reconciling…" status row.
- Multi-item share intake (R1.8): `ACTION_SEND_MULTIPLE` and `ACTION_PROCESS_TEXT` capture surface with owned, byte-bounded staging, target selection, and URL suggestion chips.
- Local session organization (R1.9): labels/groups/favorites/saved filters, Room-backed, profile-scoped, with a clear Local badge; bulk actions respect filtered selections.
- Widget/car strings moved into resources across all five locales (N1.11 slice).
- Draft persistence debounced to 350 ms; microphone recording stops when the app leaves the foreground (N1.14 slices).

### Fixed

- REST auth now converges with WS auth: the SPA-discovered loopback token is persisted to the store, so chats update again after a dashboard restart or stale stored token.
- Launch crash loop from untrimmed pasted tokens (sanitized at input, persistence, load, and transport boundaries).

## [0.8.5] — 2026-08-05

### Fixed

- Launch crash loop caused by untrimmed pasted session/bearer tokens in HTTP headers (OkHttp rejects control characters); sanitized at input, persistence, heal-on-load, and every header site.
- REST/WS auth divergence: the SPA-discovered loopback token is now synced into the store so REST calls stop 401ing after a dashboard restart or stale stored token.
- In-chat HTTP consent re-approval after the fail-closed cleartext migration.

### Changed

- AGP 9.1.1 / Gradle 9.3.1 / compileSdk 37 migration (built-in Kotlin 2.3.0).
- Lint debt cleared (702 findings → 21 documented toolchain pins).

## [0.8.4] — 2026-08-05

### Fixed

- Android Auto message center showing "No new messages during this drive" despite live chats (CarConversationNotifier per-conversation notifications).

## [0.8.3] — 2026-08-04

### Added

- Android Auto (phone projection) discovery: `com.google.android.gms.car.application` application metadata plus `res/xml/automotive_app_desc.xml` declaring `notification` + `template` capabilities, so the app is discoverable in the vehicle launcher when the phone is connected to a real head unit (AAOS discovers via the `CarAppService` intent filter; the projection host additionally requires this descriptor).

## [0.8.2] — 2026-08-03

### Changed

- Car host compatibility: release builds now accept any car host (`ALLOW_ALL_HOSTS_VALIDATOR`). The AndroidX sample allowlist rejected OEM-signed Android Auto hosts, which made the car app invisible on real vehicles with sideloaded distribution. Accepted-risk tradeoff for a sideload-only APK; replacement host-enrollment policy tracked in ROADMAP P0.2.

## [0.8.1] — 2026-08-03

### Fixed

- Restore cleartext to LAN Hermes hosts (v0.8 regression): `network_security_config.xml` permits cleartext again, with the app-layer `CleartextPolicy` as the real gate (private/link-local destinations + persisted per-profile consent; system-only CAs in release).
- Bound stringified-JSON unwrap in artifact extraction — a flaky `StackOverflowError` caused by re-parsing plain path strings (`report.txt`) as JSON literals and recursing on them.

## [0.8.0] — 2026-08-03

### Added

- Car: driving-safe agent creation, quick-start rows, AAOS launcher opens `CarAppActivity` (with `force_phone_ui` emulator test hook), `minCarApiLevel` on `<application>`, end-to-end PTY prompt delivery (`PtyPromptDelivery` ack protocol, `PtySendReceipt`).
- Voice: server STT as primary dictation with on-device fallback, periodic session auto-open sync.
- Thread-aware agent notifications for permission/clarification/secret requests and foreground/background task completion; dedicated notification channels and Android 13+ runtime permission onboarding.
- Full code audit (`audit.md`, 65 findings: 13 high / 42 medium / 10 low) and remediation wave: multi-profile connection safety (immutable `ConnectionSnapshot`, client eviction, same-origin guards), PTY delivery and car transport hardening, voice lifecycle/data bounds, artifact and managed-file transfer lifecycles, manage-screen hardening (system/config editors), PiP and navigation deep-link handoffs, chat session ownership and dictation state, complete localization catalog, transport tests.
- Version defaults bumped to 0.8.0 (versionCode 800).

## [0.7.0] — 2026-08-02

### Added

- Auto-open active sessions across platforms with `end_reason` tracking; filter tool/system messages from the chat transcript; bubble styling polish.

## [0.6.0] — 2026-08-02

### Added

- Managed file transfers over `/api/files`: upload (with progress), download via SAF, mkdir, delete, and image previews through `/api/media` — replacing the text-only browse surface.
- Dashboard and agent plugin management: installed/hub listing, rescan, visibility toggle, install/enable/disable/update/delete, and runtime-provider configuration.
- Kanban board view (MissionDeck boards): board switcher, create board, columns by status, task create/detail/comments/log, stats, assignees, active workers, and run detail/terminate.
- Guided Telegram and WhatsApp pairing onboarding (start/poll/apply/cancel).
- MCP server editing alongside add/remove/enable/test.
- Memory provider configuration, setup, and OAuth flows per provider.
- Computer-use readiness + permission grant on the Status screen; terminal backend picker.
- Toolset deep configuration: env, model, provider, model list, and post-setup actions.
- Model MoA (mixture-of-agents) editing, auxiliary-model assignments, and recommended-default lookup.
- Hermes update apply + gateway drain actions, and Ops depth: checkpoints list/prune, config-migrate, ops dump, prompt-size.
- Minor surfaces: per-model analytics, ElevenLabs voices, cron fire-now, profile sessions/model/open-terminal/setup-command, egress status.
- Chat UX backlog: composer @-mentions/URL/path chips/emoji completions, find-in-session, message edit + branch-in-new-chat, and syntax-highlighted markdown code blocks.
- Session pinning (local persistence) and compaction UI.
- Progressive disclosure rollout across Status, Config, Command Center, Cron, Skills, MCP, Files, Logs, Analytics, and Sessions via the shared `CollapsibleSection` component; Manage hub regrouped with an Extensions category.
- Unit tests for SystemViewModel, SessionAdminViewModel, ArtifactsViewModel, session pins/filters, and markdown rendering.

### Security

- Release builds now trust system CAs only; user-installed CA certificates are debug-only. Removed redundant cleartext flag (network security config is authoritative) and dead pre-Android-O guards.

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
