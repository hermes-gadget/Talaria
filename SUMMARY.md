# Provider onboarding

Implemented guided provider onboarding in the Connect flow.

## Included

- Provider catalog loading with active provider/model display. The client tries `GET /api/providers` first and falls back to `/api/model/options` for Hermes v0.19.1.
- Custom OpenAI-compatible endpoint listing, local validation, server validation, save/update, activate, remove, API-key input, model, context length, model discovery, and default-provider controls.
- Provider credential validation through `POST /api/providers/validate` with inline reachable/accepted/rejected results.
- Credential-pool listing with redacted server previews, add, confirmed replace/edit, and confirmed removal.
- Provider OAuth catalog and user-started device-code/PKCE flows: authorization URL opening, user-code display, code paste, and status polling. External/unavailable OAuth flows degrade to API-key entry.
- Existing dashboard connection URL, authentication, TLS pin, doctor, saved-profile, browser OIDC, and connect/continue actions remain intact.
- Serializable provider models and unit tests for provider-list parsing and custom-endpoint validation.

## Live contract notes

The dashboard at `127.0.0.1:9119` (Hermes v0.19.1) currently exposes:

- `/api/providers` and `/api/providers/credential-pool` as 404s;
- provider discovery through `/api/model/options`;
- custom endpoints at `/api/providers/custom-endpoints` with GET/POST plus endpoint validate/activate/delete routes;
- credential pools at `/api/credentials/pool` with GET/POST/DELETE;
- read-only OAuth catalog at `GET /api/providers/oauth`, with device-code, PKCE, and external entries.

The implementation follows those live routes and keeps the newer `/api/providers` catalog probe as a graceful compatibility path.

## Verification

```text
JAVA_HOME=/home/ben/java ANDROID_HOME=/home/ben/android-sdk ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon
BUILD SUCCESSFUL
```

Branch: `feature/provider-onboarding`.
