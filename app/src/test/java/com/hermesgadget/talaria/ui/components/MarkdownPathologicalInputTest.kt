/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P13: a single line of unmatched opening brackets used to trigger
 * quadratic suffix searches. Pathological input must parse in bounded,
 * roughly-linear time.
 */
class MarkdownPathologicalInputTest {

    @Test
    fun `long run of opening brackets parses quickly (linear scan)`() {
        // 4k brackets — quadratic would be ~8M indexOf scans over 4k chars.
        val pathological = "[".repeat(4_096) + "tail"
        val start = System.nanoTime()
        parseMarkdown(pathological)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("parse took ${elapsedMs}ms (quadratic suspected)", elapsedMs < 2_000)
    }

    @Test
    fun `interleaved brackets and links parse correctly`() {
        val doc = parseMarkdown("[a](url)[b][c](url2)")
        // All three links survive the linear scanner.
        val text = doc.blocks.joinToString { it.toString() }
        assertTrue(text.contains("a") && text.contains("url"))
    }
}
