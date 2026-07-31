# Contributing

Thanks for helping Talaria stay a trustworthy Hermes companion.

## Workflow

1. Fork / branch from `main`.
2. Keep changes focused; match existing package layout (`core` / `feature` / `ui`).
3. Run unit tests and a debug assemble before opening a PR:

```bash
export JAVA_HOME=…   # JDK 21
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

4. Update `CHANGELOG.md` under `[Unreleased]` for user-visible changes.
5. If you touch Hermes endpoints, update `docs/API.md` and bump notes in `ARCHITECTURE.md` when the baseline changes.

## Style

- Kotlin, Jetpack Compose, Material 3.
- Prefer `StateFlow` + small ViewModels; avoid premature `useMemo`-style caching.
- KDoc on non-obvious public types (auth, PTY, notification pipeline).
- Apache-2.0 license header on new source files (copy from an existing file).
- No proprietary blobs. Optional native STT engines must be documented and opt-in.

## Privacy bar

Do not add analytics, crash upload, or advertising SDKs without an explicit, default-off design discussed in an issue. Network calls must target user-configured Hermes endpoints (or user-initiated OIDC).

## API fidelity

Prefer discovering contracts from:

- https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard
- `NousResearch/hermes-agent` → `web/src/lib/api.ts` and `hermes_cli/web_server.py`

Document gaps instead of inventing endpoints.

## PR checklist

- [ ] Builds with `./gradlew :app:assembleDebug`
- [ ] Tests added/updated where practical
- [ ] Docs updated if behavior or API mapping changed
- [ ] No secrets committed (`keystore.properties`, tokens, `.jks`)
