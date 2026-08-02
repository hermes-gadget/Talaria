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

## Ops system implementation

Branch: `feature/ops-system`

### Implemented

- Added additive Hermes v0.19.1 Retrofit declarations for ops import/import-upload, backup creation and streamed download, hooks CRUD, debug-share, and raw YAML config.
- Added serializable ops request/response models in `OpsModels.kt`.
- Added `SystemViewModel` with sealed operation states, preserving the existing host, gateway, doctor, security-audit, backup, update, and portal actions.
- Enhanced `SystemScreen` with SAF import selection and destructive confirmation, FileProvider backup download/share, hook create/delete confirmation, debug-share capture/share, and a raw YAML editor.
- Added unit coverage for hook payload parsing and import-file validation.

### Live API checks

The dashboard at `127.0.0.1:9119` reported Hermes `0.19.1`. Authenticated probes confirmed:

- `GET /api/ops/hooks` returns `hooks` and `valid_events`.
- `POST /api/ops/backup` returns an archive path; `GET /api/ops/backup/download?archive=...` streams `application/zip`.
- `POST /api/ops/import` accepts an archive path and `POST /api/ops/import-upload` accepts multipart `file` + `force`.
- `POST/DELETE /api/ops/hooks`, `POST /api/ops/debug-share`, and `GET/PUT /api/config/raw` are available.

The live import-upload endpoint describes and enforces a backup ZIP. The client validates both JSON and ZIP selections as requested, then surfaces any server-side rejection for a JSON file instead of silently treating it as a successful restore.

### Ops verification

Command run with `JAVA_HOME=/home/ben/java` and `ANDROID_HOME=/home/ben/android-sdk`:

```text
./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon
```

Kotlin compilation passed and the owned `OpsValidationTest` passed. The full suite reported 123 tests with one unrelated existing failure: `ArtifactExtractionTest > extracts nested tool payload paths and classifies archives` throws `StackOverflowError` in unowned `feature/manage/artifacts/ArtifactExtraction.kt` due recursive lenient JSON primitive parsing. No artifacts files or tests were modified.
