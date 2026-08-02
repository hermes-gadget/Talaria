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

## Chat rewind implementation

Branch: `feature/chat-rewind`

### What changed

- Added long-press rewind confirmation to the reading transcript. The confirmed
  action calls Hermes websocket RPC `session.branch`, opens the returned durable
  branch session in a new chat tab, and retains optional parent lineage in the
  session rail.
- Added header overflow actions for confirmed `session.compress` compaction and
  REST-backed session title editing through the existing `PATCH /api/sessions/{id}`
  repository method.
- Preserved queue/history/steer behavior and the existing IME padding/navigation
  behavior. Added ViewModel-owned session action state/reducer tests.

### API verification

The live dashboard at `127.0.0.1:9119` reports Hermes `v0.19.1`. Its OpenAPI
surface exposes session title PATCH but not REST `/branch` or `/compact` routes;
the connected gateway RPC methods are `session.branch` and `session.compress`.
The sessions response exposes `parent_session_id`, which is projected without
changing the shared `SessionSummary` model.

### Verification

Passed on 2026-08-02:

```text
JAVA_HOME=/home/ben/java ANDROID_HOME=/home/ben/android-sdk ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon
```

The build reported only existing warnings in unrelated artifact/learning/markdown
code and the existing cron test opt-in warnings.
