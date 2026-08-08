# Talaria — next roadmap

This roadmap starts from the verified state at `main@6366ace` (2026-08-08). R0.1–R0.5 and R0.7/R0.10/R0.11 closures are merged; R0.8 (N0.7) and R0.9 (N0.9) are not implemented; the N0.12 harness is not complete. It folds the audit's new defects into the existing R1 direction without treating commit messages as acceptance evidence.

Ordering rules:

1. Keep release and authorization quick wins small and first.
2. Finish origin, snapshot, ticket, generation, and recovery invariants before adding new background/action surfaces.
3. Enforce budgets at ingress/ownership boundaries before performance polish.
4. Add executable acceptance coverage with each item; R0.11 is not a later cleanup bucket.
5. Extract facades before Gradle modules; move vertical slices rather than performing a big-bang rewrite.

## Landed since the previous baseline (verified 2026-08-08)

| Item | Status | Evidence |
| --- | --- | --- |
| N0.1 — Fail-closed validation and tag release | ✅ merged | `99ef0f2` (Wave 0) + `.github/workflows/apk-release.yml` verified: read-only `pr-gate` on PR/main, tag-only signed build, strict `vMAJ.MIN.PATCH(-rcN)` parse with monotonic versionCode + legacy floor, `apksigner verify` + fingerprint/package/version checks before upload, actions pinned by SHA, wrapper checksum + `validateDistributionUrl` set |
| N0.2 — Exact, informed approval choices | ✅ merged | `99ef0f2` (Wave 0). `ApprovalChoicePolicy` (safe one-shot allowlist, fail-closed resolution, broad-confirm requirement), `ChatViewModel.respondPrompt` resolves only the exact tapped value and errors when no explicit choice resolves, `ChatScreen` renders per-choice buttons with a confirm step for broad values. Policy tests: broad-first/once-first/unknown/deny/empty/server-offered cases. **Open acceptance gap:** expired/stale-session/duplicate-tap cases at the ViewModel/RPC boundary are untestable without a transport-injection seam — deferred to N1.10 (matches the existing `SessionAutoOpenOwnershipBehaviorTest` convention) |
| N0.3 — Exact-origin cleartext consent (R0.1) | ✅ merged | `43139f7` (+ `b5abdc1` in-chat re-approval after fail-closed migration) |
| N0.4 — Snapshot-bound operation + credential bootstrap (R0.3) | ✅ merged | `12ed612` (+ `29917ac` Auto message-center cleanup) |
| N0.5 — Independent WS auth + generation ownership (R0.2) | ✅ merged | `c784b0d` |
| N0.6 — Loss-aware bounded ingress + cancellation (R0.4) | ✅ merged | `59a15eb` |
| N0.8 — Identity-safe feature mutations + lifecycle cleanup | ✅ merged | `99ef0f2` (S slices) |
| N0.10 — Untrusted-content and media budgets (R0.7) | ✅ merged | `355fca3` (+ `3ebcc0d` injectable dispatchers) |
| N0.11 — Transcript deletion/reconciliation (R0.5) | ✅ merged | `f5de928` (agent 467882a) |
| R1.8 — Capture/attachment pipeline | ✅ merged early | `4520492` (agent b4c05dc); tests: `ShareIntakePolicyTest`, `ShareIntentParserTest`, `ShareIntakeStoreTest`, `ShareIntakeDeliveryTest`, `ShareFileManagerTest` |
| R1.9 — Session labels/groups/favorites | ✅ merged early | `90fbec5` (agent 5cb2363, verification pending → verified 2026-08-08 unit run) — no dedicated labels tests yet, covered by suite + manual check |

**Remaining P0:** N0.7 (typed secure-store recovery), N0.9 (owned files/backups/caches), N0.12 (trustworthy P0 harness). **Next after P0:** Wave 3 lifecycle/delivery (N1.1–N1.6).

## P0 — close current safety and release contracts

### N0.1 — Fail-closed validation and tag release

**Problem:** Tag publishing is independent of the validation job, signed assembly runs on PR/main/manual events, permissions are global, signature verification is masked, and prerelease version codes can move backwards.

**Effort:** **S/M**. **Dependencies:** none.

**Acceptance criteria:**

- PR/main validation needs no signing secret and has read-only minimum permissions.
- Signed assembly/release runs only for a strictly parsed supported version tag and depends on successful validation.
- `apksigner verify` fails the job; package ID, version name/code, signer fingerprint, artifact SHA-256, and expected output are checked before upload.
- Stable and prerelease version-code fixtures are strictly monotonic; malformed/out-of-range tags fail.
- Actions are pinned by commit and the wrapper distribution checksum is configured.

### N0.2 — Exact, informed approval choices

**Problem:** Chat's generic Approve submits the first non-deny server choice and can silently choose `always` or another broad authorization.

**Effort:** **S/M**. **Dependencies:** none.

**Acceptance criteria:**

- Every approval button names the exact consequence and submits the exact selected value.
- A safe one-shot value is allowlisted; unknown, ambiguous, reordered, or broad-only choices fail closed or require explicit broad confirmation.
- Tests cover broad-first, once-first, unknown, deny, expired, stale-session, and duplicate-tap cases through the production ViewModel/RPC boundary.

### N0.3 — Complete exact-origin cleartext consent (R0.1 closure)

**Problem:** Consent is a reusable Boolean; legacy missing consent becomes approved; transport ignores the recorded decision; doctor/OIDC bypass the warning; badge/revocation and acceptance fixtures are missing.

**Effort:** **M**. **Dependencies:** none; precedes N1.7 and N2.1 roaming work.

**Acceptance criteria:**

- Persisted default and legacy migration are false/undecided, not approved.
- Approval is bound to normalized scheme, host, and effective port; URL/origin/HTTPS edits invalidate it atomically.
- Save, test, doctor, password bootstrap, OIDC, REST, redirects, and WebSockets enforce the same snapshot decision.
- The connection UI shows a persistent unencrypted-HTTP badge and revoke action.
- Range/spoof/redirect/restart/revoke/legacy/HTTPS tests cover full `127/8`, RFC1918, CGNAT, ULA, link-local, public/DNS/malformed cases.

### N0.4 — Snapshot-bound operation and credential bootstrap (R0.3 closure)

**Problem:** Multi-step repository calls can change destination between phases; bootstrap redirects lack origin guards; password/OIDC checks are TOCTOU; OIDC can transmit a refresh result after failed CAS; pairing notification construction rereads active state.

**Effort:** **M**. **Dependencies:** N0.3 for cleartext bootstrap behavior.

**Acceptance criteria:**

- Every multi-step public operation captures one snapshot/API/scope and uses it for mutation, polling, reread, persistence, notification, and invalidation.
- Password/OIDC bootstrap either disables redirects or applies the same per-hop origin policy as the main client.
- Exact-current checks run inside auth single-flight and immediately before transmission; failed CAS returns no token.
- Pairing/reply notification extras are built only from the captured target.
- Deterministic A→B tests cover every auth mode and each OIDC/worker/car/repository phase; two-server 307/308 tests receive no credential body.

### N0.5 — Independent WebSocket auth and generation ownership

**Problem:** One single-use ticket opens two sidecar sockets; reconnect can reuse fixed tickets; old callbacks/replay/queue entries can cross connection generations.

**Effort:** **M**. **Dependencies:** N0.4.

**Acceptance criteria:**

- `/api/events`, `/api/ws`, and every reconnect mint independent auth immediately before opening while retaining one fixed snapshot.
- Every callback and queued/replayed event carries and checks socket identity, generation, and captured scope.
- Stop/start drains or replaces old-generation ingress/replay and late A events never reach B UI/activity/notifications.
- Gated fake-server tests prove two initial tickets, remint on reconnect, loopback token reuse, and no cross-generation delivery.

### N0.6 — Loss-aware bounded ingress and cancellation (R0.4 closure)

**Problem:** Critical and replaceable events share drop-any queues; PTY/sidecar frames are uncapped before conversion/parse; answer caps overshoot; broad catches swallow cancellation.

**Effort:** **M**. **Dependencies:** N0.5.

**Acceptance criteria:**

- Prompt, completion, connection, request-boundary, and delivery events are lossless/ordered within a measured bound; status/catalog/progress/deltas coalesce under explicit policy.
- Text/binary limits are enforced before UTF-8/JSON/ANSI/UI work where possible; oversize closes 1009.
- Answer buffers append only remaining capacity, retain a diagnostic tail, and display truncation; drop/high-water diagnostics contain no payload.
- All suspend catch paths rethrow `CancellationException`; canceled work publishes neither error nor late state.
- Burst, ordering, cancellation, text/binary boundary, and counter tests are deterministic.

### N0.7 — Typed secure-store recovery (R0.8)

**Problem:** Eager Keystore construction can crash startup; decode corruption silently becomes empty profiles/credentials; no safe recovery flow exists.

**Effort:** **M**. **Dependencies:** none; N1.12 builds on the interface.

**Acceptance criteria:**

- Store exposes `Available`, `RecoverableCorruption`, and `PermanentKeystoreLoss` with non-secret diagnostics.
- Raw encrypted state is preserved; no request substitutes empty credentials; no automatic empty-store recreation occurs.
- Recovery UI offers Retry, Copy diagnostics, and a documented confirmed reset scoped only to encrypted connections.
- Healthy released formats survive unchanged; corrupt profile JSON, secret ciphertext, Keystore invalidation, restart, and interrupted reset have executable fixtures.

### N0.8 — Identity-safe feature mutations and lifecycle cleanup

**Problem:** Learning detail loads can overwrite another selected node; Kanban/Cron/session operations have stale-result or semantic-success gaps; Voice can record off-screen.

**Effort:** **S/M**. **Dependencies:** use N0.6 cancellation conventions.

**Acceptance criteria:**

- Learning, Kanban board/task/run, and comparable identity loads cancel prior work and apply results only to matching IDs/generations.
- All mutations serialize/reject duplicates, validate semantic result payloads, and reload only after acknowledgement.
- Bulk session deletion refreshes after completion, never before.
- Voice recording, recognition, and playback stop on route/process `ON_STOP`; cloud-capable recognition requires honest disclosure/opt-in.
- Late-A/duplicate/close/background tests exercise production owners with `StandardTestDispatcher`.

### N0.9 — Owned, bounded files, backups, and caches (R0.9 closure)

**Problem:** Managed downloads have no byte quota or process-death sweep; backup duplicates bytes in memory and has no generation owner; in-memory caches retain expired/path/session/profile keys.

**Effort:** **M**. **Dependencies:** reuse one expanded ShareFileManager.

**Acceptance criteria:**

- Downloads/backups enforce declared and streamed limits, write to owned partials, delete on failure/cancel/supersession, and expose progress.
- One job/generation owns each operation; stale completion cannot replace current state.
- Share/cache managers adopt streams/files, enforce TTL/count/weight, and sweep after process restart without deleting chooser-owned files early.
- Response/transcript/profile caches use weighted LRU/expiry and prune deleted scope/state.
- Duplicate/oversize/cancel/process-death/TTL tests leave no orphan or stale UI.

### N0.10 — End-to-end untrusted-content and media budgets (R0.7 closure)

**Problem:** Artifact structural depth resets; Markdown is recursively unbounded; image/attachment paths retain raw/Base64/JSON/data-URL/bitmap copies and lack decoded-pixel/aggregate limits.

**Effort:** **M/L**. **Dependencies:** N0.6 ingress conventions and N0.9 owned files.

**Acceptance criteria:**

- Artifact and Markdown parsers cap input bytes, structural/inline depth, nodes, candidates, blocks, and nested decodes with iterative traversal where practical.
- Extraction/sorting is off-main, incremental, revision-cached, and discards full transcripts promptly.
- Images are probed off-main, reject excessive decoded pixels, downsample/re-encode to display/transport budgets, and are represented by handles rather than data URLs in UI state.
- Attachment peak is bounded by aggregate and one-at-a-time sending; rejected content has safe download/link alternatives.
- Depth 16/17, structural/string alternation, node/byte/candidate overflow, huge dimensions, cancellation, and 50-session tests terminate within budgets.

### N0.11 — Complete transcript deletion/reconciliation (R0.5 closure)

**Problem:** Lifecycle/fingerprint/atomic replacement is implemented, but session deletion/pruning leaves cached message rows and the five-tab/transaction acceptance path is untested.

**Effort:** **M**. **Dependencies:** N0.6.

**Acceptance criteria:**

- Explicit delete, prune, and authoritative server removal atomically remove session and transcript rows via cascade or cross-DAO transaction.
- Matching indices support hot session/message/activity predicates and large deletions are chunk-safe.
- Five tabs cause zero background/inactive polling; completion causes one scoped refresh; unchanged payload causes zero DAO writes.
- Failure between replacement steps preserves last-good data and resume refreshes immediately in Room-backed deterministic tests.

### N0.12 — Trustworthy P0 regression harness (R0.11 closure)

**Problem:** The fast lane exists but critical operation owners are private/global; network reads can hang; there is no Room/secure-store/Compose/car/worker/device gate or report retention.

**Effort:** **M**. **Dependencies:** seams land with N0.2–N0.11.

**Acceptance criteria:**

- PR validation runs unit, timeout-safe network integration, lint, release-like/minified manifest compile, coverage/report generation, and always uploads failures without signing secrets.
- `takeRequest`/socket waits are bounded; race tests default to `StandardTestDispatcher` and virtual time.
- Room migration/transaction, secure store, auth redirect/snapshot, event sockets, PTY recovery/delivery, workers, notification intents, real car service/validator, and share-file lifecycle use production seams.
- A small managed-device shard covers merged security/backup manifest, deep links/shares, car/notification, and Compose connection/chat smoke at API 29 and target API.

## P1 — lifecycle, delivery, and visible product work

### N1.1 — One lifecycle owner and lazy active-tab restoration (R1.1)

**Problem:** Restored tabs and independent process/service/watch surfaces create multiple PTY/RPC/event owners and off-screen work.

**Effort:** **L**. **Dependencies:** N0.5, N0.6, N0.11.

**Acceptance criteria:** N restored tabs create one active runtime; inactive tabs remain cached/dormant; one foreground subscriber hands off to one scoped background owner with no notification gap/duplicate; selecting hydrates once and stop closes exactly the intended producers.

### N1.2 — Coalesced chat state and chunked terminal rendering (R1.2)

**Problem:** Per-frame broad-state/string copies, stateless terminal re-strip, one 120k Text node, forced autoscroll, and repeated linear searches cause jank and corrupt split ANSI.

**Effort:** **M/L**. **Dependencies:** N0.6, benefits from N1.1.

**Acceptance criteria:** One stateful parser produces equal final text across split sequences; visible snapshots publish at frame/50–100 ms cadence; terminal uses a bounded chunk/line ring and keyed LazyColumn; Chat follows only near bottom; search/index maps are one-pass; release-like p95/p99/missed-frame metrics improve.

### N1.3 — Delivery-grade workers, notifications, and foreground watches (R1.3)

**Problem:** Reply work lacks stable unique identity/constraints and durable status; FGS start/quota failures and watch caps are not surfaced.

**Effort:** **M**. **Dependencies:** N0.4, N0.5; integrate N1.5 state when available.

**Acceptance criteria:** Connected/expedited unique work delivers exactly once; pre-accept retries and post-accept does not; Queued/Delayed/Failed persists visibly; FGS start/Android 15 quota states surface; watch runtime is thread-confined, capped, resumable, and never retargeted by a scope switch.

### N1.4 — Complete Android Auto messaging and qualification (R1.4)

**Problem:** Trust/descriptor/screens exist, but observed OEM enrollment is not reachable, mark-read is a no-op, MessagingStyle alignment and real-car Doze/route qualification are absent.

**Effort:** **M** plus device QA. **Dependencies:** N0.4, N1.3.

**Acceptance criteria:** Safe identity observation discloses no transcript/action before enrollment; MessagingStyle content/reply/mark-read uses stable conversation identity; unknown hosts remain denied; DHU and supported signed distribution cover locked/cold/Doze/offline/route loss on documented OEMs with host identity/version/cert evidence.

### N1.5 — Durable Agent Attention Inbox (R1.5)

**Problem:** Prompts, failures, and completions depend on ephemeral notifications and cannot form one idempotent scoped action record.

**Effort:** **M**. **Dependencies:** N0.4; define contract before N1.6.

**Acceptance criteria:** Room records are keyed by connection/profile/session/request/instance; notification clearing does not delete them; replay creates one record; exact allowed actions resolve once; expired/stale requests cannot execute; sudo/secret/broad/ambiguous actions remain phone-only or explicit.

### N1.6 — Bounded persisted dashboard event spine (R1.6)

**Problem:** Session/attention/tool/artifact/cron/connection state is split across sockets, services, widgets, and polls.

**Effort:** **M/L**. **Dependencies:** N0.5, N0.6, N0.11, N1.1, N1.5.

**Acceptance criteria:** One server transition yields one idempotent scoped state across inbox/widget/car; cursor/replay survives restart; gaps reconcile; old servers use lifecycle-aware conditional polling; socket/queue/persistence/poll budgets are measured and enforced.

### N1.7 — Capability-driven staged onboarding (R1.7)

**Problem:** The connection screen remains an expert form and exposes protocol/auth/provider complexity before route/server classification.

**Effort:** **M**. **Dependencies:** N0.3, N0.4.

**Acceptance criteria:** Progressive route→URL→TLS/HTTP→public/protected probe→advertised auth→profile/provider→completion stages support loopback/LAN/Tailscale/HTTPS and every auth mode; Advanced remains available; process restoration retains stage but never unsubmitted secrets.

### N1.8 — Bounded Android capture and attachment pipeline (R1.8)

**Problem:** Only one text/image ACTION_SEND is handled; selected text, multiple/general files, ordered managed intake, target selection, and process-death persistence are absent.

**Effort:** **M**. **Dependencies:** N0.4, N0.9, N0.10.

**Acceptance criteria:** PROCESS_TEXT/SEND/SEND_MULTIPLE/ClipData preserve order; count/per-item/aggregate/MIME/signature/TTL limits are enforced; grants are copied into owned storage; scope/target/instruction survives process death; unsupported binaries offer explicit managed upload/link alternatives; delivery is once or remains a visible draft.

### N1.9 — Capability-gated session organization (R1.9)

**Problem:** Pins/search/bulk admin exist, but local labels/groups/saved filters and versioned archive/move capabilities do not.

**Effort:** **M**. **Dependencies:** capability model from N1.7.

**Acceptance criteria:** Offline labels/groups/favorites are exact-scope and visibly Local; supported archive/move round-trips; unsupported controls are absent; pin migration is preserved; bulk operations respect filtered/hidden selections.

### N1.10 — Extract runtime, repository, and UI facades (R1.10)

**Problem:** ChatViewModel/ChatScreen/HermesRepository/HermesApi/global AppContainer mix unrelated state machines and keep important behavior uninjectable.

**Effort:** **L**, incremental. **Dependencies:** stabilize N0/N1 transport behavior first.

**Acceptance criteria:** A connection-scoped runtime coordinator owns child tab generations; transcript/voice/prompt/notification collaborators are injectable; feature Retrofit/repository interfaces are split; migrated Composables reach no global container; key recovery/poll tests no longer instantiate the god ViewModel.

### N1.11 — Localization, accessibility, and responsive surfaces (R1.11)

**Problem:** Existing locale catalogs cover 235 of 805 base entries and inline English remains across new/manage/car/widget surfaces.

**Effort:** **M**. **Dependencies:** after N1.7/N1.8 copy settles; CI check can land earlier.

**Acceptance criteria:** No representative source/manifest/widget display literals remain; current Arabic/Japanese/Simplified/Traditional Chinese catalogs are complete; pseudolocale, RTL, 200% font, compact/rail/car/widget layouts pass; translation completeness is gated.

### N1.12 — Version credential persistence and supply-chain evidence (R1.12)

**Problem:** Credentials use an alpha/deprecated abstraction with no versioned envelope/rollback fixtures; release retains no resolved lock/SBOM/scan/provenance bundle.

**Effort:** **M**. **Dependencies:** N0.7, N0.1.

**Acceptance criteria:** Every released format migrates atomically with old-or-new rollback readability; corruption routes to recovery; new installs use no alpha crypto API; release publishes dependency locks/SBOM/checksum/signing provenance and reviewed High/Critical scan disposition.

### N1.13 — Broader orchestration, UI, and performance gates (R1.13)

**Problem:** Fast P0 tests will still not measure whole-app device behavior or tail performance.

**Effort:** **M**. **Dependencies:** N0.12 and seams from N1.10.

**Acceptance criteria:** Managed API 29/target devices, Compose happy/error/restoration paths, worker/car/notification/navigation/widget wiring, macrobenchmark cold start/scroll, high-rate PTY FrameTimeline/allocations, Room-write trace, and one-vs-five-tab battery/request metrics are reproducible and uploaded without slowing the fast lane beyond its budget.

### N1.14 — Focused startup/cache/file/draft/voice/tile performance (R1.14)

**Problem:** Per-keystroke Room writes, eager TTS/container work, stale cache keys, duplicate file work, off-screen audio, and inaccurate tile state remain after the P0 hot path.

**Effort:** **M** in independent slices. **Dependencies:** N0.9, N1.1, N1.2.

**Acceptance criteria:** Drafts debounce 300–500 ms and flush on send/stop; cache obeys weight/TTL; closed preview has no late state; TTS/noncritical graph is lazy; microphone is stopped off-screen; profile fan-out has timeouts; tile reports UNAVAILABLE/INACTIVE/ACTIVE accurately; startup/request/write/battery budgets improve without data loss.

## P2 — enforced module boundaries

### N2.1 — Incremental Gradle module extraction

**Problem:** Package naming does not prevent global-container coupling and every change invalidates one large app module.

**Effort:** **L**. **Dependencies:** N1.10 stable facades; do not begin as a big-bang move.

**Acceptance criteria:** Extract `core:model`, `core:network`, and `core:data`, then vertical slices for chat/manage/voice/car; dependency direction is acyclic and enforced; migrated features cannot access AppContainer; focused tests/builds run independently; clean/release build time and APK behavior do not regress.

## Recommended wave order

1. ~~**Wave 0 — quick release and intent safety:** N0.1, N0.2, the S slices of N0.8, plus neutral mic/path/tile/dead-code cleanup from the audit~~ ✅ landed (`99ef0f2`)
2. ~~**Wave 1 — P0 security and scope:** N0.3, N0.4, N0.5, then N0.6~~ ✅ landed (`43139f7`, `12ed612`, `c784b0d`, `59a15eb`)
3. **Wave 2 — P0 recovery, data, and resource ownership:** N0.7, N0.9 (N0.10, N0.11 already landed), with N0.12 acceptance coverage landing alongside each item.
4. **Wave 3 — lifecycle and delivery platform:** N1.1, N1.2, N1.3, N1.4, N1.5, N1.6. (R1.8/R1.9 already landed early — merge `4520492`, `90fbec5`.)
5. **Wave 4 — user-facing features:** N1.7, N1.8, N1.9, N1.11.
6. **Wave 5 — architecture and durable assurance:** N1.10, N1.12, N1.13, N1.14, then N2.1 vertical module extraction. (N1.10 also unblocks N0.2's remaining RPC-boundary acceptance tests.)
