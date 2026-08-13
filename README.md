# Talaria

**Talaria** (Hermes' winged sandals) is a privacy-respecting native Android client for [self-hosted Hermes Agent](https://hermes-agent.nousresearch.com/docs/) by Nous Research.

It adapts the remote-capable parts of Hermes Desktop and the Web Dashboard (default `http://127.0.0.1:9119`) to mobile: chat, status, config, keys, sessions, cron, skills, MCP, channels, pairing, and more, with notifications, voice dictation, offline cache, and home-screen widgets.

## Features

| Area | What you get |
|------|----------------|
| **Connections** | Multiple saved servers, password-cookie / session-token / bearer / native OIDC PKCE auth, optional TLS pinning |
| **Chat** | Live `/api/pty` bridge plus `/api/ws` and `/api/events`, clean reading mode, terminal mode, session resume, image attachments |
| **Notifications** | Thread-aware permission and completion alerts named for the selected agent, exact-thread deep links, background turn monitoring, plus cron/gateway/pairing/error channels |
| **Voice** | Server STT primary (`/api/audio/transcribe`), on-device `SpeechRecognizer` fallback, continuous dictation + partials, TTS of replies |
| **Manage** | Status, Sessions, Config, API Keys, Cron, Skills Hub, MCP catalog/OAuth, Channels, Pairing, Webhooks, Profiles, Files, Artifacts, Learning, Logs, Analytics, System |
| **Car** | Android Auto projected app + AAOS templated car app: agent list, voice replies, one-tap quick starts, driving-safe agent creation |
| **Privacy** | Zero telemetry by default, Keystore-backed secrets, local Room cache, no forced accounts |
| **Polish** | Edge-to-edge Compose UI, adaptive phone/foldable layouts, share-to-chat, deep links (`talaria://…`), Glance widgets, Quick Settings tile, picture-in-picture chat |

## Quick start

### Prerequisites

- JDK **17 or 21** (Temurin recommended)
- Android SDK platform **37** + build-tools **36.0.0**
- A reachable Hermes dashboard (`hermes dashboard`, often via Tailscale/SSH tunnel)

### Build & install (debug)

```bash
export JAVA_HOME=/path/to/jdk-21   # if needed
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or use the helper that pins a local JDK path when present:

```bash
./gradlew.local :app:assembleDebug
```

### Connect

1. Open Talaria → enter your dashboard URL (e.g. `http://100.x.y.z:9119`).
2. Choose auth mode:
   - **SESSION_TOKEN** — token printed/injected by the dashboard (loopback / token mode)
   - **BASIC** — Hermes password-provider login; Talaria stores the resulting session cookie
   - **BEARER** — a pre-issued bearer token
   - **OIDC_BROWSER** — RFC 8252 loopback redirect with PKCE and refresh tokens
3. Optional: management profile name (`?profile=`), TLS pin `sha256/…`
4. **Save & connect**

## Project layout

```
app/src/main/java/com/hermesgadget/talaria/
  core/          network, security, Room, notifications, voice
  domain/        shared models
  feature/       connection, chat, activity, manage/*, you
  ui/            theme, navigation, components
  widget/        Glance widget + QS tile
  worker/        WorkManager sync, notification actions, boot receiver
```

## Docs

- [ARCHITECTURE.md](ARCHITECTURE.md) — layers, auth, chat transport, notifications
- [ROADMAP.md](ROADMAP.md) — verified coverage and remaining mobile-relevant work
- [SETUP.md](SETUP.md) — SDK, signing, Hermes server tips
- [PRIVACY.md](PRIVACY.md) — data handling & telemetry stance
- [CONTRIBUTING.md](CONTRIBUTING.md) — development workflow
- [CHANGELOG.md](CHANGELOG.md) — release notes
- [docs/API.md](docs/API.md) — Hermes endpoint mapping & gaps

## Release APK

```bash
# Optional signing — create keystore.properties (see SETUP.md)
./gradlew :app:assembleRelease
./gradlew :app:assembleSignedRelease   # same, with a clear log about signing
```

Without `keystore.properties`, release builds are **unsigned** (still useful for local sideload after `apksigner` / Play App Signing).

## License

Apache License 2.0 — see [LICENSE](LICENSE).

Hermes Agent is a separate project by Nous Research; Talaria is an independent mobile client that speaks its dashboard API.
