# Starmap implementation

- Branch: `feature/starmap`
- Added a feature-local graph adapter so the Learning screen retains node timestamps and `{source,target}` edges from Hermes v0.19.1 without changing shared/core files.
- Added deterministic seeded radial placement, node sizing/colors, labels, edge culling, Canvas pan/zoom/tap interaction, timeline slider/chips, and node detail metadata.
- Preserved stats, clusters, node list, and existing get/update/delete flows. Node descriptions continue to load through `getLearningNode`.
- Added unit coverage for deterministic placement, recency filtering, radial distance, and edge culling.

## Verification

The requested command passed with the available JDK and Android SDK paths:
`./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon`. The build reports two redundant-null-check warnings in `LearningLayout.kt`; unit tests and Kotlin compilation both succeed.
