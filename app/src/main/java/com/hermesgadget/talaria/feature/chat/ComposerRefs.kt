/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hermesgadget.talaria.feature.chat

/** A reference detected in the composer without changing the submitted prompt. */
enum class ComposerReferenceKind { MENTION, URL, PATH }

data class ComposerReference(
    val kind: ComposerReferenceKind,
    val value: String,
)

enum class ComposerCompletionKind { MENTION, EMOJI }

/** A completion replacement range is kept so a tap never loses surrounding draft text. */
data class ComposerCompletion(
    val kind: ComposerCompletionKind,
    val label: String,
    val insertText: String,
    val tokenStart: Int,
    val tokenEnd: Int,
)

internal data class ComposerAnalysis(
    val references: List<ComposerReference> = emptyList(),
    val completions: List<ComposerCompletion> = emptyList(),
)

/**
 * Offline composer reference parsing. Hermes does not need to understand these
 * decorations: they are intentionally just affordances around the normal text
 * prompt, while mentions and emoji offer convenient local completions.
 */
internal object ComposerRefs {
    private val URL = Regex("(?i)(?<!\\S)(?:https?://|www\\.)[^\\s<>()]+")
    private val PATH = Regex("(?<!\\S)(?:~?/|\\.{1,2}/|/)[^\\s<>()]+|(?<!\\S)[A-Za-z0-9_.-]+/[^\\s<>()]+")
    private val MENTION = Regex("(?<!\\S)@[A-Za-z0-9_.-]+")
    private val MENTION_COMPLETION = Regex("(?:^|\\s)@([A-Za-z0-9_.-]*)$")
    private val EMOJI_COMPLETION = Regex("(?<!\\w):([a-z0-9_+\\-]*)$", RegexOption.IGNORE_CASE)

    private data class Emoji(
        val shortcode: String,
        val value: String,
    )

    // Keep this small and predictable: it is a completion palette, not a full
    // emoji database, and it works entirely offline.
    private val EMOJI = listOf(
        Emoji("smile", "😄"),
        Emoji("grin", "😁"),
        Emoji("laughing", "😆"),
        Emoji("wink", "😉"),
        Emoji("heart", "❤️"),
        Emoji("thumbsup", "👍"),
        Emoji("wave", "👋"),
        Emoji("rocket", "🚀"),
        Emoji("tada", "🎉"),
        Emoji("fire", "🔥"),
        Emoji("eyes", "👀"),
        Emoji("thinking", "🤔"),
        Emoji("sparkles", "✨"),
        Emoji("white_check_mark", "✅"),
        Emoji("warning", "⚠️"),
        Emoji("bug", "🐛"),
        Emoji("memo", "📝"),
        Emoji("pray", "🙏"),
    )

    fun analyze(text: String, knownAgents: List<String>): ComposerAnalysis {
        val references = mutableListOf<ComposerReference>()
        val occupied = mutableListOf<IntRange>()

        URL.findAll(text).forEach { match ->
            val value = trimReferencePunctuation(match.value)
            if (value.isNotBlank()) {
                val range = match.range.first..(match.range.first + value.length - 1)
                references += ComposerReference(ComposerReferenceKind.URL, value)
                occupied += range
            }
        }
        PATH.findAll(text).forEach { match ->
            val value = trimReferencePunctuation(match.value)
            if (value.isBlank()) return@forEach
            val range = match.range.first..(match.range.first + value.length - 1)
            if (occupied.none { it.overlaps(range) }) {
                references += ComposerReference(ComposerReferenceKind.PATH, value)
                occupied += range
            }
        }
        MENTION.findAll(text).forEach { match ->
            if (occupied.none { it.overlaps(match.range) }) {
                references += ComposerReference(ComposerReferenceKind.MENTION, match.value)
            }
        }

        val completions = mentionCompletions(text, knownAgents).ifEmpty {
            emojiCompletions(text)
        }
        return ComposerAnalysis(
            references = references.distinctBy { it.kind to it.value },
            completions = completions,
        )
    }

    private fun mentionCompletions(text: String, knownAgents: List<String>): List<ComposerCompletion> {
        val match = MENTION_COMPLETION.find(text) ?: return emptyList()
        val query = match.groupValues[1]
        val tokenStart = text.length - query.length - 1
        return knownAgents
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .filter { it.replace(Regex("\\s+"), "-").startsWith(query, ignoreCase = true) }
            .map { agent ->
                val mention = "@${agent.replace(Regex("\\s+"), "-")}"
                ComposerCompletion(
                    kind = ComposerCompletionKind.MENTION,
                    label = mention,
                    insertText = "$mention ",
                    tokenStart = tokenStart,
                    tokenEnd = text.length,
                )
            }
            .take(8)
            .toList()
    }

    private fun emojiCompletions(text: String): List<ComposerCompletion> {
        val match = EMOJI_COMPLETION.find(text) ?: return emptyList()
        val query = match.groupValues[1]
        val tokenStart = text.length - query.length - 1
        return EMOJI
            .asSequence()
            .filter { it.shortcode.startsWith(query, ignoreCase = true) }
            .map { emoji ->
                ComposerCompletion(
                    kind = ComposerCompletionKind.EMOJI,
                    label = "${emoji.value} :${emoji.shortcode}:",
                    insertText = "${emoji.value} ",
                    tokenStart = tokenStart,
                    tokenEnd = text.length,
                )
            }
            .take(8)
            .toList()
    }

    private fun trimReferencePunctuation(value: String): String =
        value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')

    private fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last
}
