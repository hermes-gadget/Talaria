package com.hermesgadget.talaria.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B74: search-highlight ranges must be valid indices into the ORIGINAL
 * string — case-folding must not change offsets (İ folds to two chars in
 * full-string lowercase, shifting every subsequent index).
 */
class MarkdownHighlightRangesTest {

    @Test
    fun asciiRangesAreExact() {
        val ranges = markdownHighlightRanges("Hello HELLO world", "hello")
        assertEquals(listOf(0..4, 6..10), ranges)
    }

    @Test
    fun expandingFoldDoesNotShiftIndices() {
        // "İ" lowercases to "i̇" (2 chars) with full-string lowercase — the
        // per-code-point fold keeps "İ" intact so later indices stay valid.
        val text = "İstanbul post"
        val ranges = markdownHighlightRanges(text, "post")
        assertEquals(listOf(9..12), ranges)
        // The range must index the original text without throwing and must
        // cover exactly "post".
        assertEquals("post", text.substring(ranges.single()))
    }

    @Test
    fun rangesAlwaysIndexOriginalText() {
        val text = "Straße STRASSE match"
        val ranges = markdownHighlightRanges(text, "match")
        assertTrue(ranges.all { text.length > it.last })
        assertEquals("match", text.substring(ranges.single()))
    }
}
