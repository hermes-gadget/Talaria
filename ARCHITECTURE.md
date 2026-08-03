# Architecture

Talaria is a single-module native Android application. It is a remote client: the Python Hermes runtime, shell, tools, and model credentials remain on the user’s Hermes host.

The verified protocol baseline is Hermes Agent `v0.19.1` at upstream commit `470cf66b039c73bdd2c21d43094ce41a4db74eae` (2026-08-01).

## Layers

```text
Compose UI and navigation
        ↓
Feature ViewModels (StateFlow)
        ↓
HermesRepository / ChatRepository / ConnectionRepository
        ↓
Retrofit + OkHttp WebSockets | Room | encrypted preferences | WorkManager
        ↓
User-configured Hermes dashboard over HTTP(S) and WS(S)
```

Manual dependency injection lives in `di/AppContainer`, created by `TalariaApp`. `HermesClientFactory` rebuilds the HTTP client when the active server, credentials, management profile, or TLS pin changes.

## Connection and data boundaries

A saved connection and its selected Hermes management profile form one local scope. Room caches, drafts, restored chat tabs, sync fingerprints, widget summaries, REST caches, PTY sessions, sidecar sockets, and notification actions use that scope. Switching either part tears down live transports and recreates the navigation/ViewModel graph, preventing data from one Hermes home appearing in another.

The local blank profile name represents Hermes’ `default` profile. Profile-scoped REST and WebSocket calls still send `profile=default` explicitly, so Talaria does not inherit the dashboard process’s sticky CLI profile by accident.

## Authentication

| Mode | Behavior |
|---|---|
| None / loopback | Discovers the injected dashboard session token before opening protected WebSockets |
| Session token | `X-Hermes-Session-Token` on REST and `token=` where the dashboard protocol requires it |
| Password | Discovers password providers, posts JSON to `/auth/password-login`, and persists the scoped session cookie |
| Bearer | Sends `Authorization: Bearer …` |
| Native OIDC | RFC 8252 loopback callback, PKCE S256, state validation, encrypted refresh token, proactive synchronized refresh |

When Hermes reports `auth_required`, Talaria mints short-lived tickets through `POST /api/auth/ws-ticket` for `/api/pty`, `/api/ws`, and `/api/events`. Cookies retain their name/domain/path identity and standard expiry, Secure, and path matching semantics. Connection URLs reject embedded credentials and query/fragment ambiguity. Optional SHA-256 certificate pins are accepted only for HTTPS.

## Chat transport

Hermes exposes the TUI through `/api/pty`. Talaria opens one PTY per chat tab and adds two structured sidecars:

- `/api/ws` for JSON-RPC state, command completion, image attachment, model state, and approval/clarification/sudo/secret responses.
- `/api/events` for lifecycle, tool progress, prompt, usage, notification, and completion events.

Reading mode combines structured live state with REST session messages; Terminal mode shows the ANSI-stripped PTY stream and supports interrupt/copy. Chat tabs and drafts survive process death. Image attachments are signature-validated, capped at 25 MB, staged through `image.attach_bytes`, and associated with the exact live PTY session before the prompt is sent.

Slash completion starts with a bundled compatibility catalog, then replaces it with live `commands.catalog` data. The palette ranks exact, prefix, alias, fuzzy-name, and description matches while `complete.slash` supplies argument completions. Debouncing and request generation checks prevent stale results replacing newer input.

## Background work and notifications

`HermesSyncWorker` performs network-constrained periodic polling. There is no long-running general sync or microphone foreground service. For a user-started agent turn, `AgentTaskNotificationService` temporarily runs as a data-sync foreground service and subscribes to that tab's `/api/events` channel. This keeps permission and completion detection alive while Talaria is backgrounded; the service stops at the authoritative `message.complete` boundary and restores its small non-secret watch list if Android recreates it.

Both the visible chat and the foreground monitor feed the same `AgentNotificationPolicy` / `AgentAlertDispatcher`. Persistent 30-second fingerprints suppress duplicate frames without hiding later turns. Permission alerts use a dedicated high-importance channel and are cleared on expiry, response, or completion; completed and failed tasks use a separate channel. Titles always include the persisted chat-tab name selected by the user, and taps deep-link to the exact connection, management profile, and Hermes session.

Android 13+ notification permission is requested when the user first opens a connected chat. The You screen provides separate switches for permission requests and task completion, and disabling the master switch stops active monitors.

Reply and pairing notification actions carry the expected connection/profile scope. Workers refuse an action if the user has since switched scope. Deep links use strict route parsing and cannot select an unknown saved connection.

## Persistence and privacy

| Store | Contents |
|---|---|
| EncryptedSharedPreferences | Connection metadata, passwords, session/bearer tokens, OIDC refresh state |
| DataStore / SharedPreferences | Non-secret UI, notification, sync, and widget preferences |
| Room | Scoped session/message caches, activity history, and chat drafts |

Android backup and device transfer exclude encrypted connection state and Room databases. Talaria includes no analytics or crash-upload SDK.

## Car surface

`car/` implements a Car App Library service (`TalariaCarService`) shared by Android Auto projection and AAOS: an active-agent list, voice replies, one-tap quick starts, and driving-safe agent creation, backed by `CarSessionsRepository`. Discovery: AAOS binds the `CarAppService` intent filter; Android Auto additionally requires the `com.google.android.gms.car.application` metadata + `automotive_app_desc.xml` capabilities (v0.8.3). The release host validator is `ALLOW_ALL` for sideload compatibility (accepted risk — see ROADMAP P0.2).

## Voice

`feature/voice/` implements server STT as primary dictation (`/api/voice/transcribe`) with on-device `SpeechRecognizer` fallback, continuous dictation with partials, and Android TTS of replies. Server capabilities are probed with generation guards; recording is bounded and lifecycle-scoped.

## Widgets, tile, and PiP

Two Glance widgets (status + quick entry), a Quick Settings tile, and picture-in-picture chat all read from the same profile-scoped state as the app. Widget summaries are refreshed via `HermesSyncWorker`; taps deep-link to exact connection/profile/session.

## UI adaptation boundary

Talaria implements remote agent/chat and dashboard management workflows in touch-native Compose screens. Desktop-only host integrations—local Electron window management, desktop quick-entry/pet overlays, native terminal multiplexing, and local drag/drop shell integration—are not meaningful Android parity targets. The mobile equivalents are Android shares, notifications, widgets, app shortcuts, the Quick Settings tile, photo picker, SAF import/export, and on-device speech APIs.

See [docs/API.md](docs/API.md) for endpoint coverage and [ROADMAP.md](ROADMAP.md) for remaining work.
