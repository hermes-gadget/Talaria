# Talaria

**Talaria** (Hermes' winged sandals) is a privacy-respecting native Android client for [self-hosted Hermes Agent](https://hermes-agent.nousresearch.com/docs/) by Nous Research.

It brings the Hermes Web Dashboard (default `http://127.0.0.1:9119`) to mobile — chat, status, config, keys, sessions, cron, skills, MCP, channels, pairing, and more — with notifications, on-device voice dictation, offline cache, and home-screen widgets.

## Features

| Area | What you get |
|------|----------------|
| **Connections** | Multiple saved profiles, session token / basic / bearer / OIDC-browser auth, optional TLS pinning |
| **Chat** | Live bridge to Hermes `/api/pty` (TUI over WebSocket), ANSI-stripped transcript, session resume |
| **Notifications** | Channels for replies, cron, gateway, pairing, errors, long tasks — actionable reply/open/dismiss |
| **Voice** | On-device `SpeechRecognizer` (cloud STT opt-in only), continuous dictation + partials, TTS of replies |
| **Manage** | Status, Sessions, Config, API Keys, Cron, Skills, MCP, Channels, Pairing, Webhooks, Profiles, Logs, Analytics, System |
| **Privacy** | Zero telemetry by default, Keystore-backed secrets, local Room cache, no forced accounts |
| **Polish** | Edge-to-edge Compose UI, share-to-chat, deep links (`talaria://…`), Glance widget, Quick Settings tile |

## Quick start

### Prerequisites

- JDK **17 or 21** (Temurin recommended)
- Android SDK platform **35** + build-tools **35.0.0**
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
   - **BASIC** — username/password when the dashboard is gated (`--host 0.0.0.0`)
   - **BEARER** / **OIDC_BROWSER** — for advanced / portal flows
3. Optional: management profile name (`?profile=`), TLS pin `sha256/…`
4. **Save & connect**

## Project layout

```
app/src/main/java/com/nousresearch/talaria/
  core/          network, security, Room, notifications, voice
  domain/        shared models
  feature/       connection, chat, activity, manage/*, you
  ui/            theme, navigation, components
  widget/        Glance widget + QS tile
  worker/        WorkManager sync, FGS, boot receiver
```

## Docs

- [ARCHITECTURE.md](ARCHITECTURE.md) — layers, auth, chat transport, notifications
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
