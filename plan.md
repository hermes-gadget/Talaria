# Talaria Android Auto / Car Experience — Implementation Plan

**Status:** CURRENT — car implementation through Phase 4 is recorded complete; notification polish and release validation are deferred
**Last updated:** 2026-08-03 (status reconciliation and repository evidence)
**Owner:** Ben + Hermes
**Distribution model:** APK-only via GitHub releases — **no Play Store** (this is a hard constraint, see [Distribution](#distribution-model))

## Authoritative status

This is the single source of truth for phase status. Runtime claims in the
evidence record below were recorded on 2026-08-03; this audit zone did not
launch an emulator, AVD, device, `adb`, or instrumentation test.

### Completed

- [x] **Phase 0 — toolchain validation:** the prior evidence record documents
  a capped `talaria_auto` AAOS boot/install/screencap loop. It is not rerun here
  because emulator and device execution are prohibited for this worktree.
- [x] **Phase 2 — car-app skeleton:** `TalariaCarService`, the car intent
  filter, application `minCarApiLevel`, and `SessionListScreen` are present in
  `AndroidManifest.xml`, `car/TalariaCarService.kt`, and
  `car/SessionListScreen.kt`.
- [x] **Phase 3 — active session list:** `CarSessionsRepository.activeSessions`
  filters end markers and automation sources, and the car list consumes that
  projection; the evidence record says the real list rendered.
- [x] **Phase 4 — voice-first agent creation:** the car repository exposes
  `createSession`/`sendText`; the evidence record says a quick-start prompt
  created a server session and the run survived socket close via an attach token.
- [x] **PTY startup ordering:** the current car and notification paths wait for
  first PTY output, apply the 350 ms settle interval, and use an attach token.

### Current

- [ ] **Phase 1 — notification presentation:** existing Talaria notifications
  remain the Android Auto notification surface; MessagingStyle/CarAppExtender
  conversion and a car round-trip check are not landed.
- [ ] **Phase 4 residual — live response state:** the car conversation path has
  creation and history delivery, but a live streaming/"thinking" indicator is
  not evidenced in the current repository.
- [ ] **Phase 5 — notification pairing and playback polish:** unread pairing,
  duplicate-tab handling, offline UX, and the full notification/car loop remain.

### Deferred

- [ ] **Phase 6 — release validation:** real-car testing, optional App Host
  validation, screenshots, and the `v0.8.0` tag are deferred to Ben's release
  decision. No emulator/device validation is authorized in this audit zone.
- [ ] **Play Store/developer-program work:** remains out of scope under the
  APK-only distribution decision.

## 0. Locked decisions (Ben, 2026-08-03)

| Decision | Value |
|---|---|
| Target version | **v0.8.0** (next version after v0.7.0) |
| Sessions shown in car | **Active sessions only** (`end_reason == null && ended_at == null`, non-automation) — not historical ones |
| Create agent | **"New agent" button in the car UI** — voice prompt starts a fresh session |
| Input model | **Voice-first** — dictation (`requestInput(VOICE)`) is the primary input; no keyboard in the car |
| Notifications | **Unchanged** — existing Talaria notifications surface on Android Auto as normal notifications; no MessagingStyle conversion work |
| Emulator testing | Prior AAOS evidence is retained above; no emulator/device execution is permitted in this audit zone |
| Delegation | MissionDeck agents allowed if needed; Hermes monitors in-session |

**Historical evidence record (2026-08-03):** ✅ VERIFIED on the AAOS emulator (capped
boot, no crash): car launcher → CarAppActivity → template host renders
the real session list (live sessions from the Hermes dashboard, "New
agent" mic entry, active-only filtering). Fixes: CarAppActivity
declaration + MainActivity `FEATURE_AUTOMOTIVE` hand-off; minCarApiLevel
metadata on `<application>` (key is `androidx.car.app.minCarApiLevel`,
host throws otherwise); `minCarApiLevel` 7 for ConversationItem. Also
added debug hook `--ez force_phone_ui true` to reach the phone UI on
AAOS (used to configure the connection on the emulator — SESSION_TOKEN
auth auto-mints against the local dashboard via `adb reverse`).

**Historical evidence record — agent creation (2026-08-03):** car list has a
"Create new agent" entry (distinct + avatar, voice via framework mic)
plus 3 one-tap quick-start actions. Verified on the AAOS emulator:
tapping a quick action created a real session on the Hermes dashboard
("Plan today's work based on my recent activity.") and the agent's run
continued after the app closed its socket. Three stacked fixes were
required:
1. **Server: PTY spawn re-ran `npm install` on every connection** —
   `_tui_need_npm_install` compares the whole-workspace lockfile (incl.
   never-installed `apps/desktop` deps) against the installed state, so
   it always reinstalled (~40-60s) and prompts were dropped. Workaround:
   move `hermes-agent/package-lock.json` aside (prebuilt-bundle mode:
   `ui-tui/dist/entry.js` exists → install skipped → TUI spawns in ~2s).
   Also unbreaks the dashboard's own /chat tab (broken since ~Jul 31).
2. **App: send only after the TUI banner** — an immediate send races the
   fresh TUI process and the prompt is silently dropped (PTY buffer does
   NOT reliably hold it through TUI startup). Wait for first Output, +
   350ms settle, then send.
3. **App: keep-alive attach token (`?attach=<uuid>`)** — the legacy
   path kills the PTY on socket close, so the agent's run died right
   after it started. With attach, the server's PTY registry keeps the
   TUI + run alive (30-min TTL reaper). Applied to the car repository
   AND `ReplyWorker` (same bug class — notification replies were killing
   runs too).
Plus: catch-order fix in `ptySend` (PtySendDone is a CancellationException
and was swallowed by the generic catch → every successful send reported
"Failed to start agent"); Hermes message timestamps are epoch SECONDS →
×1000 for the car API (was rendering garbage hex time labels).
Pending: v0.8.0 tag (Ben's call — release discipline: never tag
unprompted).

---

## 1. Goal

Run Talaria in the car via Android Auto so Ben can:

1. **Voice-input prompts to agents while driving** — "Hey, ask Hermes to draft a release note for v0.7" without touching the screen.
2. **Hear agent output** — responses read aloud through the car's audio system (TTS).
3. **See active agent conversations** — a driver-safe list of sessions, tap to view, no dense text, no keyboard.
4. **Stay safe** — everything must comply with Android Auto's driver-distraction templates (large touch targets, voice-first, minimal glances).

The driving scenario: Ben has an idea while driving → speaks it → the agent picks it up → the response comes back read aloud (and is visible in the app / Talaria on his phone when he parks).

## 2. Background / what we already know

- Talaria is a Kotlin/Jetpack Compose Android client (`com.hermesgadget.talaria`, debug variant `com.hermesgadget.talaria.debug`) for the self-hosted Hermes agent.
- It already has: multi-tab PTY chat, session management, `AgentTaskNotificationService` (actionable, thread-aware notifications, 10 channels, quiet hours, per-agent channels), voice dictation/TTS, `ReplyWorker` (handles notification reply intents → sends messages to agents), Room cache, multi-profile registry.
- The Hermes backend exposes a session list API with `end_reason`/`ended_at`/`is_active` — Talaria already uses these for auto-open/auto-close of agent tabs.
- **Key research finding (2026-08-03):** Android Auto *does not require Play Store distribution* for sideloaded APKs. The Play-Store beta gate on templated messaging apps is a distribution policy, not a device-side technical restriction. A sideloaded APK declaring a `CarAppService` with the `MESSAGING` category **will appear in Android Auto** on a phone-projected head unit.

## 3. Distribution model (hard constraints)

| Constraint | Detail |
|---|---|
| No Play Store | APK only, distributed via GitHub releases (existing CI flow: tag `v*` → build → attach to release) |
| Works sideloaded | Manifest declares car service; Android Auto discovers it via intent filter, not store presence |
| Real-world target | Phone-projected Android Auto (head unit or "AA for phone screens") — **not** Android Automotive OS built-in units (those can't sideload; out of scope) |
| No developer-program dependency | We can build + test without joining Google's Android Auto developer program. (Joining it later would only be needed for the App Host test APK or future Play distribution — both optional.) |

## 4. Architecture overview

Two complementary layers, designed as a pair (Google's own model):

```
┌─────────────────────────────────────────────────────────┐
│  Layer 1: MessagingStyle notifications (works today)    │
│  - Agent responses → car TTS read-aloud                 │
│  - Voice replies via RemoteInput → ReplyWorker → agent  │
│  - No approval, no car app, no template work            │
├─────────────────────────────────────────────────────────┤
│  Layer 2: CarAppService templated app (new build)       │
│  - "Real app" icon in the car's app grid                │
│  - Session list (conversations) + message view          │
│  - Voice compose → agent                                │
│  - Sideloadable APK, no Play gate                       │
└─────────────────────────────────────────────────────────┘
```

- Layer 1 gives the *alerts + read-aloud* channel.
- Layer 2 gives the *browsable in-car UI*.
- New-message read-aloud on Android Auto comes through the notification channel (Layer 1), so Layer 2 alone is not sufficient for the full "agent speaks to you" experience. **Both layers ship.**

## 5. Work plan (phased)

### Phase 0 — Toolchain validation (quick, do first)

- [x] Download `system-images;android-34-ext9;android-automotive;x86_64` via sdkmanager (historical evidence; do not rerun in this zone)
- [x] Create `talaria_auto` AVD (historical evidence; do not rerun in this zone)
- [x] Boot it headless (KVM, `-no-window`), confirm `sys.boot_completed` (historical evidence; do not rerun in this zone)
- [x] Confirm we can install an APK and screencap it (historical evidence; do not rerun in this zone)
- **Exit criteria:** automotive AVD boots and installs APKs; we have a repeatable test loop.

### Phase 1 — MessagingStyle notifications (1 day)

The immediate car experience, no car-app code needed. Reuses `AgentTaskNotificationService`.

- [ ] Convert agent task notifications to `MessagingStyle` (title = agent/session, text = latest response)
- [ ] Add `CarAppExtender` (unread count, conversation ID)
- [ ] Add reply action with `RemoteInput` → existing `ReplyWorker` path (verify it survives the car round-trip; Android Auto delivers replies via the notification action)
- [ ] Verify on emulator: notification appears with AA-style actions
- **Exit criteria:** an agent message triggers a car-readable notification; replying from it sends a message to the agent.

### Phase 2 — CarAppService skeleton (1 day)

- [x] Add dependency `androidx.car.app:app-automotive` (+ version catalog entry)
- [x] Create `TalariaCarService : CarAppService`
- [x] Manifest: service + intent filter (`androidx.car.app.CarAppService` action, `androidx.car.app.category.MESSAGING` category) + `androidx.car.app.minCarAppApiLevel` metadata
- [x] Wire minimal `SessionListScreen` (empty state) so the service is discoverable
- [x] Test on `talaria_auto` AVD: app appears in car launcher, empty list renders (historical evidence; no rerun in this zone)
- **Exit criteria:** car app launches on the automotive emulator and shows a session list skeleton.

### Phase 3 — Session list (2 days)

- [x] Map Hermes `SessionSummary` → car conversation rows (title, source platform, unread indicator, active badge)
- [x] Reuse `ProfileRegistry`/`HermesApi` session fetch (already built for the app) — a thin `CarSessionsRepository` wrapper
- [x] `ListTemplate` with `ItemList` (driver-safe: title + subtitle, no dense metadata)
- [x] Filter: show only non-automation, active sessions (same source classification as the app)
- [x] Tap → open `MessageTemplate` conversation view
- **Exit criteria:** real sessions from the connected Hermes instance render in the car list.

### Phase 4 — Message view + voice compose (2 days)

- [x] `MessageTemplate`-based conversation screen (or `ListTemplate` fallback for message history if template limits bite)
- [x] Voice compose: template input callback → dictation → send prompt to agent via existing Hermes API path (reuse `HermesRepository`/chat send)
- [ ] Show streaming/working state (agent is "thinking") using existing event/streaming plumbing where feasible
- [x] "New message" action to start a fresh session
- **Exit criteria:** voice dictation sends a prompt to an agent from the car UI; response renders in the conversation.

### Phase 5 — Notification pairing + playback polish (1 day)

- [ ] Confirm read-aloud works: Layer-1 notification + Layer-2 conversation agree on the same session
- [ ] Unread counts flow: notification unread → car conversation badge
- [ ] Handle "reply from car" → same thread, no duplicate tabs
- [ ] Connection-state UX: car shows "offline / reconnect" instead of a blank list
- **Exit criteria:** end-to-end loop — agent responds → car announces it → voice reply → agent responds again.

### Phase 6 — Verification & release (ongoing)

- [ ] Automotive-emulator E2E: install APK, connect to Hermes (adb reverse), voice round-trip, screenshots (landscape, dark)
- [ ] Optional: Android Auto App Host test (requires Google developer-program sign-in — only if we want the exact phone-projection rendering; **not a blocker**)
- [ ] Real-world: Ben sideloads APK from GitHub release, tests in his car
- [ ] Tag `v0.8.0` (or next) → CI builds APK → GitHub release (existing flow, **no Play**)
- **Exit criteria:** Ben drives with it.

## 6. Technical reference (for implementation)

### 6.1 Dependencies

```kotlin
// libs.versions.toml
androidx-car = "1.7.0"   // matches the current app dependency/catalog pin
car-app = { id = "androidx.car.app:app-automotive", version.ref = "androidx-car" }
```

### 6.2 Manifest declaration

```xml
<service
    android:name=".car.TalariaCarService"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.car.app.CarAppService" />
        <category android:name="androidx.car.app.category.MESSAGING" />
    </intent-filter>
</service>
<meta-data
    android:name="androidx.car.app.minCarAppApiLevel"
    android:value="2" />
```

### 6.3 Template surface (proposed)

| Screen | Template |
|---|---|
| Session list | `ListTemplate` + `ItemList` |
| Conversation | `MessageTemplate` (single-message focus) or `ListTemplate` for history |
| Compose | Template input callback (voice) — never a keyboard |
| Error/offline | `MessageTemplate` with retry action |

### 6.4 Voice flow

```
[mic] → dictation text (head unit) → InputCallback
      → HermesRepository.sendMessage(sessionId, text)   [existing path]
      → response → notification (Layer 1) → car TTS read-aloud
      → response also renders in conversation (Layer 2)
```

### 6.5 Reuse map (things that already exist in Talaria)

| Need | Existing asset |
|---|---|
| Session fetch | `ProfileRegistry.refresh()`, `HermesApi.getSessionsForProfile()` |
| Send message | `HermesRepository` (chat send path used by `ReplyWorker`) |
| Session filtering | `SessionFilters.matchesTab()` (Chats vs Automation) |
| Notifications | `AgentTaskNotificationService` + `PersistedAgentWatch` |
| Reply intent | `ReplyWorker` |
| Connection state | `ConnectionStore`, connection doctor |

## 7. Risks & limitations (honest)

1. **Read-aloud requires Layer 1.** Android Auto's message playback is notification-driven; the templated app alone can't inject TTS. Mitigation: both layers ship together (planned).
2. **No custom wake word / continuous voice.** Assistant-style always-on voice is reserved for Google Assistant. Mitigation: standard AA voice affordances (mic button, "reply" command) cover the flow.
3. **AAOS emulator ≠ phone projection.** The automotive AVD validates templates/voice but not the phone↔head-unit handshake. Mitigation: real-car test with sideloaded APK is the final gate (Phase 6).
4. **Category fit.** Google's messaging category targets human-to-human comms; an agent-chat app may not pass a future Play review. **Irrelevant for sideloading** — noted only in case we ever change distribution.
5. **Templated messaging is beta on Play.** Same as above — Play-only concern, no impact on GitHub APK distribution.
6. **Driver distraction rules are enforced by the head unit.** We must stick to provided templates; custom/complex layouts may be rejected by the host at runtime.

## 8. Out of scope (for now)

- Android Automotive OS (built-in head units) — cannot sideload; would need OEM/store channels
- Play Store distribution / developer-program enrollment (unless Ben later wants it)
- Custom TTS voices or wake words
- Media/navigation car categories

## 9. Remaining release decisions

1. Ben decides when to tag `v0.8.0` (or a later release) after Phase 1, the
   Phase 4 residual, and Phase 5 are complete.
2. Ben decides whether to add optional phone-projection/App Host validation;
   it is not required for the APK-only distribution model.
