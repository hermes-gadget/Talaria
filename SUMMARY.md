# Artifacts and subagent monitor

Branch: `feature/artifacts-subagents`

Implemented:

- A pure transcript artifact extractor for assistant/tool messages. It recognizes supported image, text, and archive paths in message text, markdown links/images, and nested tool payloads, deduplicates by session/path, and has unit coverage.
- An Artifacts screen/ViewModel with recent-session scanning through the existing sessions/messages API, image/text/archive/all filter chips, client pagination, filesystem image/text previews, FileProvider share sheets, and originating-session actions.
- A read-only expandable Subagent Monitor in the Chat header/rail. It combines existing tab working/tool/prompt state with live sidecar tool, prompt, and delegate/subagent frames, including argument summaries and elapsed timing.
- The Manage Artifacts entry and additive `Routes.ARTIFACTS` constant.

The central NavHost is intentionally untouched because `TalariaNavRoot.kt` is outside this worktree’s ownership. The integration owner should register `Routes.ARTIFACTS` to `ArtifactsScreen` and route its originating-session action to `Routes.chat(sessionId)`.

Verification requested by the task:

```text
./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon
```
