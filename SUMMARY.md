# Files preview implementation

Branch: `feature/files-preview`

Implemented the Files pane media-preview and sharing parity work:

- image previews for PNG/JPG/JPEG/GIF/WebP/BMP and image MIME types, with base64 data-URL parsing and pinch/pan zoom;
- binary-file metadata with read-only presentation and FileProvider `ACTION_SEND` sharing for text, image, and binary files;
- overwrite confirmation plus existing optimistic-concurrency checks for text edits;
- pull-to-refresh and lifecycle-aware refresh when the Files destination resumes;
- unit coverage for data-URL parsing and preview-type mapping.

Verification passed with:

```text
./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon
```

The shell required the installed JDK at `/home/ben/.local/jdk17` and Android SDK at `/home/ben/android-sdk`.
