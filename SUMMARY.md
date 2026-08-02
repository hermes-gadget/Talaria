# Talaria extensions — ROADMAP item 2

Implemented the dashboard plugin surface and Kanban board for the Android Compose app.

- Added `PluginsScreen` with dashboard plugin discovery/rescan/visibility, agent-plugin install and lifecycle actions, and memory/context provider selection.
- Added `KanbanScreen` with board switching/creation, status columns, task creation/edit/delete, statistics, assignees, active workers, and a task detail sheet with comments, worker log, run inspection, and run termination.
- Wired `Routes.PLUGINS` and `Routes.KANBAN` in `TalariaNavRoot.kt`.
- Added English feature-owned resources in `strings_plugins.xml` and `strings_kanban.xml`.

Verification: `git diff --check` passed. The prescribed low-memory Gradle compile (`--no-daemon`, `--max-workers=1`) ran for 7m33s and failed with `OutOfMemoryError: GC overhead limit exceeded` in `:app:compileDebugKotlin`, without emitting Kotlin source diagnostics. No further build retries were made.
