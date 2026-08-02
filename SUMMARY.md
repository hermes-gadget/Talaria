# ROADMAP #11 — Minor surfaces

Implemented the requested minor surfaces for Analytics, Voice, Cron, and Profiles:

- Analytics loads per-model usage through `getAnalyticsModels()` and shows it in a progressive-disclosure card section.
- Analytics connectivity includes the typed egress status from `getEgressStatus()`.
- Voice loads and displays the configured ElevenLabs voice list through `getElevenLabsVoices()`.
- Cron exposes a separate “Fire now” action backed by `fireCronJob({ job_id })`.
- Profiles loads sessions, supports per-profile provider/model updates, opens a profile terminal, and displays the setup command.
- New UI strings are English-only and scoped to `res/values/strings_minor.xml`; feature groups use `CollapsibleSection`.

Verification:

```text
export ANDROID_HOME=$HOME/android-sdk JAVA_HOME=$HOME/java
export PATH=$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH
./gradlew :app:compileDebugKotlin --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m -q
```

The single prescribed compile attempt failed because the shared Kotlin daemon ran out of memory (`OutOfMemoryError: GC overhead limit exceeded`) while reading the cached Compose material-icons class files. No retry was made. The Hermes API client and all files outside the permitted feature/resource scope were left unchanged.
