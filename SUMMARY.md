# Parity Wave 1 — Agent Summaries

## Starmap (feature/starmap)
- Feature-local graph adapter so the Learning screen retains node timestamps and `{source,target}` edges from Hermes v0.19.1 without changing shared/core files.
- Deterministic seeded radial placement, node sizing/colors, labels, edge culling, Canvas pan/zoom/tap interaction, timeline slider/chips, node detail metadata.
- Preserved stats, clusters, node list, and existing get/update/delete flows.
- Verification: `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon` passed (two redundant-null-check warnings in LearningLayout.kt).

## Markdown upgrade (feature/markdown-upgrade)
- Desktop-parity chat markdown in `SimpleMarkdown.kt`: memoized dependency-free parser for fenced syntax-colored code, GFM tables, links (Intent.ACTION_VIEW + optional handler), blockquotes, strikethrough, nested lists, horizontal rules; selectable monospace code surfaces.
- `ChangedFilesCard.kt`: completed file-edit tool paths aggregated with unified-diff +/- counts, rendered in the chat message-items LazyColumn region.
- Verification passed.

## Artifacts browser + subagent monitor (feature/artifacts-subagents)
- (see SUMMARY in branch commit; artifacts extraction, preview via fs bridge, Manage entry, subagent monitor panel)

## Files pane previews + sharing (feature/files-preview)
- (see branch SUMMARY; data-URL parsing, image decode + pinch zoom, binary metadata, FileProvider share, overwrite confirm, pull-to-refresh)

## Manage depth (feature/manage-depth)
- (see branch SUMMARY; cron runs/delivery/blueprints, session admin bulk-delete/empty/import/stats/descendant, skill authoring)
