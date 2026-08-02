# Composer UX implementation summary

## What changed

- Added a per-tab composer queue. Text submitted during an active turn is retained, counted in the composer row, and drained FIFO after the authoritative `message.complete` event (with the reading-mode transcript fallback).
- Added per-session input history with ↑/↓ navigation, draft restoration, a 50-entry cap, and SharedPreferences/JSON persistence following the existing settings pattern.
- Added a chat-header steer/trigger popover for model selection, reasoning effort, approval mode, and YOLO. The model action reuses the existing model picker; session-only controls explain and remain disabled until Hermes assigns a session.
- Added pure queue/history unit coverage.

## Files

- `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatViewModel.kt`
- `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatScreen.kt`
- `app/src/main/java/com/hermesgadget/talaria/feature/chat/ComposerInputState.kt`
- `app/src/test/java/com/hermesgadget/talaria/feature/chat/ComposerInputStateTest.kt`

## Dashboard paths investigated

- `GET /api/status`, `/api/model/info`, `/api/model/options`, `/api/config`, and `/api/config/schema`.
- WebSocket `ws://127.0.0.1:9119/api/ws?token=local&profile=default`, including `commands.catalog` and `config.set`.
- The wired RPC keys are `reasoning` and `yolo` per session, and `approval_mode` globally; model selection continues to use the existing `model` config path.
- `session.info` state is consumed from sidecar events; direct `session.info` and `model.info` request RPCs are not exposed by the live v0.19.1 gateway.

## Verification

- Command: `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon`
- Result: passed (`BUILD SUCCESSFUL`, 30 actionable tasks).

## Leftovers

- Approval mode is global in the current Hermes server, so the popover labels it accordingly. No requested server-side control was found to be unsupported; reasoning and YOLO are disabled only while a tab has no active/resumable session.

## Widget + PiP chat

Branch: `feature/widget-pip`

- Added a 4-column Glance quick-entry widget with `New chat` and `Talk` deep-link buttons. The chat link carries a composer-focus hint; `Talk` routes to the existing connection screen.
- Added `PipChatActivity`, an explicit read-only snapshot contract, PiP lifecycle handling, and a header-only PiP action in `ChatScreen`.
- Added unit coverage for widget deep-link intents, PiP state transitions, and snapshot intent round-tripping.
- Preserved the existing Glance status widget, edge-to-edge setup, `adjustResize`, composer, and steer popover behavior.

Verification: `JAVA_HOME=/home/ben/java ANDROID_HOME=/home/ben/android-sdk ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon` — passed.

## Multi-profile streaming

Branch: `feature/multi-profile`

### Implemented

- Added `MultiProfileModels.kt` with profile-tagged sessions, numeric/ISO recency ordering, All/profile filtering, and per-profile stream lifecycle state.
- Added the singleton `ProfileRegistry`. It refreshes `/api/profiles` and an explicit `/api/sessions?profile=...` request for every profile, preserves local channel transitions, and exposes active-session/streaming state through a `StateFlow`.
- Added additive `HermesApi` methods in the marked Multi-profile section at the end of the interface.
- Extended `ChatViewModel` with `mergedSessions`, profile filter options/selection, loading state, and profile streaming state. Existing screen-facing `sessions` remains the legacy active-profile projection until a profile-aware rail is wired.
- Chat tabs now retain their originating Hermes management profile. Background transcript polling uses explicit profile-scoped REST calls, and profile-scoped drafts/tabs remain isolated.
- Management-profile changes no longer call the ChatViewModel's full connection teardown. Existing PTY and per-tab sidecar clients remain alive; `HermesEventClient` snapshots the originating profile so reconnects do not move a background channel to the foreground profile.

### Live API contract

Using `X-Hermes-Session-Token: local` against the local Hermes v0.19.1 dashboard:

- `GET /api/profiles` returned `{ "profiles": [...] }`; the live instance currently advertises `default`, model `deepseek-v4-flash`, provider `opencode-go`, and `gateway_running: true`.
- `GET /api/sessions?profile=default` returned `{ "sessions": [...], "total": ..., "limit": 20, "offset": 0 }`. Session rows include `profile`, `is_default_profile`, numeric `started_at`/`last_active`, and `is_active`; `live` may be `null`.

### Follow-up UI work

No `@Composable` or string files were changed. The existing Chat rail still reads `ui.sessions`; the new `ui.mergedSessions` projection is profile-tagged and sorted, with `ui.sessionProfileOptions` plus nullable `ui.selectedSessionProfile` for an All/profile chip row. A follow-up UI pass should render that projection with `MultiProfileSession.key` and route resume/title actions with the selected session's profile.

The known teardown outside the owned screen surface is `ProfileSwitcherBar` stopping the singleton foreground observer client. Chat tab clients are separate instances and are no longer stopped by profile switching. If a background PTY has already failed while another profile is foreground, reconnect is deferred until that profile is foreground because the existing `PtyWebSocketSession` takes its connection profile from the active store; the active, ongoing channel is preserved.

### Verification

Passed the requested single verification:

`JAVA_HOME=/home/ben/java ANDROID_HOME=/home/ben/android-sdk ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon`

Gradle reported `BUILD SUCCESSFUL`.
