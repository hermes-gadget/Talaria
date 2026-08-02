# Talaria chat UX backlog summary

Implemented ROADMAP items 12, 13, 14, and 15 in the chat feature without changing HermesApi, navigation, models, Gradle, or excluded locale files.

## Delivered

- Composer refs: offline @-mention completion from Hermes, you, current tab, and session titles; URL/path/mention chips; and emoji shortcode completions with token-safe replacement.
- Find in session: case-insensitive line filtering, match count, closeable search row, and highlighted matches in markdown transcript content.
- Message actions: long-press opens edit/branch actions; editing a user prompt branches before it with the existing `session.branch` WebSocket RPC and preloads edited text in the new durable child chat; branching keeps the parent tab and existing durable/runtime ID behavior.
- Markdown: preserved the existing `SimpleMarkdownText(String, Modifier, ((String)->Unit)?)` signature and added a highlight-query overload; offline token highlighting now covers common languages and comment forms. Added parser, range, composer, transcript, and action tests.

## Files

- `app/src/main/java/com/hermesgadget/talaria/feature/chat/ComposerRefs.kt`
- `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatScreen.kt`
- `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatSessionControls.kt`
- `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatTranscriptPolicy.kt`
- `app/src/main/java/com/hermesgadget/talaria/feature/chat/ChatViewModel.kt`
- `app/src/main/java/com/hermesgadget/talaria/ui/components/SimpleMarkdown.kt`
- `app/src/main/res/values/strings_chat.xml`
- Related chat and markdown unit tests.

## Verification

- `git diff --check`: passed.
- Resource reference and duplicate-name check: passed.
- Prescribed single low-memory compile (`--no-daemon --max-workers=1`): failed in `:app:kspDebugKotlin` with `java.lang.OutOfMemoryError: GC overhead limit exceeded` on the shared VM. No retry was made, per instruction.
- Unit tests were added but not run separately because the shared VM was memory constrained.
