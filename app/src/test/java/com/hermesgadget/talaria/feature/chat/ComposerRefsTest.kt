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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerRefsTest {
    @Test
    fun detectsMentionsUrlsAndPathsWithoutOverlappingUrlPaths() {
        val analysis = ComposerRefs.analyze(
            "Ask @Build-agent to read https://example.com/docs and ./app/src/main.kt.",
            knownAgents = listOf("Build agent", "Research"),
        )

        assertEquals(
            listOf("https://example.com/docs", "./app/src/main.kt", "@Build-agent"),
            analysis.references.map { it.value },
        )
        assertEquals(
            listOf(ComposerReferenceKind.URL, ComposerReferenceKind.PATH, ComposerReferenceKind.MENTION),
            analysis.references.map { it.kind },
        )
    }

    @Test
    fun mentionCompletionReplacesOnlyTheTrailingToken() {
        val completion = ComposerRefs.analyze(
            "Please ask @b",
            knownAgents = listOf("Build agent", "Research"),
        ).completions.single()

        assertEquals(ComposerCompletionKind.MENTION, completion.kind)
        assertEquals("@Build-agent ", completion.insertText)
        assertEquals("@b", "Please ask @b".substring(completion.tokenStart, completion.tokenEnd))
    }

    @Test
    fun emojiCompletionIsOfflineAndSupportsShortcodePrefix() {
        val completions = ComposerRefs.analyze("Great :roc", emptyList()).completions

        assertTrue(completions.isNotEmpty())
        assertEquals(ComposerCompletionKind.EMOJI, completions.first().kind)
        assertEquals("🚀 ", completions.first().insertText)
    }
}
