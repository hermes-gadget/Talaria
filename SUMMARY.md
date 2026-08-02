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

## Server voice

Implemented on branch `feature/server-voice`.

### Live capability probe

Hermes v0.19.1 at `127.0.0.1:9119` exposed these voice paths in `GET /openapi.json`:

- `POST /api/audio/transcribe`
- `POST /api/audio/speak`
- `GET /api/audio/elevenlabs/voices`

The requested `GET /api/voice` and `GET /api/audio` probes returned `404`; both were read-only probes.

### Delivered

- Added typed server voice request/response models in `VoiceModels.kt`.
- Added only the verified OpenAPI/STT/TTS methods to the end of `HermesApi.kt`, with profile query support.
- Added the standalone `feature/voice` screen and view model: local recording to an audio data URL, Hermes transcription, copyable/editable text, Hermes TTS playback through `MediaPlayer`, and a six-item in-memory history.
- Added runtime OpenAPI capability refresh and an explicit unavailable state listing missing routes. When server voice is unavailable, the screen reports Android `SpeechRecognizer` availability and offers the existing on-device dictation coordinator as a local fallback.
- Added pure voice lifecycle reducer tests covering idle → recording → transcribing → playing and unavailable → available recovery.

Chat dictation hooks and navigation were intentionally left untouched per the ownership constraints; the voice surface is standalone for this wave.

### Verification

- `JAVA_HOME=/home/ben/java ANDROID_HOME=/home/ben/android-sdk ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon`
- Result: passed (`BUILD SUCCESSFUL`, 30 actionable tasks).
