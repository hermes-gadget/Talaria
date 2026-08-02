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

## i18n implementation summary

- Added `AppLocale` and `LocaleManager` with persisted `SettingsStore` locale tags, Android 13+ per-app locales, and a pre-33 configuration-context fallback. Unknown or malformed stored tags resolve to the system locale.
- Added the You screen language picker: System default, English, 日本語, 中文, 繁體中文, and العربية. Selecting a language persists it and recreates the activity.
- Added translated resource overlays for Japanese, Simplified Chinese, Traditional Chinese, and Arabic, with English defaults in `values/strings.xml`.
- Extracted user-facing strings from Chat, You, Manage home, Activity, shared scaffolding, profile switching, and the unsaved-changes component into `stringResource` calls. Connection resource vocabulary is present for the connection surface, which remained outside this branch's editable screen boundary.
- Added locale persistence, supported-tag resolution, invalid-tag fallback, and pre-Android-13 context-wrapping tests.

### RTL and boundaries

`AndroidManifest.xml` already contained `android:supportsRtl="true"`; it was left unchanged. Prohibited network, navigation, connection, widget, PiP, and excluded Manage feature files were not edited.

### i18n verification

Passed:

```text
JAVA_HOME=/home/ben/java ANDROID_HOME=/home/ben/android-sdk ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon
```

No third-party dependencies were added. No services were restarted, and nothing was pushed.

## ROADMAP #5 — Memory provider configuration, setup, and OAuth

Implemented the memory provider configuration workflow in the Talaria Android app.

- Added feature-local parsing for provider config schemas, typed field kinds/options, and OAuth status responses.
- Added provider config loading with `surface=declared`, draft editing, secret-safe updates, and `{ "values": ... }` request bodies.
- Added provider setup execution and refresh handling.
- Added OAuth capability probing, browser-flow start, 1.5-second status polling, cancellation, and a 120-second timeout.
- Reworked `MemoryScreen` to render generic text, secret, select, boolean, number, and JSON fields from the server schema.
- Added progressive disclosure with `CollapsibleSection` for providers, field groups, OAuth, and built-in memory files.
- Added all new English UI copy under the `memory_` prefix in `strings_memory.xml`.

Verification: the required constrained compile was attempted with `--no-daemon --max-workers=1`. Kotlin reached `:app:kspDebugKotlin`, but the shared VM terminated it with `OutOfMemoryError: GC overhead limit exceeded`; this is noted per the task instructions.

Only the memory feature files, `strings_memory.xml`, and this required summary were changed. No forbidden client, model, navigation, catalog, shared component, manifest, Gradle, locale, or CI files were modified.
