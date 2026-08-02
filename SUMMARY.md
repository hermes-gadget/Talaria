# Command Center implementation summary

## What changed

- Added a Manage > Command Center destination with Gateway, Logs, Usage, and Maintenance sections.
- Gateway combines `/api/status` and `/api/system/stats`; logs tail `agent`, `gateway`, and `errors`; usage normalizes current and legacy analytics shapes.
- Added pull-to-refresh, loading/error states, per-section graceful degradation, level-colored compact log rows, and System links for doctor, backup, and restart.
- Added log-line and usage-summary parser unit tests. Existing HermesApi/repository endpoints already covered the live v0.19.1 contract, so no API additions were necessary.

## Files

- `app/src/main/java/com/hermesgadget/talaria/feature/manage/commandcenter/CommandCenterModels.kt`
- `app/src/main/java/com/hermesgadget/talaria/feature/manage/commandcenter/CommandCenterViewModel.kt`
- `app/src/main/java/com/hermesgadget/talaria/feature/manage/commandcenter/CommandCenterScreen.kt`
- `app/src/test/java/com/hermesgadget/talaria/feature/manage/commandcenter/CommandCenterModelsTest.kt`
- `app/src/main/java/com/hermesgadget/talaria/feature/manage/ManageHomeScreen.kt`
- `app/src/main/java/com/hermesgadget/talaria/ui/navigation/Routes.kt`
- `app/src/main/java/com/hermesgadget/talaria/ui/navigation/TalariaNavRoot.kt`

## Live endpoint checks

- `GET /api/status` — 200
- `GET /api/system/stats` — 200
- `GET /api/logs?file=agent|gateway|errors` — 200
- `GET /api/analytics/usage?days=7` — 200
- `/api/system` and `/api/usage` are not present; the Command Center uses the verified replacements. Maintenance routes are present in OpenAPI and remain behind the existing System screen to avoid accidental side effects during verification.

## Verification

- Passed: `JAVA_HOME=/home/ben/java ANDROID_HOME=/home/ben/android-sdk ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon` (`BUILD SUCCESSFUL`, 30 actionable tasks).

---

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
