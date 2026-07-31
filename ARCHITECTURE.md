# Architecture

Talaria is a single-module Android app (`:app`) organized by clean-architecture packages: **ui → feature ViewModels → repositories → network / Room / secure prefs**.

## Goals

1. Remote client only — never embeds the Python Hermes runtime.
2. Treat the official Web Dashboard REST `/api/*` + WebSockets as the contract.
3. Privacy-first defaults: no telemetry, Keystore secrets, network only to user endpoints.
4. Battery-aware background work via WorkManager; optional FGS when the user opts into continuous sync/dictation.

## Layering

```
┌─────────────────────────────────────────────┐
│  UI (Compose + NavigationSuite)             │
│  Chats · Activity · Manage · You            │
├─────────────────────────────────────────────┤
│  Feature ViewModels (StateFlow)             │
├─────────────────────────────────────────────┤
│  Repositories                               │
│  ConnectionRepository · HermesRepository    │
│  ChatRepository                             │
├─────────────────────────────────────────────┤
│  Core                                       │
│  Retrofit/OkHttp · PTY WebSocket · Room     │
│  EncryptedSharedPreferences · WorkManager   │
│  SpeechRecognizer / TTS · Notifications     │
└─────────────────────────────────────────────┘
            │ HTTPS/WSS (user Hermes URL)
            ▼
     Hermes Agent dashboard (:9119)
```

Manual DI lives in `di/AppContainer` (constructed from `TalariaApp`). This keeps the graph obvious and unit-testable without a DI framework.

## Auth model

Mapped from Hermes dashboard behavior (v0.17+):

| Mode | Client behavior |
|------|-----------------|
| Loopback / token | `X-Hermes-Session-Token` on REST; `?token=` on WS |
| Gated basic | Password login + cookie jar; WS via `POST /api/auth/ws-ticket` → `?ticket=` (extension point; PTY currently also accepts token when available) |
| Bearer | `Authorization: Bearer …` |
| OIDC browser | Custom Tabs / deep link to `/auth/login` (operator completes login; cookies stored) |

`ProfileQueryInterceptor` appends `?profile=` for management-scoped families, matching `web/src/lib/api.ts` `PROFILE_SCOPED_PREFIXES`.

Optional **certificate pinning** per connection profile via OkHttp `CertificatePinner`.

## Chat transport

Dashboard Chat is a real `hermes --tui` behind `/api/pty` (plus `/api/ws` JSON-RPC sidecar for structured events).

Talaria’s v0.1 chat path:

1. Open OkHttp WebSocket to `/api/pty` with auth query + optional `resume=` / `profile=`.
2. Strip ANSI (`AnsiStripper`) into a Compose transcript.
3. Send user lines as PTY input (newline-terminated).
4. On disconnect / background, surface notifications for completed assistant buffers.

**Gap:** Full Ink/xterm fidelity, slash-command palettes, and `/api/ws` tool sidebars are not pixel-parity yet. Extension points exist in `PtyWebSocketSession` and `ChatRepository`. Session browsing uses REST `/api/sessions*` for offline-friendly history.

## Notifications & background

- Channels: replies, cron, gateway, pairing, errors, tasks, sync.
- Actions: Open (deep link), Dismiss, Reply (`RemoteInput` → `ReplyWorker` short PTY send).
- `HermesSyncWorker` (Periodic WorkManager, network-constrained) polls status/pairing/cron.
- Optional `HermesSyncForegroundService` for users who disable battery optimizations.
- BootReceiver re-schedules work after reboot.
- All toggles live under **You**; Doze-aware by default (15+ minute periods).

## Voice

- Prefer on-device `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE` unless cloud STT opt-in.
- Continuous dictation with partial results; optional `VoiceDictationService` FGS (microphone type).
- TTS via Android `TextToSpeech` when enabled.

Optional Whisper.cpp / Vosk bindings are **not** bundled (size/licensing); documented as future optional modules.

## Persistence

| Store | Contents |
|-------|----------|
| EncryptedSharedPreferences | Connection profiles metadata + secrets |
| SharedPreferences | Non-secret settings (notifications, TTS, …) |
| Room | Cached sessions/messages, activity feed, chat drafts |

Cloud backup / device transfer excludes secure prefs and DBs (`backup_rules` / `data_extraction_rules`).

## UI

- Material 3 + optional dynamic color; default Hermes dark aesthetic (void/ink/ember/wing).
- `NavigationSuiteScaffold`: Chats / Activity / Manage / You.
- Manage is a hub into dashboard feature screens adapted for one-handed lists/forms.

## Version mapping

`BuildConfig.HERMES_API_BASELINE` documents the dashboard API generation (`dashboard-v0.17+`). See [docs/API.md](docs/API.md) for endpoint coverage and known gaps.
