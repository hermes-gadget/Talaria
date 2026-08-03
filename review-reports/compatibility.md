# Talaria v0.8.2 device/platform compatibility review

Reviewed 2026-08-03. This was a read-only source/configuration review of the requested Android manifest, app Gradle configuration, `car/`, `core/network/`, `feature/chat/`, `widget/`, and `audit.md`, plus the directly related worker and notification code needed to assess background and Android Auto behavior. No source was edited, no build was run, and no Git command was run.

## Android-Auto-projection

### Verdict

**Fail for the stated requirement (“the sideloaded APK appears when the phone is plugged into a real Android Auto vehicle”).** The car service itself is shaped correctly, but there are two independent blockers:

1. **The Android Auto capability declaration is missing.** `AndroidManifest.xml` has no `com.google.android.gms.car.application` metadata, and there is no `res/xml/automotive_app_desc.xml`. Android Auto requires that metadata to discover a templated app. A templated messaging app's descriptor must declare both `notification` and `template` capabilities. See [Add support for Android Auto to a templated app](https://developer.android.com/training/cars/apps/auto) and [Build templated messaging experiences](https://developer.android.com/training/cars/communication/templated-messaging).
2. **A normal GitHub/Obtainium sideload is not a supported real-vehicle distribution path for a Car App Library app.** Current Android guidance requires a trusted source for a real vehicle and explicitly says Android Auto's “Unknown sources” developer option does **not** apply to apps built with the Android for Cars App Library. Use Google Play Internal App Sharing or an Internal/Closed test track for real-car testing. See [Test Android apps for cars](https://developer.android.com/training/cars/testing).

Fixing only the manifest will make DHU/emulator discovery testable, but it does not make an untrusted release sideload reliably visible in real production vehicles.

### What is correct

- `TalariaCarService` is exported and advertises the required `androidx.car.app.CarAppService` action and `androidx.car.app.category.MESSAGING` category (`AndroidManifest.xml:139-147`).
- `androidx.car.app.minCarApiLevel=7` is correctly on `<application>`, not on the service (`AndroidManifest.xml:28-35`). `ConversationItem` requires Car API 7, so the declared floor matches the implementation (`SessionListScreen.kt:136-175`).
- The service can inherit its label and adaptive launcher icon from `<application>`; explicit service label/icon attributes would be clearer but are not required (`AndroidManifest.xml:20-23`, `139-147`).
- Car App Library 1.7.0 satisfies the Android 15 AAOS compatibility floor documented by Android (`gradle/libs.versions.toml:28-29`).
- `createHostValidator()` returns `ALLOW_ALL_HOSTS_VALIDATOR` in every build (`TalariaCarService.kt:34-50`). Therefore the sample allowlist and OEM Android Auto signing differences are **not** the reason this build is hidden.
- Application initialization occurs before the bound service, so the service can read the saved connection without starting `MainActivity`. With no connection, the first template provides a useful phone-setup error (`SessionListScreen.kt:277-295`).

### Projection risks and reasons Android Auto can hide the app

In likely order:

1. Missing `com.google.android.gms.car.application` metadata and missing `automotive_app_desc.xml` (`notification` + `template`). This is the immediate repository defect.
2. Release APK installed from GitHub/Obtainium/ADB instead of a trusted source. “Unknown sources” is not an escape hatch for Car App Library apps on real vehicles.
3. Android Auto host reports Car API below 7. There is no lower-API fallback because every useful list includes `ConversationItem`; older/outdated phone hosts must hide or reject the app.
4. Templated messaging's required notification-powered companion experience is incomplete. Notifications use `BigTextStyle` plus a reply `RemoteInput`, not `MessagingStyle`, and have no mark-as-read action (`TalariaNotifier.kt:293-331`, `NotificationActionReceiver.kt:31-75`). This may not block a permissive DHU launch, but it fails the documented messaging integration contract and makes production qualification fragile.
5. Android Auto or Google Play services is disabled/outdated, the phone/app is force-stopped, the app is installed only in a work/secondary profile Android Auto is not using, or both `.debug` and release packages are installed and the wrong one is being inspected.
6. A preinstalled OEM Android Auto build is stale and exposes a pre-7 template host. Update Android Auto through Play before blaming the vehicle firmware; projection's host logic runs on the phone.
7. If strict Android 16 intent matching is later enabled with `android:intentMatchingFlags="enforceIntentFilter"`, the car service may need `allowNullAction`. It is not currently enabled, so its absence is not today's blocker. Android documents the potential car-service interaction in [Android for Cars platform releases](https://developer.android.com/training/cars/platforms/releases).

`ALLOW_ALL_HOSTS_VALIDATOR` is an availability-positive but security-negative tradeoff: any installed app can impersonate a car host and enter Talaria's authenticated car surface. This matches the documented sideload/OEM rationale and rules out validator rejection, but it should remain explicitly accepted and threat-modeled. If tightened later, validate real Samsung/OnePlus/Google/OEM signer coverage before replacing it with the small sample allowlist.

### Required manifest/package changes before testing

For the phone/projection artifact:

```xml
<!-- Inside <application> -->
<meta-data
    android:name="com.google.android.gms.car.application"
    android:resource="@xml/automotive_app_desc" />
```

`app/src/main/res/xml/automotive_app_desc.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="notification" />
    <uses name="template" />
</automotiveApp>
```

Also implement notification-powered messaging with `NotificationCompat.MessagingStyle`, immutable content/mark-read pending intents, a mutable reply pending intent, and reply + mark-as-read handlers. Existing generic task-completion notifications do not satisfy that complete contract.

### Real-device and DHU logcat checks

Use the exact installed package (`com.hermesgadget.talaria` for release, `com.hermesgadget.talaria.debug` for debug):

```bash
adb shell pm path com.hermesgadget.talaria
adb shell dumpsys package com.hermesgadget.talaria
adb shell dumpsys package com.google.android.projection.gearhead
adb logcat -c
adb logcat -b all -v threadtime | grep -Ei 'talaria|carapp|car app|gearhead|hostvalidator|min api|template|PackageManager|ForegroundServiceStartNotAllowed'
```

Before connecting, inspect the packaged/merged manifest (not just the source manifest) and confirm:

- the Google car metadata resolves to an existing descriptor;
- the descriptor contains `notification` and `template`;
- `TalariaCarService` is enabled/exported and its action/category survived manifest merging;
- application min Car API metadata is 7;
- the installed artifact is the same package/certificate/version being tested.

Interpretation:

- No Talaria/car-service trace at all: discovery, trusted-source, user/profile, or Car API filter.
- Host bind followed by `Min API level not declared`: packaged metadata is missing/misplaced despite source expectations.
- `RequiresCarApi` or host API `< 7`: update Android Auto or test a fallback implementation.
- Host-validation rejection should be impossible with current `ALLOW_ALL`; if seen, verify the running package/version and that minification did not select another service.
- `onCreateSession`/`onGetTemplate` reached but screen fails: inspect template constraint/model exceptions and the underlying Hermes network errors separately.
- Android 16 `PackageManager` messages saying an intent does not match a filter matter only if strict intent matching was opted in.

There is no application logging in `TalariaCarService`, so add temporary release-safe lifecycle logs or debugger breakpoints in a future diagnostic build for `createHostValidator`, `onCreateSession`, and `onGetTemplate`; system logs alone cannot distinguish every path.

### Device checklist

1. **DHU first:** install a debug build containing the descriptor, enable Android Auto developer mode, start Head Unit Server, and run DHU over USB/ADB. Current official testing uses DHU; the discontinued **Android Auto for Phone Screens** experience is not a valid modern projection test and does not exercise a real template host. See [Test using the Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu).
2. **Trusted real-car build:** distribute the same signed release through Internal App Sharing or Internal/Closed testing. Do not use GitHub/Obtainium sideload visibility as the pass criterion.
3. Update Android Auto and Google Play services; confirm the host reports Car API 7+.
4. Open Talaria once on the phone, create the Hermes profile, and grant notification/microphone permissions before connecting. Permission dialogs cannot be completed on every car display.
5. Ensure the app is not force-stopped or in Samsung “Deep sleeping apps”/OnePlus aggressive battery restriction while checking background replies and task completion. These settings affect workers/services, not initial package discovery.
6. Test release and debug package IDs separately; uninstall the other variant to avoid duplicate-label confusion.
7. Test wired projection on at least Pixel/AOSP, Samsung One UI, and OnePlus/OxygenOS. Repeat with Android Auto preinstalled then Play-updated, and record phone/AA/Play-services/car firmware versions.
8. Test cold phone process, locked phone, app backgrounded, notification permission denied, battery saver, Doze, restricted app standby, mobile data only, Wi-Fi only, VPN/Tailscale, and loss/recovery of the Hermes route.
9. Test touch, rotary, and non-touch DHU configurations; exercise create, quick start, reply, mark read, retry, and connection-error templates.
10. A home-LAN Hermes address is normally unreachable once driving, and wireless Android Auto can change Wi-Fi routing. Use a routed VPN/Tailscale or publicly reachable TLS endpoint for on-road tests.

### AAOS specifics

The current single APK contains `CarAppActivity` and an automotive trampoline, but it is **not a compliant AAOS templated-app artifact**:

- Android requires a separate automotive build/module for templated apps. The phone and AAOS requirements intentionally use different metadata names.
- The AAOS manifest must require `android.hardware.type.automotive` and `android.software.car.templates_host`.
- It must point `com.android.automotive` (not the Google projection metadata) to an AAOS descriptor containing `template`.
- `CarAppActivity` must be the launchable `MAIN`/`LAUNCHER` activity. Current source deliberately gives it no intent filter and launches it from the phone `MainActivity` (`AndroidManifest.xml:72-87`, `MainActivity.kt:63-76`). That can help a userdebug sideload test but is not AAOS discovery/distribution compliance.
- The `app-automotive` AAR's optional automotive feature and renderer query do not replace those app declarations.
- `distractionOptimized=true` is only trusted on a trusted install or userdebug image.
- AAOS has independent app storage and network connectivity; a phone's saved connection is not projected/transferred. Configure Hermes on the car build, and expect no home Wi-Fi and highly variable cellular routing.

See [Add support for Android Automotive OS to a templated app](https://developer.android.com/training/cars/apps/automotive-os). Split shared car/session code into a shared module, keep the mobile projection artifact free to use minSdk 28 if desired, and create a minSdk 29 automotive artifact with the required AAOS-only manifest.

## API-levels-permissions

- `compileSdk=36`, `targetSdk=36`, `minSdk=29` (`app/build.gradle.kts:36-48`). This covers Android 10 through Android 16. Android Auto supports Android 9 phones, so Talaria intentionally excludes one supported projection generation. The cause is combining `app-automotive` (minSdk 29) into the phone module (`app/build.gradle.kts:165-168`); a separate automotive module would allow the phone artifact to return to minSdk 28.
- Target 36 is appropriate, but it activates Android 16's large-screen behavior: orientation/resizability restrictions are ignored on `sw600dp` devices. `PipChatActivity`'s `resizeableActivity=false` cannot be relied upon there (`AndroidManifest.xml:89-95`). See [Android 16 adaptive-layout changes](https://developer.android.com/about/versions/16/behavior-changes-16).
- `POST_NOTIFICATIONS` is requested only on API 33+ and checked before posting (`ChatScreen.kt:188-199`, `TalariaNotifier.kt:362-365`): pass.
- `RECORD_AUDIO` is requested in context before chat/voice capture (`ChatScreen.kt:1111-1123`, `VoiceScreen.kt:82-112`): pass.
- `FOREGROUND_SERVICE` plus `FOREGROUND_SERVICE_DATA_SYNC` match the manifest service type (`AndroidManifest.xml:12-13`, `133-137`): declaration pass, duration caveat below.
- `RECEIVE_BOOT_COMPLETED` is used only to re-enqueue WorkManager periodic work, not to start the prohibited data-sync foreground service (`BootReceiver.kt:24-28`): pass for Android 15.
- `WAKE_LOCK` and network-state access are consistent with WorkManager/network use. No Bluetooth/location permission is required merely to be projected by Android Auto.
- `RECORD_AUDIO` implicitly makes `android.hardware.microphone` required for Play filtering. Voice is optional and the rest of Talaria works without a mic, so add `<uses-feature android:name="android.hardware.microphone" android:required="false"/>` to keep mic-less tablets/AAOS devices eligible.
- Both normal and round icons are adaptive `mipmap-anydpi-v26` resources with foreground/background and Android 13 monochrome layers. With minSdk 29 there is no legacy-icon gap (`ic_launcher.xml:1-6`, `ic_launcher_round.xml:1-6`). Verify safe-zone masking and monochrome contrast on Samsung/OnePlus launchers, but the resource structure is correct.
- Android 16 local-network protection is still documented as opt-in/forward-looking for target 36. Talaria has no nearby/local-network permission. Test now with `RESTRICT_LOCAL_NETWORK`; plan an in-context permission flow when Android's final enforced permission contract lands. See [Android 16 local-network guidance](https://developer.android.com/about/versions/16/behavior-changes-16).

## Background-and-network

### Polling and workers

- The “30-second poller” is a `viewModelScope` loop, not an OS scheduler (`ChatViewModel.kt:344-352`). It continues while the Activity/ViewModel remains in memory even when the UI is stopped, and it is suspended/delayed or lost when Doze/OEM process management intervenes. It is neither reliable background sync nor appropriately foreground-only. Gate it to `Lifecycle.State.STARTED` or expose explicit start/stop hooks from `repeatOnLifecycle`.
- Each open chat tab also performs a serialized reading poll every 2.5 seconds (`ChatViewModel.kt:2375-2405`). The request-race fix is good, but these jobs also live until tab/ViewModel teardown. Stop them when the process UI is backgrounded and restart with an immediate refresh.
- The durable poller is WorkManager with `NetworkType.CONNECTED` and a 15–360 minute interval (`SyncScheduler.kt:38-49`). This is Doze-aware and correctly approximate; it is not a promise of exact cadence. Samsung/OnePlus restricted/force-stopped states can delay it until the user launches the app.
- `ReplyWorker` is enqueued as ordinary one-time work with no network constraint and not expedited (`NotificationActionReceiver.kt:56-71`). A user can reply from Android Auto during Doze, then the worker can be delayed or consume retries while offline. Add `NetworkType.CONNECTED`, make user-initiated reply work expedited with an out-of-quota fallback, give it a stable unique/idempotency key, and surface delayed/failed delivery without immediately dismissing the originating notification.
- `AgentTaskNotificationService` correctly calls `startForeground` before opening sockets and implements Android 15's `onTimeout` callback (`AgentTaskNotificationService.kt:85-88`, `121-127`). However, its `dataSync` type has a six-hour cumulative background limit per 24 hours for target 35+, so an indefinite agent watch will be stopped and later starts can throw `ForegroundServiceStartNotAllowedException`. See [Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout). Keep watches turn-scoped, persist/resume visibly, and test the shortened timeout compat configuration.
- Starting the monitor is wrapped in `runCatching` and failures are discarded (`AgentTaskNotificationService.kt:326-335`). On OEM/background-start rejection, monitoring silently disappears. Record the exception and update UI/notification state.

### Cleartext, TLS, and LAN reachability

- The XML deliberately permits cleartext globally at the platform layer (`network_security_config.xml:19-23`), while `CleartextPolicyInterceptor` applies per-profile policy before every REST/WebSocket socket (`HermesClientFactory.kt:115-123`). That architecture is coherent for arbitrary user-entered LAN IPs.
- The current UI cannot actually make the promised physical-device LAN choice. `ConnectionRepository.save` requires an explicit `allowCleartext=true` for any private IP except loopback/`10.0.2.2` (`ConnectionRepository.kt:95-103`), but `ConnectUiState` has no such field and `saveConnectionDraft` never passes it (`ConnectViewModel.kt:40-68`, `477-489`). As a result, the screen's advertised `http://192.168.x`/LAN/Tailscale path fails with “explicit confirmation” and no way to confirm.
- Cleartext destination parsing accepts loopback, RFC1918 IPv4, and link-local IPv4 only (`ConnectionSnapshot.kt:97-127`). It rejects Tailscale CGNAT `100.64.0.0/10`, IPv6 ULA/link-local, `.local`, and private DNS/MagicDNS names, even after a future confirmation UI. Extend classification carefully using resolved-route/network-capability evidence, guard DNS rebinding, and persist the exact user's decision.
- Release trusts system CAs only; user-installed CAs are debug-only (`network_security_config.xml:19-30`). Therefore a self-signed/private-CA Hermes certificate installed by the user fails in release. A certificate pin does not replace normal TLS trust validation. Use a system-trusted certificate, or provide a narrowly scoped in-app CA/pin trust design rather than globally trusting user CAs.
- Cleartext protects compatibility, not confidentiality. On wired/wireless Android Auto, use TLS plus VPN/Tailscale for prompts, transcripts, and session tokens. A local address that works in the house is not an on-road route.

## Form-factors

- Root navigation uses `currentWindowAdaptiveInfo()` and `NavigationSuiteScaffold`, so bottom bar/rail selection adapts to current window class (`TalariaNavRoot.kt:219-240`): good large-screen baseline.
- Chat uses a second hard-coded `screenWidthDp >= 600` decision (`ChatScreen.kt:163-164`) to switch from modal session sheet to permanent side rail (`ChatScreen.kt:492-590`, `622-639`). `LocalConfiguration` does update for multi-window/fold/unfold, so it is responsive, but it can disagree with the root's richer adaptive info and has no height/posture handling.
- No `WindowLayoutInfo`/`FoldingFeature` handling exists. On a separating or occluding hinge, the fixed rail and transcript/composer row can be split across the fold. Derive both root and chat layout from one `WindowAdaptiveInfo`, then place the list/detail panes around a separating hinge. See [Make your app fold aware](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware).
- Target 36 makes resizability mandatory on large screens. Test unfolded portrait/landscape, tabletop, split-screen, desktop windowing, font scale 200%, and compact height. The current threshold considers only width.
- Status widget correctly supports horizontal/vertical resize and uses the minimum supported 30-minute periodic update, live read, and cached fallback (`status_widget_info.xml:5-9`, `TalariaStatusWidget.kt:43-71`). Updates are still host/Doze/OEM-batched. Android 15 force-stop grays widgets until the app is launched again.
- Quick-entry widget is a fixed four-cell, one-row target with horizontal-only resize and a 250dp minimum (`quick_entry_widget_info.xml:5-11`). It can be unavailable or cramped on narrow OEM grids. Supply responsive size modes/layouts and localization; keep both explicit deep-link intents, which are otherwise robust.
- QS tile declaration is protected by `BIND_QUICK_SETTINGS_TILE` (`AndroidManifest.xml:149-158`): pass. The tile launches a network refresh on every listen/click but always reports `STATE_ACTIVE`, even when offline (`TalariaTileService.kt:42-53`). Cancel/ignore stale refreshes on `onStopListening` and use `STATE_UNAVAILABLE`/`INACTIVE` accurately for OEM hosts with short TileService lifetimes.

## Findings-table

| Severity | File:line | Issue | Fix |
|---|---|---|---|
| **High** | `app/src/main/AndroidManifest.xml:15` | Android Auto templated-app support metadata and `automotive_app_desc.xml` are absent, so projection discovery is incomplete. | Add `com.google.android.gms.car.application` metadata and a descriptor with `notification` + `template`; verify the merged APK manifest. |
| **High** | `app/src/main/java/com/hermesgadget/talaria/car/TalariaCarService.kt:28` | The comment assumes a sideloaded Car App Library APK is discoverable in a real vehicle without Play. Current Android Auto trusted-source filtering contradicts this, and “Unknown sources” does not cover Car App Library apps. | Use Internal App Sharing or Internal/Closed Play testing for real-car installation; treat raw sideload as DHU/emulator-only/unsupported. |
| **High** | `app/src/main/java/com/hermesgadget/talaria/feature/connection/ConnectViewModel.kt:477` | Advertised HTTP LAN/Tailscale onboarding has no cleartext-confirmation state and never passes `allowCleartext`, so physical-device private HTTP profiles cannot be saved. | Add an explicit risk dialog/state, pass and persist the decision, and add real-device tests for RFC1918 destinations. |
| **High (accepted security risk)** | `app/src/main/java/com/hermesgadget/talaria/car/TalariaCarService.kt:50` | Every installed host is trusted. This improves OEM compatibility and is not the visibility blocker, but exposes authenticated car UI to a hostile local host app. | Keep as an explicit accepted sideload tradeoff, or build a maintained signer policy with OEM coverage; never regress blindly to the sample-only allowlist. |
| **Medium** | `app/src/main/java/com/hermesgadget/talaria/core/notifications/TalariaNotifier.kt:293` | Generic `BigTextStyle` + reply is not the required Android Auto notification-powered messaging experience; `MessagingStyle` and mark-as-read are missing. | Implement `MessagingStyle` conversations plus reply and mark-read actions/services, matching `ConversationItem` IDs. |
| **Medium** | `app/src/main/AndroidManifest.xml:72` | AAOS is attempted through a phone-APK trampoline; required automotive/template-host features, `com.android.automotive` metadata, and launchable `CarAppActivity` filter are absent. | Create a dedicated minSdk 29 automotive module/artifact with the AAOS-only manifest and shared car code. |
| **Medium** | `app/src/main/java/com/hermesgadget/talaria/core/network/ConnectionSnapshot.kt:99` | Cleartext host classification rejects Tailscale `100.64/10`, IPv6 local ranges, mDNS, and private DNS names despite UI language promising Tailscale/LAN. | Add explicit, rebinding-safe destination/routing validation for supported IPv4/IPv6/name cases and test each transport. |
| **Medium** | `app/src/main/res/xml/network_security_config.xml:19` | Release accepts only system CAs, so private/self-signed HTTPS fails even if the user installed its CA; pinning does not create trust. | Prefer system-trusted TLS or implement narrow per-profile custom trust material with explicit UX and rotation. |
| **Medium** | `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatViewModel.kt:344` | The 30-second session poll and per-tab 2.5-second reads are not lifecycle-gated and are unreliable/wasteful in background/Doze. | Run only while UI is STARTED; do durable background refresh through WorkManager/events and refresh immediately on resume. |
| **Medium** | `app/src/main/java/com/hermesgadget/talaria/core/notifications/NotificationActionReceiver.kt:60` | ReplyWorker has no network constraint, expedited policy, or visible delayed-delivery state. Android Auto replies can be delayed or exhaust retries in Doze/offline states. | Add connected constraint, expedited user-initiated work with fallback, unique/idempotent work, and retain delivery UI until acknowledged. |
| **Medium** | `app/src/main/java/com/hermesgadget/talaria/core/notifications/AgentTaskNotificationService.kt:85` | The long-lived data-sync FGS correctly handles timeout but still stops after Android 15's six-hour quota; start failures are swallowed. | Keep watches bounded/resumable, expose timeout/start failure, and test FGS quota/start restrictions on Samsung/OnePlus/Pixel. |
| **Medium** | `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatScreen.kt:163` | Fixed 600dp width adaptation has no fold/hinge posture awareness and can disagree with root adaptive navigation. | Share `WindowAdaptiveInfo`; use folding features/list-detail panes and compact-height behavior. |
| **Low** | `app/src/main/AndroidManifest.xml:8` | `RECORD_AUDIO` implicitly filters out devices without a microphone even though voice is optional. | Declare `android.hardware.microphone` with `required=false`; retain runtime capability/permission checks. |
| **Low** | `app/build.gradle.kts:43` | Combining AAOS support into the phone module raises minSdk to 29 and excludes Android 9 phones that Android Auto still supports. | Split the automotive artifact; consider minSdk 28 for the phone projection artifact. |
| **Low** | `app/src/main/res/xml/quick_entry_widget_info.xml:5` | Fixed 4x1, horizontal-only quick widget has weak compatibility with narrow OEM launcher grids. | Provide responsive Glance layouts/size modes, smaller minimums where usable, vertical resize, and localized labels. |
| **Low / forward** | `app/src/main/AndroidManifest.xml:5` | No declared/requested local-network permission path exists for Android's forthcoming LAN enforcement. | Test target 36 with `RESTRICT_LOCAL_NETWORK` now and implement the final permission contract once enforced/documented. |

### Overall release gate

Do not call Android Auto projection compatible until all of these pass: descriptor present in the packaged APK; DHU launches the Car API 7 conversation template; Android Auto messaging notifications provide reply and mark-read; a trusted Internal/Closed build appears in at least Pixel, Samsung, and OnePlus real-car tests; and replies survive Doze/offline recovery. AAOS should be tracked as a separate artifact, not inferred from the current trampoline.
