# Talaria Android Auto / Car Experience — Implementation Plan

**Status:** ACTIVE — implementation started 2026-08-03
**Last updated:** 2026-08-03
**Owner:** Ben + Hermes
**Distribution model:** APK-only via GitHub releases — **no Play Store** (this is a hard constraint, see [Distribution](#distribution-model))

## 0. Locked decisions (Ben, 2026-08-03)

| Decision | Value |
|---|---|
| Target version | **v0.8.0** (next version after v0.7.0) |
| Sessions shown in car | **Active sessions only** (`end_reason == null && ended_at == null`, non-automation) — not historical ones |
| Create agent | **"New agent" button in the car UI** — voice prompt starts a fresh session |
| Input model | **Voice-first** — dictation (`requestInput(VOICE)`) is the primary input; no keyboard in the car |
| Notifications | **Unchanged** — existing Talaria notifications surface on Android Auto as normal notifications; no MessagingStyle conversion work |
| Emulator testing | Android Automotive OS AVD (`talaria_auto`, android-34-ext9 automotive x86_64) |
| Delegation | MissionDeck agents allowed if needed; Hermes monitors in-session |

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

- [ ] Download `system-images;android-34-ext9;android-automotive;x86_64` via sdkmanager
- [ ] Create `talaria_auto` AVD (Automotive OS head-unit emulator)
- [ ] Boot it headless (KVM, `-no-window`), confirm `sys.boot_completed`
- [ ] Confirm we can install an APK and screencap it
- **Exit criteria:** automotive AVD boots and installs APKs; we have a repeatable test loop.

### Phase 1 — MessagingStyle notifications (1 day)

The immediate car experience, no car-app code needed. Reuses `AgentTaskNotificationService`.

- [ ] Convert agent task notifications to `MessagingStyle` (title = agent/session, text = latest response)
- [ ] Add `CarAppExtender` (unread count, conversation ID)
- [ ] Add reply action with `RemoteInput` → existing `ReplyWorker` path (verify it survives the car round-trip; Android Auto delivers replies via the notification action)
- [ ] Verify on emulator: notification appears with AA-style actions
- **Exit criteria:** an agent message triggers a car-readable notification; replying from it sends a message to the agent.

### Phase 2 — CarAppService skeleton (1 day)

- [ ] Add dependency `androidx.car.app:app-automotive` (+ version catalog entry)
- [ ] Create `TalariaCarService : CarAppService`
- [ ] Manifest: service + intent filter (`androidx.car.app.CarAppService` action, `androidx.car.app.category.MESSAGING` category) + `androidx.car.app.minCarAppApiLevel` metadata
- [ ] Wire minimal `SessionListScreen` (empty state) so the service is discoverable
- [ ] Test on `talaria_auto` AVD: app appears in car launcher, empty list renders
- **Exit criteria:** car app launches on the automotive emulator and shows a session list skeleton.

### Phase 3 — Session list (2 days)

- [ ] Map Hermes `SessionSummary` → car conversation rows (title, source platform, unread indicator, active badge)
- [ ] Reuse `ProfileRegistry`/`HermesApi` session fetch (already built for the app) — a thin `CarSessionsRepository` wrapper
- [ ] `ListTemplate` with `ItemList` (driver-safe: title + subtitle, no dense metadata)
- [ ] Filter: show only non-automation, active/ended sessions (same `SessionFilters.matchesTab` logic as the app)
- [ ] Tap → open `MessageTemplate` conversation view
- **Exit criteria:** real sessions from the connected Hermes instance render in the car list.

### Phase 4 — Message view + voice compose (2 days)

- [ ] `MessageTemplate`-based conversation screen (or `ListTemplate` fallback for message history if template limits bite)
- [ ] Voice compose: template input callback → dictation → send prompt to agent via existing Hermes API path (reuse `HermesRepository`/chat send)
- [ ] Show streaming/working state (agent is "thinking") using existing event/streaming plumbing where feasible
- [ ] "New message" action to start a fresh session
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
androidx-car = "1.8.0"   // pin actual latest at implementation time
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

## 9. Open questions for Ben

1. Target version number for the car release — `v0.8.0`? (Current: v0.7.0 released.)
2. Which sessions should appear in the car — all non-automation sessions, or only currently-active ones?
3. Should the car list show a "start new agent" voice flow, or only existing sessions?
4. Priority: Phase 1 (notifications, quick win) before Phase 2+ (car app), or build the car app first?
