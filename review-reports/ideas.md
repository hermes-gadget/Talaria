# Talaria: ranked feature and experience ideas

Read-only product review, 2026-08-03. Scope reviewed: `app/src/main/java` (chat, Manage, car, voice, widgets, notifications and core/network), `AndroidManifest.xml`, Gradle configuration, and the existing tagged-APK workflow. These are additive ideas, not restatements of features already present.

The strongest existing seams are unusually good for a mobile agent client: a durable PTY prompt path, channel-scoped `/api/events` and RPC sockets, a 30-second multi-profile session poll, event-driven permission/completion notifications, private-LAN cleartext policy, car `ConversationItem` templates, server/on-device voice, static shortcuts, and Glance widgets. The main opportunity is to turn those parts into one coherent ambient agent experience.

## Ideas-ranked

Ranked primarily by user value divided by implementation effort. “Files” are rough ownership areas, not an implementation prescription.

| Rank | Idea | Lane | Value/effort case | Effort |
|---:|---|---|---|:---:|
| 1 | Agent Attention Inbox | Core/mobile | Converts existing ephemeral agent prompts and completions into a reliable, actionable queue. | M |
| 2 | Hands-free Drive Loop | Car | Makes Talaria useful for a whole drive, not just one dictated message. | M |
| 3 | Live Agent Board widget | Core/mobile | Puts “working / needs me / done” on the launcher with one-tap return. | M |
| 4 | Dashboard Event Spine | Ecosystem | One push-first state layer can power notifications, widgets, car and boards while retaining the 30s poll as fallback. | M |
| 5 | Contextual launcher shortcuts | Core/mobile | Small change with daily value: return to the right agent or run a favorite action immediately. | S |
| 6 | Mobile cron recipes | Ecosystem | Repackages capable cron plumbing into outcome-oriented daily/commute experiences. | S–M |
| 7 | Smart LAN roaming profiles | Core/mobile | Makes self-hosted Hermes feel dependable when moving between home LAN, VPN and offline. | M |
| 8 | Deliverable Inbox | Core/mobile | Turns artifacts from hidden transcript references into a first-class “agent delivered this” experience. | M |
| 9 | Road-safe approval queue | Car | Lets agents unblock safely without exposing secrets or dangerous approvals while driving. | M |
| 10 | MissionDeck task-to-agent bridge | Ecosystem | Joins the existing Kanban surface and live session plumbing into a real mobile agent board. | M–L |
| 11 | Offline PTY outbox | Core/mobile | Preserves captured ideas during poor mobile/LAN connectivity and sends them to the intended thread later. | M |
| 12 | Artifact Valet for car | Car | Delivers useful summaries in-car and transfers the actual file to the phone at the right moment. | M |
| 13 | Android “Ask Hermes” action | Core/mobile | Makes any selected/shared text, URL or image an agent task without opening and navigating Talaria first. | M |
| 14 | Personal car quick starts | Car | Replaces three hard-coded prompts with user-owned prompts and safe cron actions. | S |
| 15 | OpenViking Memory Capsule | Ecosystem | Gives voice/share capture durable fleet memory instead of leaving useful context in a chat transcript. | M |
| 16 | Park-and-continue handoff | Car | A cheap, exact handoff from the car conversation to the phone session or artifact. | S |
| 17 | tokensave Pocket Impact Brief | Ecosystem | Makes code-impact questions genuinely usable on a phone without recreating an IDE. | M |
| 18 | Foldable Agent Cockpit | Core/mobile | Uses the large screen for session + conversation + live context rather than only widening the current rail. | L |
| 19 | MCP Tool Cards | Ecosystem | Turns configured MCP tools from server administration into reusable mobile actions. | M |
| 20 | One-tap Obtainium onboarding | Distribution | Builds on the already-correct signing/update path and removes setup ambiguity. | S |
| 21 | Stable / Beta / Canary lanes | Distribution | Enables real-world testing without replacing the trusted daily install. | M |
| 22 | Sideload Trust Center | Distribution | Makes manual APK provenance, version and signing identity understandable before users update. | M |
| 23 | Test-builds channel | Distribution | Gives testers short-lived, side-by-side PR builds with an explicit feedback loop. | M |
| 24 | Reproducible F-Droid flavor | Distribution | Adds a discoverable FOSS channel, at the cost of a separate signing/update lane and packaging work. | L |

### Core/mobile ideas

#### 1. Agent Attention Inbox

- **What:** Add a persistent inbox for permission requests, clarification questions, expired prompts, failures and completions across profiles. Each row retains agent/session identity and offers the safe applicable actions: answer, choose an option, approve once, deny, snooze, open thread, or dismiss. Notifications become views onto this same queue instead of the only copy of an event.
- **Why:** `AgentNotificationPolicy` already recognizes the important sidecar events, but a notification can be cleared, expire, or arrive while another profile is selected. A durable inbox makes agent-initiated work trustworthy and gives phone, foldable, widget and car one shared attention model.
- **Effort:** M.
- **Rough files:** `core/notifications/AgentNotificationPolicy.kt`, `TalariaNotifier.kt`, `NotificationActionReceiver.kt`, `AgentTaskNotificationService.kt`; `core/data/db/*` or `SettingsStore.kt`; `feature/activity/ActivityScreen.kt`; `core/network/HermesEventClient.kt`; new `feature/attention/*` and scoped WorkManager action worker.

#### 3. Live Agent Board widget

- **What:** Replace the status-only Glance card with a resize-aware board showing up to three agents: state dot, title, current tool/short status, age, and “needs input” count. Rows deep-link to the exact connection/profile/session; compact size shows one aggregate line, large size adds “approve/open” or “new task” actions where safe.
- **Why:** The current widget fetches gateway/version/session count every 30 minutes, while `ProfileRegistry` and the foreground watcher already know much richer agent state. Agent state is more valuable at a glance than server version and makes Talaria ambient rather than app-bound.
- **Effort:** M.
- **Rough files:** `widget/TalariaStatusWidget.kt`, widget XML; `core/network/ProfileRegistry.kt`; `AgentTaskNotificationService.kt`; `SettingsStore.kt`; `worker/HermesSyncWorker.kt`; `ui/navigation/Routes.kt`.

#### 5. Contextual launcher shortcuts

- **What:** Keep the static fallbacks but publish dynamic shortcuts for the two most relevant live/pinned sessions, “Talk to Hermes,” and one pinned cron/MCP action. Update them after the 30-second session refresh and task-state transitions; include connection/profile/session in every deep link.
- **Why:** Static New chat/Status/Activity/Manage shortcuts ignore what the user is actually doing. Android limits shortcut slots, so recency plus “needs input” produces a small, high-quality set.
- **Effort:** S.
- **Rough files:** `MainActivity.kt`, `feature/chat/ChatViewModel.kt`, `core/network/ProfileRegistry.kt`, `feature/manage/cron/*`, `ui/navigation/Routes.kt`, `res/xml/shortcuts.xml`; new `core/shortcuts/AgentShortcutPublisher.kt`.

#### 7. Smart LAN roaming profiles

- **What:** Allow a logical Hermes home to have ordered endpoints such as home-LAN HTTP, Tailscale HTTPS, and manual fallback. Probe only previously approved endpoints on network changes, prefer verified private LAN when present, fail over without crossing connection/profile identity, and clearly badge the active transport. Never auto-enable cleartext or probe arbitrary hosts.
- **Why:** The current per-profile private-host cleartext gate is careful, but one saved profile still represents one URL. Mobile users move between Wi-Fi, cellular and VPN; manual URL switching makes a self-hosted agent feel unreliable and can strand background monitors.
- **Effort:** M.
- **Rough files:** `domain/model/ConnectionProfile.kt`; `core/data/prefs/SecureConnectionStore.kt`; `core/network/ConnectionSnapshot.kt`, `CleartextPolicyInterceptor.kt`, `HermesClientFactory.kt`; `feature/connection/*`; `AgentTaskNotificationService.kt`; manifest network permissions if platform requirements demand them.

#### 8. Deliverable Inbox

- **What:** When a completion contains file/artifact references, resolve them through the existing extraction and filesystem preview paths and create a durable delivery card: producer agent, source session, MIME/kind, size, preview, download/share, and “ask for revision.” Notify once with the best next action. Keep delivery state separate from the full transcript.
- **Why:** Talaria can already extract and share artifacts, but users must visit Manage → Artifacts and rescan recent sessions. Agents should feel like they deliver results to the phone, not merely mention paths in chat.
- **Effort:** M.
- **Rough files:** `feature/manage/artifacts/ArtifactExtraction.kt`, `ArtifactsViewModel.kt`, `ArtifactsScreen.kt`; `core/notifications/*`; `core/data/db/*`; `feature/activity/ActivityScreen.kt`; `ui/navigation/Routes.kt`; `feature/chat/ChangedFilesCard.kt`.

#### 11. Offline PTY outbox

- **What:** If a prompt cannot connect, offer “send when Hermes is reachable.” Persist text/attachment metadata against the exact connection, management profile and session; retry under network constraints through the existing short-lived PTY delivery handshake; show queued/sending/failed/sent and permit cancel/edit.
- **Why:** The in-chat queue only handles a busy live turn. A mobile capture surface also needs to survive tunnels dropping, leaving home Wi-Fi, process death and car disconnects without silently targeting a different Hermes home.
- **Effort:** M.
- **Rough files:** `core/network/PtyPromptDelivery.kt`; `worker/ReplyWorker.kt`, `SyncScheduler.kt`; `core/data/db/*`; `feature/chat/ChatViewModel.kt`, `ChatScreen.kt`; `core/network/ConnectionSnapshot.kt`; notifications.

#### 13. Android “Ask Hermes” action

- **What:** Add `ACTION_PROCESS_TEXT` for selected text and improve `ACTION_SEND` into a lightweight task sheet: choose current/new/pinned agent, add an instruction, optionally queue offline, then deliver through PTY. URLs get suggested actions such as summarize, compare, extract tasks; images retain the existing validation/upload path.
- **Why:** Share intake currently routes into the full chat composer. A focused Android-native capture action removes navigation and makes Talaria useful from browser, mail, documents and screenshots.
- **Effort:** M.
- **Rough files:** `AndroidManifest.xml`, `MainActivity.kt`; new `feature/capture/*`; `core/network/PtyPromptDelivery.kt`; `feature/chat/ChatImageAttachments.kt`; `ProfileRegistry.kt`; `SettingsStore.kt`.

#### 18. Foldable Agent Cockpit

- **What:** On expanded/foldable layouts, use three coordinated panes: session/agent board, conversation, and contextual inspector that switches among current tool/subagent tree, changed files, artifact preview and permission details. Respect hinge/posture rather than only the current `screenWidthDp >= 600` check; preserve a two-pane layout when one pane would be cramped.
- **Why:** The current expanded UI adds a persistent session rail, but live agent context still competes with chat in popovers/cards. A cockpit is the clearest phone-to-foldable differentiation and matches agent work better than a stretched transcript.
- **Effort:** L.
- **Rough files:** `feature/chat/ChatScreen.kt`, `SessionRailPane.kt`, `SubagentMonitor.kt`, `ChangedFilesCard.kt`; `feature/manage/artifacts/*`; `ui/navigation/TalariaNavRoot.kt`; Gradle/version catalog if window posture APIs are added.

## Car-specific

Car ideas deliberately stay inside AndroidX Car App messaging/template affordances. While moving, artifacts become text summaries and sensitive or destructive actions remain phone-only.

#### 2. Hands-free Drive Loop

- **What:** Turn a selected conversation into a continuous voice loop: dictate a prompt, show/announce “agent working,” receive the concise final reply through the car conversation, then offer “reply,” “repeat,” “summarize,” or “continue on phone.” Subscribe to that session’s event channel after send instead of forcing a manual list reload. Add a driving-response instruction that asks Hermes for a short spoken answer while preserving the full answer in the phone transcript.
- **Why:** The car app already creates/resumes sessions and accepts framework voice replies, but each interaction ends after the send/reload cycle. Closing the response loop creates the core driving experience without inventing a custom car UI.
- **Effort:** M.
- **Rough files:** `car/CarSessionsRepository.kt`, `SessionListScreen.kt`, `TalariaCarService.kt`; `core/network/HermesEventClient.kt`, `PtyPromptDelivery.kt`; `core/voice/*`; car strings/resources.

#### 9. Road-safe approval queue

- **What:** Surface clarification and low-risk approval prompts as car conversation messages with constrained answers (“approve once,” “deny,” one of the declared choices, or dictate clarification). Sudo, secret entry, broad YOLO changes, and ambiguous actions only offer “remind me on phone.” Persist prompt/request/channel identity so the answer reaches the correct RPC socket.
- **Why:** Agent work often stops on one question. Existing car replies can send ordinary text, while the sidecar already distinguishes approval, clarification, sudo and secret prompts; that is enough to unblock safe cases and explicitly gate unsafe ones.
- **Effort:** M.
- **Rough files:** `car/CarSessionsRepository.kt`, `SessionListScreen.kt`; `core/network/HermesEventClient.kt`; `core/notifications/AgentNotificationPolicy.kt`; the proposed attention store/action worker; `domain` prompt models.

#### 12. Artifact Valet for car

- **What:** When an agent delivers an artifact, add a car-safe message such as “Release notes ready, Markdown, 12 KB” plus actions to hear a short summary or send it to the phone. “Send to phone” posts a scoped local notification opening the exact artifact preview; images/archives never render while moving. A parked user can continue in the phone UI.
- **Why:** Car templates cannot be treated as a general file browser. A summary-and-handoff pattern still fulfills artifact delivery and uses the existing preview/share implementation where it belongs.
- **Effort:** M.
- **Rough files:** `car/SessionListScreen.kt`, `CarSessionsRepository.kt`; `feature/manage/artifacts/*`; `core/notifications/TalariaNotifier.kt`; `ui/navigation/Routes.kt`; manifest deep-link host for artifacts.

#### 14. Personal car quick starts

- **What:** Let users pin prompt templates and explicitly safe cron actions to the car home. Examples: “triage overnight agents,” “draft commute brief,” “run build-health check.” Sync a bounded ordered list from phone settings and replace the three hard-coded `quickStarts` rows. Require confirmation for actions with external side effects.
- **Why:** The current fixed release-note/session-summary/day-plan actions prove the template but will not match every user. Personalization is small and immediately visible.
- **Effort:** S.
- **Rough files:** `car/SessionListScreen.kt`, `CarSessionsRepository.kt`; `SettingsStore.kt`; `feature/manage/cron/*`; a small pin action in chat/cron/MCP screens.

#### 16. Park-and-continue handoff

- **What:** Add a “Continue on phone” conversation/action that posts a notification carrying exact connection, profile and session, plus artifact when relevant. Opening it resumes the existing thread and focuses the composer. This is user-triggered, so it does not depend on unreliable vehicle-arrival detection.
- **Why:** It cleanly ends the constrained car flow and costs little because scoped session deep links and notifications already exist.
- **Effort:** S.
- **Rough files:** `car/SessionListScreen.kt`; `core/notifications/TalariaNotifier.kt`; `ui/navigation/Routes.kt`, `TalariaNavRoot.kt`; `AndroidManifest.xml`.

## Ecosystem-integrations

#### 4. Dashboard Event Spine

- **What:** Introduce one process-level event/state store that consumes a dashboard-wide SSE feed when available and folds in the current channel WebSockets for detailed PTY events. Normalize session created/working/needs-input/completed, tool, artifact and cron events; persist the last known state. Keep the existing 30-second multi-profile poll as reconciliation and compatibility fallback, not the primary UI clock.
- **Why:** Today the global client, per-tab sidecars, foreground monitor and session poll each own part of reality. A shared event spine prevents duplicate sockets/notifications and gives widgets, car, MissionDeck and the Attention Inbox consistent state.
- **Effort:** M, plus dashboard work if a sufficiently global SSE contract does not already exist.
- **Rough files:** `core/network/HermesEventClient.kt`, `ProfileRegistry.kt`; `core/lifecycle/HermesForegroundObserver.kt`; `feature/chat/ChatViewModel.kt`; `AgentTaskNotificationService.kt`; `worker/HermesSyncWorker.kt`; new `core/events/*` and persisted event entities.

#### 6. Mobile cron recipes

- **What:** Add one-tap recipes over existing cron CRUD/blueprints: Morning Agent Brief, Commute Digest, Build Watch, Memory Curator Summary and Weekly Cost Review. Each recipe previews schedule, prompt, delivery target and notification behavior; successful runs deep-link to the created session/deliverable and can be pinned as widget/shortcut/car actions.
- **Why:** The Cron screen is operationally complete but asks users to design prompts, schedules and delivery strings. Recipes sell outcomes and connect cron to mobile surfaces.
- **Effort:** S–M.
- **Rough files:** `feature/manage/cron/CronScreen.kt`, `CronViewModel.kt`; cron models/resources; `core/notifications/TalariaNotifier.kt`; widget/shortcut publisher; `car/SessionListScreen.kt`.

#### 10. MissionDeck task-to-agent bridge

- **What:** Add “Start agent” on a Kanban task. Build the first PTY prompt from task description/comments/attachments, record the resulting session association, then render live worker/session state, last tool, attention badge and delivered artifacts on the card. New task comments can become follow-up prompts; completion proposes, but does not silently force, a status transition.
- **Why:** Talaria already has MissionDeck-style boards, active workers, run details, PTY sessions and event state, but they are separate surfaces. Linking them turns Kanban into an agent control plane rather than another CRUD screen.
- **Effort:** M–L, depending on whether session linkage can be stored in board metadata or needs dashboard support.
- **Rough files:** `feature/manage/kanban/KanbanScreen.kt`; Kanban DTOs in `domain/model/V06Models.kt`; `core/network/HermesApi.kt`; `PtyPromptDelivery.kt`; `ProfileRegistry.kt`; notifications and routes.

#### 15. OpenViking Memory Capsule

- **What:** Offer “Remember with OpenViking” from selected text, shares, voice history and completed chats. Show the proposed compact memory, scope/tags and source session before sending an agent-mediated `openviking_remember` task; add “Recall for this prompt” to inject a short sourced memory pack into the composer. Detect/configure the OpenViking MCP server from the existing MCP catalog rather than storing its credentials in Talaria.
- **Why:** Mobile is the best capture point for fleeting context, and the fleet-wide memory store is more durable than a transcript. Review-before-write prevents accidental long-term storage of secrets or low-signal chatter.
- **Effort:** M.
- **Rough files:** `feature/manage/mcp/McpScreen.kt`; new `feature/memorycapsule/*`; `feature/chat/ChatScreen.kt`, `ChatViewModel.kt`; `feature/voice/VoiceScreen.kt`; capture/share flow; `PtyPromptDelivery.kt`.

#### 17. tokensave Pocket Impact Brief

- **What:** Add a compact code-intelligence action: choose or dictate a symbol/path/question, have Hermes call tokensave context/callers/callees/impact, and render a phone-native brief with definition, upstream callers, downstream effects, likely tests and “open file / start review agent.” Cache the report as an artifact. Start agent-mediated because Hermes exposes MCP server metadata/PTY prompting but Talaria has no generic direct tool-invocation API.
- **Why:** Raw code browsing on a phone is poor; a bounded semantic impact brief is exactly the level at which mobile review is useful. It also gives the existing Files, Git Review and agent features a shared entry point.
- **Effort:** M.
- **Rough files:** new `feature/manage/tokensave/*`; `feature/manage/mcp/McpScreen.kt`; `feature/manage/review/*`; `feature/manage/files/*`; `feature/manage/artifacts/*`; `PtyPromptDelivery.kt`; Manage catalog/routes.

#### 19. MCP Tool Cards

- **What:** Let users pin allowlisted MCP tools as parameterized cards. A card shows server health, a small schema-derived form, expected side-effect level, and “run in new/current agent.” Store no MCP secrets locally. Initially translate the filled card into a structured agent prompt; switch to direct invocation only if Hermes later exposes an authenticated generic tool-call endpoint.
- **Why:** The MCP screen currently excels at install/edit/enable/test/OAuth administration. Tool Cards expose the actual value of those servers while preserving Hermes as the policy and credential boundary.
- **Effort:** M.
- **Rough files:** `feature/manage/mcp/McpScreen.kt`; MCP models; new `feature/toolcards/*`; `SettingsStore.kt`; `PtyPromptDelivery.kt`; widgets/shortcuts/car pins; Manage catalog/routes.

## Distribution

The current foundation is sound: tag builds use the stable release application ID, a persistent CI signing key, monotonic version codes, and a GitHub pre-release APK compatible with Obtainium. The next work should clarify lanes rather than replace that path.

#### 20. One-tap Obtainium onboarding

- **What:** Publish a prominent install page/QR that opens the repository in Obtainium with the intended APK regex and prerelease policy, shows the release signing SHA-256 fingerprint, and explains the one-time pre-v0.4 package migration. Add a tiny machine-readable release descriptor/checksum asset beside every APK.
- **Why:** The hard signing/version work is already done; discovery and trust are now the friction. Checksums and the expected certificate also make sideload verification less hand-wavy.
- **Effort:** S.
- **Rough files:** `.github/workflows/apk-release.yml`, release notes/template, `README.md`, `SETUP.md`; new `docs/install.md` and checksum/release-descriptor generation.

#### 21. Stable / Beta / Canary lanes

- **What:** Make stable releases non-prerelease and infrequent; publish beta from version tags and canary from selected main builds. Use side-by-side package IDs (`com.hermesgadget.talaria.beta` / `.canary`), distinct app labels/icons, independent monotonic version codes and explicit signing keys. Provide encrypted profile export/import that excludes or separately protects secrets.
- **Why:** All current tagged APKs are GitHub prereleases. Parallel lanes let car/voice/network changes get real-device coverage without displacing the daily stable install or confusing Obtainium.
- **Effort:** M.
- **Rough files:** `app/build.gradle.kts`, manifest placeholders/resources, launcher icons, `BuildConfig`; `.github/workflows/*`; `SettingsStore.kt`/connection export UI; docs.

#### 22. Sideload Trust Center

- **What:** Add an About/Updates screen showing installed version/code, build lane, Hermes API baseline, application ID, signing-certificate fingerprint, source repository, and last checked release. Let users compare the downloaded APK checksum/certificate before launching the system installer; never request silent-install privileges.
- **Why:** Sideloaded users need to know whether an APK is the stable Hermes Gadget build, a beta, or a local debug build. This is particularly important because older Talaria packages used a different ID/certificate.
- **Effort:** M.
- **Rough files:** new `feature/settings/AboutUpdatesScreen.kt`; `app/build.gradle.kts` BuildConfig fields; `PackageManager` helper; `HermesApi` is not involved; Manage/You catalog, routes, strings; release checksum asset/workflow.

#### 23. Test-builds channel

- **What:** Build installable PR/manual QA APKs from an explicit workflow, retain them briefly, publish SHA-256 plus change summary and a QR/download page, and collect a structured feedback bundle (app/build/device/API-baseline plus redacted logs). Keep a `.test` application ID and test signing identity so builds install beside stable and never enter the Obtainium stable feed.
- **Why:** The current workflow builds deployable APKs only for version tags. Car hosts, foldables, speech engines and OEM background limits need hands-on testers earlier than release day.
- **Effort:** M.
- **Rough files:** new `.github/workflows/test-build.yml`; `app/build.gradle.kts` product flavor/build type; About/diagnostics screen; redaction helpers in logging; `docs/testing.md`.

#### 24. Reproducible F-Droid flavor

- **What:** Add an `oss` flavor and F-Droid metadata, pin a reproducible Gradle/JDK/SDK recipe, generate changelogs/icons/screenshots, and audit every dependency/network feature for the repository’s inclusion policy. Disable any future proprietary updater/telemetry integrations in this flavor. Clearly state that F-Droid signing makes it a separate update lane from the CI-signed Obtainium APK unless reproducible-build/signing arrangements are accepted.
- **Why:** Talaria is Apache-licensed and currently uses an AndroidX/Kotlin network stack without an obvious proprietary analytics SDK, so it is a credible candidate. The work is mostly packaging, reproducibility and ongoing release discipline rather than product code.
- **Effort:** L.
- **Rough files:** `app/build.gradle.kts`, `gradle/libs.versions.toml`; new `fastlane/metadata/android/*` and F-Droid recipe metadata; privacy/network docs; release workflow reproducibility checks; flavor-specific resources/config.

## Suggested sequence

1. Build the **Attention Inbox** data/action model first.
2. Feed it through the **Dashboard Event Spine**, retaining 30-second reconciliation.
3. Ship the **Live Agent Board widget**, **contextual shortcuts**, and **mobile cron recipes** as visible wins on that foundation.
4. Extend the same attention/session state into the **Hands-free Drive Loop** and **Artifact Valet**.
5. Layer MissionDeck, OpenViking, tokensave and generic MCP experiences on the proven event/action model.
6. Split release lanes before broad car/foldable testing, then pursue F-Droid once builds are reproducible.
