# Setup

## Development machine

### JDK

Use **JDK 21** (or 17). Java 25 is not supported by the Android Gradle Plugin / Gradle combination used here.

```bash
# Example: Temurin 21 unpacked under ~/.jdks
export JAVA_HOME="$HOME/.jdks/jdk-21.0.12+8"
export PATH="$JAVA_HOME/bin:$PATH"
```

`gradlew.local` exports a default `JAVA_HOME` if you keep the JDK at that path.

### Android SDK

Set `sdk.dir` in `local.properties` (already gitignored):

```properties
sdk.dir=/home/you/Android/Sdk
```

Required packages:

- `platforms;android-37.0`
- `build-tools;36.0.0`
- `platform-tools`

Install via `sdkmanager` or Android Studio SDK Manager.

### Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Note: debug builds use `applicationIdSuffix .debug` → `com.hermesgadget.talaria.debug`.

## Hermes server tips

```bash
# Local only
hermes dashboard

# Reachable on LAN / Tailscale (auth gate ON)
hermes dashboard --host 0.0.0.0 --port 9119 --no-open
```

Configure basic auth (trusted networks) or Nous/OIDC per [Hermes Web Dashboard docs](https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard).

For phones, prefer Tailscale/WireGuard or an SSH tunnel over exposing the dashboard to the public internet.

Session token: in loopback mode the SPA receives `__HERMES_SESSION_TOKEN__`. Copy that token into Talaria’s SESSION_TOKEN field, or use basic auth against a gated bind.

## Obtainium / GitHub release APKs

Tagged releases (`v*`) build a **signed release** APK (`com.hermesgadget.talaria`)
in CI with the independent Hermes Gadget upload keystore from repo secrets
(`TALARIA_CI_KEYSTORE_*`), and
attach `Talaria-vX.Y.Z.apk` to the GitHub pre-release. `versionName` /
`versionCode` come from the tag (`v0.2.1` → name `0.2.1`, code ≥ `202`).

The corrected v0.4 release changed the former `com.nousresearch.talaria`
identity to `com.hermesgadget.talaria` because Talaria is independent and is
not affiliated with Nous Research. Android treats the corrected ID as a new
app: uninstall the former package once, then install v0.4. Future releases
using `com.hermesgadget.talaria` update in place.

Local `./gradlew :app:assembleDebug` uses package `com.hermesgadget.talaria.debug`
and your machine debug keystore — do **not** mix that with Obtainium installs.
Prefer Obtainium → GitHub releases on device. If you previously installed a
former-package or `*-debug.apk` build from GitHub, uninstall it once before
installing the corrected release APK.

## Signing release APKs

1. Generate a keystore (once):

```bash
keytool -genkey -v -keystore talaria-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias talaria
```

2. Create `keystore.properties` in the repo root (gitignored):

```properties
storeFile=/absolute/path/to/talaria-release.jks
storePassword=***
keyAlias=talaria
keyPassword=***
```

3. Build:

```bash
./gradlew :app:assembleRelease
# or
./gradlew :app:assembleSignedRelease
```

Output: `app/build/outputs/apk/release/`.

Without `keystore.properties`, `assembleRelease` still produces an **unsigned** APK — useful for CI artifact checks; sign with `apksigner` before distribution.

### Manual signing (no Gradle signingConfig)

```bash
zipalign -v -p 4 app-release-unsigned.apk app-release-aligned.apk
apksigner sign --ks talaria-release.jks --out app-release.apk app-release-aligned.apk
apksigner verify app-release.apk
```

## Permissions you will grant on device

- Notifications (Android 13+)
- Microphone (dictation)

## Emulator networking

`127.0.0.1` on the emulator is the emulator itself. Use `10.0.2.2:9119` to reach a dashboard on the host machine, or a LAN/Tailscale IP for physical devices.


## Native OIDC login

1. On Connect, set **Auth mode** to `OIDC_BROWSER`.
2. Tap **Sign in with browser**. Talaria discovers the server's native OIDC provider and opens its authorization URL.
3. Complete login in the browser. The provider redirects to a temporary loopback listener on the phone; Talaria validates the callback state and exchanges the PKCE verifier for tokens.
4. Talaria stores the refresh token in encrypted preferences, refreshes access before expiry, and mints WebSocket tickets when required.
5. If the server does not advertise native OIDC, use its password provider or a session token instead.
