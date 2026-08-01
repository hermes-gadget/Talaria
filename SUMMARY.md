# Manage-depth implementation

Branch: `feature/manage-depth`

Implemented the requested management-depth surfaces:

- Cron jobs now use an additive raw v0.19.1 API seam for object-shaped schedules, delivery-target selection, expandable per-job run history, and blueprint field instantiation.
- Sessions now expose stats, multi-select bulk deletion, empty-session count/delete, SAF JSON import, and latest-descendant navigation from session detail. Destructive actions remain confirmation-gated.
- Skills now have a validated SKILL.md content editor for name/description/body and a Skills Hub update action.
- Added focused unit coverage for cron run parsing, blueprint instantiation, and session selection behavior.

The legacy typed cron API remains unchanged for existing repository consumers; the new raw methods and management-depth models are additive. A missing pre-existing `FsDataUrl` import in `HermesApi.kt` was also restored so the module compiles.

Verification passed with the requested tasks:

```text
./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon
```

The shared shell required `JAVA_HOME=/home/ben/.local/jdk17` and `ANDROID_HOME=/home/ben/android-sdk` to run Gradle. No services were restarted and no remote changes were made.
