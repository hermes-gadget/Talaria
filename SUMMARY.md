# Markdown upgrade

Branch: `feature/markdown-upgrade`

Implemented desktop-parity chat markdown in `SimpleMarkdown.kt` with a memoized, dependency-free parser for fenced syntax-colored code, GFM tables, links, blockquotes, strikethrough, nested lists, and horizontal rules. Links open through `Intent.ACTION_VIEW` by default and accept an optional click handler; code blocks are selectable monospace surfaces.

Added `ChangedFilesCard.kt`, deriving completed file-edit tool paths and aggregating unified-diff additions/removals into a compact card. The card is rendered only in the existing chat message-items `LazyColumn` region.

Added parser and changed-files derivation unit tests.

Verification passed:

```text
./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon
```

The shell required `JAVA_HOME=/home/ben/java` and `ANDROID_HOME=/home/ben/android-sdk` to locate the preinstalled toolchains; no repository configuration was changed.
