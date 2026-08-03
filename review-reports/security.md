# Talaria v0.8.2 — Mobile Security Review

Date: 2026-08-03  
Mode: read-only static review  
Target: Kotlin/Compose Android client in `the review worktree`

## Verdict

**Not ready for release against the stated hostile-local-app and untrusted-LAN threat model.** No critical issue was found, but the release build's unconditional Android Car `ALLOW_ALL_HOSTS_VALIDATOR` is a high-severity authorization failure: any installed app that can act as a car host is accepted into an authenticated surface that displays transcript excerpts and can create agents or send prompts. Fix that before shipping the car service.

The next release blockers are the legacy cleartext-consent migration and three remaining mutable-active-profile authentication paths. The private-range parser, TLS pin validation, encrypted storage, release trust anchors, WebSocket logging separation, and voice payload bounds are otherwise materially improved from the issues recorded in `audit.md`.

Finding count: **0 Critical, 1 High, 4 Medium, 4 Low**.

This was a source/configuration review. I did not edit source, invoke Git, run Gradle, install an APK, connect a real Hermes server, or exercise Android Auto/AAOS. Dependency checks covered declared direct coordinates and selected known transitives; without a resolved lockfile/SBOM they are not a complete transitive-component attestation.

## Findings

### F-01 — High — Release car service authorizes every host application

- **File:** `app/src/main/java/com/hermesgadget/talaria/car/TalariaCarService.kt:39-50`; entry point `app/src/main/AndroidManifest.xml:140-147`; impacts `CarSessionsRepository.kt:66-84,137-167` and `SessionListScreen.kt:165-175,205-275`.
- **Issue:** `createHostValidator()` unconditionally returns `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR`; the imported `BuildConfig` is unused. The exported service therefore accepts an arbitrary installed app as a car host. The accepted host can receive recent authenticated conversation messages and invoke reply, quick-start, and create-session actions. The source comment understates this as merely driving session UI and says the data is already on-device; the transcripts are fetched from Hermes and the host can trigger new privileged agent work.
- **Fix:** Use `ALLOW_ALL_HOSTS_VALIDATOR` only for debug/emulator builds. In release, validate package name plus signing-certificate SHA-256 for Google/AOSP and explicitly supported OEM hosts. If a static OEM list is operationally insufficient for sideloads, add phone-side enrollment that displays and pins the requesting package/certificate fingerprint. Until a host is enrolled, expose no transcripts and no send/create capability; require recent handset confirmation for sensitive actions. Test legitimate projected Android Auto and AAOS hosts plus a fake-host APK.

### F-02 — Medium — Legacy profiles silently opt into cleartext and the UI cannot record explicit LAN consent

- **File:** `app/src/main/java/com/hermesgadget/talaria/domain/model/ConnectionProfile.kt:31-46`; `core/data/repo/ConnectionRepository.kt:95-103`; `feature/connection/ConnectViewModel.kt:45-68,477-489,641-653,701-713,731-743,837-849`; `app/src/main/res/xml/network_security_config.xml:19-23`.
- **Issue:** The serializable `allowCleartext` property defaults to `true`. An older encrypted profile JSON record that lacks this field therefore becomes explicitly approved after upgrade without a user decision. Repository saving correctly requires `allowCleartext=true` plus a verified private/local literal, but `ConnectUiState` has no cleartext-consent field and every save path omits the argument. Thus new private-LAN HTTP profiles cannot be explicitly confirmed through this UI, while legacy profiles work because of the unsafe default. Because the XML base policy permits cleartext globally, an accidentally approved profile can transmit dashboard tokens, tickets, prompts, transcripts, and voice traffic without transport confidentiality.
- **Fix:** Change the stored default to `false` and implement a versioned migration: treat a missing field as undecided, auto-approve only the existing loopback/emulator set, and require a one-time risk dialog for other verified private literals. Add the decision to `ConnectUiState`, display a persistent HTTP warning, and pass it through every save/doctor/OIDC path. Add tests for missing-field legacy JSON, public/malformed/hostname rejection, explicit private-LAN approval, URL edits, and HTTPS forcing the flag false.

### F-03 — Medium — Native OIDC exchange can send the active profile's credential to the captured server

- **File:** `app/src/main/java/com/hermesgadget/talaria/core/network/NativeOidcLogin.kt:95-102,149-158,182-190`; `HermesClientFactory.kt:46-61`; `AuthInterceptor.kt:52-80`.
- **Issue:** `signIn()` captures profile A and later builds A's absolute `/auth/native/token` URL, but `exchange()` executes it with `clientFactory.okHttp()` using the profile active at that later moment. A system-browser flow can remain open for five minutes. If the user switches to profile B, B's `SESSION_TOKEN`, BASIC-derived session token/cookie where URL matching permits it, or BEARER token can be attached to A's request. `OIDC_BROWSER` suppresses its bearer on `/auth/native/`, which narrows but does not eliminate the cross-profile disclosure. `AuthInterceptor` checks that its snapshot is still stored but never verifies that the request origin belongs to that snapshot.
- **Fix:** Capture one full `ConnectionSnapshot` for `profileId` at flow start and use `api(snapshot)`/`okHttp(snapshot)` for status, provider discovery, token exchange, and persistence. Add a fail-closed origin check in `AuthInterceptor` before attaching any secret: scheme, canonical host, and effective port must equal the snapshot base origin. Test A-to-B switches during browser wait for every auth mode.

### F-04 — Medium — ReplyWorker WebSocket authentication is not bound to its captured connection

- **File:** `app/src/main/java/com/hermesgadget/talaria/worker/ReplyWorker.kt:43-60`; `core/network/WsAuthHelper.kt:49-80`; `PtyWebSocketSession.kt:107-128`.
- **Issue:** The worker checks active profile A and fixes the socket client/URL to A, then calls the no-argument `WsAuthHelper.authQueryParam()` twice. That helper independently rereads the mutable active profile and its secrets. A switch after the check can therefore place B's reusable SPA token—or a B-scoped single-use ticket—on A's PTY/event URL. Ticket server-binding may limit some deployments, but the SPA token is reusable and observable by A.
- **Fix:** Resolve the notification's `connectionId` and management profile with `snapshotFor`, not `activeProfile`. Change `WsAuthHelper` to require that snapshot and mint/fetch authentication through `api(snapshot)` and `okHttp(snapshot)`. Build both socket clients and both query values from the same immutable snapshot; reject execution if it changes or disappears. Add deterministic switch-during-worker tests.

### F-05 — Medium — Car prompt delivery has the same independent cross-profile authentication race

- **File:** `app/src/main/java/com/hermesgadget/talaria/car/CarSessionsRepository.kt:182-214`; `core/network/WsAuthHelper.kt:49-80`.
- **Issue:** `ptySend()` captures active profile A and fixes its client/URL, then obtains both auth query values from the mutable active profile. A concurrent phone-side switch during a car reply/create action can send B's token or ticket to A. This is separate from F-04 because the car action is an independently reachable entry point and remains exposed even if the worker is fixed.
- **Fix:** Capture a `ConnectionSnapshot` at the car operation boundary and pass it through every REST, ticket, PTY, and event call. Use the snapshot-required `WsAuthHelper` described in F-04, and cancel the operation when the selected connection changes. Cover phone-side switching while a car action is in progress.

### F-06 — Low — PTY text and binary messages have no client-side size budget

- **File:** `app/src/main/java/com/hermesgadget/talaria/core/network/PtyWebSocketSession.kt:129-143`.
- **Issue:** Text frames go directly into `AnsiStripper.Stream` and the event flow. Binary messages are first converted with `ByteString.utf8()`, creating another full representation, then processed the same way. A compromised or malicious configured Hermes endpoint can send a very large message to drive memory pressure, downstream Compose work, ANR, or process death. This is a single-client availability issue; binary data is rendered as inert text, not executed.
- **Fix:** Define a protocol-level maximum message size and reject/close with WebSocket code 1009 before UTF-8 conversion or ANSI/UI processing when the callback input exceeds it. Bound ANSI pending state, transcript accumulation, and event queues, and test oversized and sustained text/binary messages. OkHttp already assembles a complete message before the callback, so server/proxy enforcement is also desirable.

### F-07 — Low — Artifact JSON unwrap depth is reset through objects and arrays

- **File:** `app/src/main/java/com/hermesgadget/talaria/feature/manage/artifacts/ArtifactExtraction.kt:57-90,159-204`.
- **Issue:** Primitive stringified-JSON unwrapping increments `depth`, but `JsonArray` and `JsonObject` branches call the three-argument overload, resetting depth to zero. Alternating stringified objects/arrays therefore bypass `MAX_STRINGIFIED_JSON_DEPTH`; there is also no input, node, candidate, or structural depth budget. Crafted assistant/tool transcript content from the configured server can cause excessive parsing/recursion and crash the client.
- **Fix:** Carry one monotonically increasing depth through every branch, cap input characters/nodes/candidates, and preferably use an iterative traversal. Reject or stop unwrapping when any budget is exceeded. Add tests for deeply structural JSON and alternating stringified object/array chains beyond the limit.

### F-08 — Low — Developer identity and local paths are embedded in production UI strings

- **File:** `app/src/main/java/com/hermesgadget/talaria/feature/manage/review/ReviewViewModel.kt:201`; `ReviewScreen.kt:130`. Related repository metadata: `plan.md:5,46,51,102,109,116,215,217,286,292,294`; `SUMMARY.md:64,83`; `app/src/test/java/com/hermesgadget/talaria/network/FsModelDecodeTest.kt:33-44`.
- **Issue:** A production error and placeholder expose a local checkout path; source documentation/tests expose the developer name and home/tool paths. This is low-impact PII and environment metadata, not a credential leak. No private-key blocks, common API-token patterns, or hard-coded email addresses were found. The exact `192.168.2.5` fixture was not present in this snapshot; if it exists in another test revision, an RFC1918 test host is expected and not a secret.
- **Fix:** Replace personal examples with `/home/user/project` or another neutral fixture. If the repository is public and the owner considers the historical metadata sensitive, scrub release artifacts and assess history separately.

### F-09 — Low — Credential storage remains on an old alpha/deprecated crypto wrapper

- **File:** `gradle/libs.versions.toml:22`; `app/build.gradle.kts:152-157`; use site `app/src/main/java/com/hermesgadget/talaria/core/data/prefs/SecureConnectionStore.kt:34-49`.
- **Issue:** `androidx.security:security-crypto:1.1.0-alpha06` is from the alpha line and the Security Crypto APIs are now deprecated. No known OSV vulnerability was returned for this exact coordinate or the checked Tink 1.8.0 dependency, so this is lifecycle/assurance debt rather than a demonstrated CVE. It is still an unnecessarily old security boundary for passwords, session tokens, and refresh tokens.
- **Fix:** First move to `1.1.0` stable with migration/readback tests for existing encrypted preferences. Then plan a versioned move to direct Android Keystore-backed authenticated encryption or another supported platform design. Do not strand or silently discard existing secrets during migration.

## ALLOW_ALL-risk-assessment

`ALLOW_ALL_HOSTS_VALIDATOR` is not acceptable in a release build merely because the APK is sideloaded. Sideloading removes Play-distribution assurances; it does not cause Android to trust all other installed packages. The exact boundary at risk is an exported Binder-facing `CarAppService` whose validator is designed to authenticate the car host.

The practical compatibility concern in the source comment is legitimate: OEM Android Auto/AAOS hosts can use signatures not covered by a small sample allowlist, and rejecting a legitimate host can make the sideloaded app disappear from the launcher. That is an availability/support problem, not a reason to remove authorization. The hostile-app precondition is also narrower than a LAN attack: an attacker must get another APK installed locally and implement the host protocol. Android sandboxing still protects direct access to Talaria's files.

The impact is nevertheless high. Once accepted, the fake host is handed authenticated product functionality. It can obtain conversation titles and recent message bodies, and it can send prompts into an existing session or create new agent work. Agent prompts may invoke tools or expose further server-side data depending on Hermes policy. The path requires no Talaria handset confirmation after host acceptance.

Recommended staged posture:

1. Debug/emulator: permit `ALLOW_ALL_HOSTS_VALIDATOR` only under `BuildConfig.DEBUG`, with a distinct debug application ID/signing key.
2. Release default: package-and-signing-certificate allowlist for known Google/AOSP and tested OEM hosts; maintain the list as release data with provenance.
3. Compatibility escape hatch: phone-side enrollment of an observed package/certificate fingerprint, with an explicit warning and revocation UI. Never trust package name alone.
4. Defense in depth: before enrollment show no transcript content; require recent phone unlock/confirmation for reply/create; record the host identity associated with each action.
5. Verification: test projected Android Auto, embedded AAOS, supported OEM variants, signature rotation, an unsigned/debug host, and a malicious fake host.

AndroidX Car App `1.7.0` is not itself the vulnerable old release implicated by CVE-2024-10382; AndroidX release notes state the fix was present before the 1.7.0 stable release. That dependency fix does not compensate for Talaria's explicit allow-all policy. Sources: [AndroidX Car App release notes](https://developer.android.com/jetpack/androidx/releases/car-app), [OSV CVE-2024-10382](https://osv.dev/vulnerability/CVE-2024-10382).

## Credential-handling

### SPA dashboard token and logging

- `WsAuthHelper.kt:86-104` extracts `__HERMES_SESSION_TOKEN__` from the SPA shell and returns it; no direct `Log`, `println`, `printStackTrace`, or token interpolation was found.
- The token is stored through `SecureConnectionStore.updateSessionToken()` in `EncryptedSharedPreferences`, not Room or plaintext preferences.
- REST diagnostics use `HttpLoggingInterceptor.Level.BASIC` and redact `Authorization` and `X-Hermes-Session-Token` (`HermesClientFactory.kt:125-133`). BASIC logs method/URL but not headers/bodies. The dedicated WebSocket client has no logger (`:79-81`), which avoids logging `ticket=`, `token=`, and `attach=` query values.
- Residual rule: keep auth values out of exception messages, analytics, crash reports, UI doctor output, and future URL loggers. If diagnostic URLs are ever shared, strip credentials and consider IP/profile names PII.

### Session tickets and `attachToken`

- For gated dashboards, `WsAuthHelper` mints a ticket via `POST /api/auth/ws-ticket`; worker/car code obtains separate values for PTY and event sockets, consistent with single-use tickets.
- `HermesWebSocketUrlBuilder.kt:33-43` parses only `ticket`/`token` and adds all query fields through OkHttp's encoded query builder. There is no string-concatenation injection into the URL.
- `attachToken` is separate from authentication: it is an idempotency/attachment identifier passed alongside a real ticket/token. Car uses a fresh UUID; ReplyWorker derives a stable value from the delivery/message ID. The server must authenticate and authorize the attach operation independently and must not treat this identifier as a bearer capability. If it is capability-bearing server-side, use a cryptographically random, single-use, short-lived value instead.
- Tickets and attach values still appear in the HTTP upgrade request target and may be visible to a TLS terminator/reverse-proxy access log. Prefer short TTL/single use and configure server-side URL redaction.

### Keystore, lifecycle, and revocation

- `SecureConnectionStore.kt:34-49` uses an Android Keystore-backed AES-256-GCM `MasterKey`, AES-SIV preference-key encryption, and AES-GCM value encryption. `allowBackup=false` plus both backup rule files exclude shared preferences and databases. This is sound at-rest handling for a non-rooted device, subject to F-09 and normal unlocked-device compromise limits.
- Connection saves clear credentials on base-URL/auth-mode/provider boundary changes and retain only mode-relevant fields (`ConnectionRepository.kt:81-125`). `AuthMode.NONE` attaches no secret (`AuthInterceptor.kt:77-79`). Snapshot refresh writes are conditional on the original profile/secrets (`SecureConnectionStore.kt:151-172`).
- Deleting a profile removes its encrypted secret record and invalidates clients; invalidation cancels calls, evicts pools, clears cookies, and drops bundles (`ConnectionRepository.kt:153-156`, `HermesClientFactory.kt:67-77,96-103`). `PersistentCookieJar` is actually memory-only, expires cookies, and uses `Cookie.matches(url)`; the misleading class name is not an at-rest leak.
- There is no client-visible logout/token-revocation endpoint for dashboard/OIDC credentials. Locally deleting/editing a profile does not necessarily revoke a still-valid server token or provider refresh token. Add an explicit sign-out flow that attempts server/provider revocation, always clears local access/refresh/session state, cancels sockets, and reports whether remote revocation succeeded. Define TTL/rotation behavior for the SPA process token.
- F-03 through F-05 are the remaining lifecycle/binding defects: secret storage is encrypted, but a secret can still be delivered to the wrong saved server if request authentication is not origin- and snapshot-bound.

### TLS pinning and cleartext controls

- `CertificatePinnerFactory.kt:24-43` accepts only a base64-decoded 32-byte SHA-256 SPKI digest and normalizes it to OkHttp's `sha256/...` form. `ConnectionRepository.kt:77-80` permits a pin only for HTTPS.
- The pin is applied to REST, WebSocket, password bootstrap, and OIDC refresh clients (`HermesClientFactory.kt:135-142`; `SnapshotCredentialHelpers.kt:116-127,156-163`). No permissive hostname verifier or trust-all manager was found.
- Release XML trusts system CAs only; user-installed CAs appear solely under debug overrides (`network_security_config.xml:19-30`). Pinning is additive to normal CA/hostname verification; it does not make a self-signed certificate trusted and cannot protect HTTP.
- One stored pin creates a rotation/availability hazard. Prefer an ordered set containing current and backup SPKI pins, document overlap/rotation/recovery, and label the UI value as an SPKI/public-key pin rather than a certificate-file hash.
- `CleartextPolicy.isVerifiedDestination()` correctly rejects DNS names, public IPv4, malformed/out-of-range octets, and unsupported IPv6 forms; it permits only loopback, RFC1918 IPv4, and 169.254/16. Only localhost/`::1`/`127.0.0.1`/emulator `10.0.2.2` are auto-approved. The fail-closed lack of IPv6 ULA support is an availability limitation, not a bypass.
- The global XML cleartext permission is defensible only while every runtime HTTP client is covered by `CleartextPolicyInterceptor`. Current active clients and snapshot credential helpers are covered; remove unused legacy credential helpers and add a regression test/lint rule so future direct clients cannot bypass the app-level gate.

### Injection and parsing assessment

- No WebView, raw SQL construction, command execution, or unsafe object deserialization path was found in the reviewed client.
- Markdown is rendered into Compose text, not HTML. `SimpleMarkdown.kt:109-115` opens only `http`, `https`, and `mailto` links. The anchored fence/heading/table/list regexes and inline delimiter scans showed no credible catastrophic-regex or script-injection path. Large document budgets and fuzzing are still useful hardening, but the current markdown regexes were not promoted as a finding.
- Artifact paths identify files on the remote Hermes server; extraction does not open an Android local path. Server-side file APIs must still enforce workspace containment. F-07 concerns recursion/resource use, not local path traversal.
- PTY binary frames are decoded as UTF-8 and displayed as text after ANSI processing. There is no shell or code-execution sink; F-06 is resource exhaustion.
- Voice recording/playback now has duration, encoded-character, decoded-byte, streaming, dispatcher, and cleanup limits in `feature/voice/VoiceRecorder.kt` and `VoiceAudioDataUrl.kt`; the prior unbounded-data findings in `audit.md` are addressed in this snapshot.

## Dependencies

Declared-version review was performed on 2026-08-03. Direct OSV queries returned no known vulnerability for the checked declared coordinates, including OkHttp `4.12.0`, Retrofit `2.11.0`, Kotlin/serialization/coroutines, Room, WorkManager, Browser, Car App `1.7.0`, and Security Crypto `1.1.0-alpha06`; selected known transitives Tink `1.8.0` and Okio `3.6.0` also returned none. “No OSV match” is not proof of safety, and Google Maven/BOM transitives were not fully resolved without running Gradle.

| Component | Pinned version | Assessment | Action |
|---|---:|---|---|
| `androidx.security:security-crypto` | `1.1.0-alpha06` | **Known-risky lifecycle choice:** old alpha on a credential boundary; APIs are deprecated. No current OSV CVE found. | Upgrade to stable `1.1.0` with encrypted-pref migration tests, then move to supported platform Keystore primitives. [AndroidX Security release notes](https://developer.android.com/jetpack/androidx/releases/security) |
| `androidx.car.app:app` / `app-automotive` | `1.7.0` | Current pin includes the CVE-2024-10382 fix; Talaria's explicit `ALLOW_ALL` remains vulnerable policy. | Keep at or above the fixed line and fix F-01. [AndroidX Car App notes](https://developer.android.com/jetpack/androidx/releases/car-app), [OSV advisory](https://osv.dev/vulnerability/CVE-2024-10382) |
| OkHttp / logging / MockWebServer | `4.12.0` | No known OSV match in the direct check. TLS/hostname defaults are not overridden. | Maintain coordinated upgrades and add origin-binding tests before any major client change. |
| Retrofit | `2.11.0` | No known OSV match in the direct check. | Keep converter/OkHttp versions tested together. |
| Kotlinx serialization | `1.8.0` | No known OSV match in the direct check; first-party recursive traversal remains the F-07 issue. | Upgrade routinely and keep parser size/depth budgets independent of library behavior. |
| AndroidX Car host validator policy | app code | Not a dependency CVE. `ALLOW_ALL_HOSTS_VALIDATOR` intentionally disables the library's caller validation. | Treat F-01 as a release blocker even though Car App `1.7.0` is patched. |

For release assurance, generate a resolved release-runtime SBOM or dependency lockfile in CI, scan it with OSV/Dependency-Track or equivalent, fail on known exploitable High/Critical issues, retain the scan with the APK provenance, and review Android/Google security release notes because not every affected artifact is represented cleanly in Maven Central tooling.
