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
package com.hermesgadget.talaria.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleMarkdownTest {

    @Test
    fun parsesFencedCodeAndLanguageTokens() {
        val document = parseMarkdown(
            """
            ```kotlin
            fun greet(name: String): Int { // comment
                val answer = 42
                val greeting = "hi"
                return answer
            }
            ```
            """.trimIndent(),
        )

        val code = document.blocks.single() as MarkdownCodeBlock
        assertEquals("kotlin", code.language)
        assertTrue(code.tokens.any { it.text == "fun" && it.kind == MarkdownCodeTokenKind.KEYWORD })
        assertTrue(code.tokens.any { it.text == "\"hi\"" && it.kind == MarkdownCodeTokenKind.STRING })
        assertTrue(code.tokens.any { it.text == "// comment" && it.kind == MarkdownCodeTokenKind.COMMENT })
        assertTrue(code.tokens.any { it.text == "42" && it.kind == MarkdownCodeTokenKind.NUMBER })
    }

    @Test
    fun highlightsJavascriptAliasesAndBlockCommentsOffline() {
        val code = parseMarkdown(
            """
            ```js
            const answer = 42 /* useful */
            """.trimIndent(),
        ).blocks.single() as MarkdownCodeBlock

        assertTrue(code.tokens.any { it.text == "const" && it.kind == MarkdownCodeTokenKind.KEYWORD })
        assertTrue(code.tokens.any { it.text == "42" && it.kind == MarkdownCodeTokenKind.NUMBER })
        assertTrue(code.tokens.any { it.text == "/* useful */" && it.kind == MarkdownCodeTokenKind.COMMENT })
    }

    @Test
    fun searchHighlightRangesAreCaseInsensitiveAndNonOverlapping() {
        assertEquals(
            listOf(0..4, 8..12),
            markdownHighlightRanges("Build a build", "BUILD"),
        )
    }

    @Test
    fun parsesGfmTableWithRows() {
        val document = parseMarkdown(
            """
            | Name | Status |
            | :--- | :---: |
            | Hermes | online |
            """.trimIndent(),
        )

        val table = document.blocks.single() as MarkdownTable
        assertEquals(listOf("Name", "Status"), table.header.map(::inlineText))
        assertEquals(listOf(listOf("Hermes", "online")), table.rows.map { row -> row.map(::inlineText) })
    }

    @Test
    fun parsesLinksAndInlineStyles() {
        val paragraph = (parseMarkdown("**bold** [docs](https://example.com) ~~old~~")
            .blocks.single() as MarkdownParagraph).lines.single()

        assertTrue(paragraph.any { it is MarkdownBold })
        val link = paragraph.filterIsInstance<MarkdownLink>().single()
        assertEquals("https://example.com", link.url)
        assertEquals("docs", inlineText(link.children))
        assertTrue(paragraph.any { it is MarkdownStrike })
    }

    @Test
    fun parsesNestedOrderedAndUnorderedLists() {
        val list = parseMarkdown(
            """
            - first
              - nested
            1. ordered
            """.trimIndent(),
        ).blocks.single() as MarkdownList

        assertEquals(listOf(0, 1, 0), list.items.map { it.depth })
        assertEquals(listOf(false, false, true), list.items.map { it.ordered })
        assertEquals(1, list.items.last().number)
    }

    @Test
    fun parsesBlockquotesAndHorizontalRules() {
        val document = parseMarkdown(
            """
            > quoted **answer**

            ---
            """.trimIndent(),
        )

        assertTrue(document.blocks[0] is MarkdownQuote)
        assertTrue(document.blocks[1] is MarkdownHorizontalRule)
    }

    @Test
    fun deeplyNestedInlineMarkupTerminatesWithoutRecursiveStackGrowth() {
        val markdown = buildString {
            repeat(200) { append("**") }
            append('x')
            repeat(200) { append("**") }
        }

        val document = parseMarkdown(markdown)

        assertEquals(1, document.blocks.size)
        assertTrue(document.blocks.single() is MarkdownParagraph)
    }

    @Test
    fun inputAndBlockBudgetsBoundPathologicalDocuments() {
        val markdown = buildString {
            repeat(MAX_MARKDOWN_BLOCKS + 32) { appendLine("---") }
            append("z".repeat(MAX_MARKDOWN_INPUT_BYTES.toInt()))
        }

        val document = parseMarkdown(markdown)

        assertTrue(document.blocks.size <= MAX_MARKDOWN_BLOCKS)
    }

    @Test
    fun codeTokenBudgetBoundsRepeatedKeywords() {
        val document = parseMarkdown(
            "```kotlin\n" + "true ".repeat(MAX_MARKDOWN_CODE_TOKENS + 100) + "\n```")

        val code = document.blocks.single() as MarkdownCodeBlock
        assertTrue(code.tokens.size <= MAX_MARKDOWN_CODE_TOKENS)
    }

    private fun inlineText(nodes: List<MarkdownInline>): String = buildString {
        nodes.forEach { node ->
            when (node) {
                is MarkdownText -> append(node.value)
                is MarkdownBold -> append(inlineText(node.children))
                is MarkdownItalic -> append(inlineText(node.children))
                is MarkdownInlineCode -> append(node.value)
                is MarkdownStrike -> append(inlineText(node.children))
                is MarkdownLink -> append(inlineText(node.children))
            }
        }
    }
}
