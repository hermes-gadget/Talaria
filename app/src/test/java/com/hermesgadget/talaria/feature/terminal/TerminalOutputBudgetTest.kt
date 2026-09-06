package com.hermesgadget.talaria.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P12: TerminalOutput must keep serving a consistent text projection while the
 * ViewModel coalesces high-frequency PTY bursts. These tests pin the output-buffer
 * contract the coalescing depends on (append-only growth + truncation marker).
 */
class TerminalOutputBudgetTest {

    @Test
    fun `append grows text incrementally`() {
        val out = TerminalOutputBuffer(maxChars = 100, diagnosticTailChars = 10)
        out.append("hello")
        assertEquals("hello", out.displayText)
        out.append(" world")
        assertEquals("hello world", out.displayText)
    }

    @Test
    fun `overflow marks truncation and keeps tail`() {
        val out = TerminalOutputBuffer(maxChars = 20, diagnosticTailChars = 5)
        out.append("x".repeat(50))
        assertTrue(out.isTruncated)
        assertEquals(20, out.text.length)
        assertTrue(out.droppedChars == 30L)
        assertTrue(out.displayText.startsWith(TerminalOutputBuffer.TRUNCATION_MARKER))
    }

    @Test
    fun `clear resets buffer`() {
        val out = TerminalOutputBuffer(maxChars = 100, diagnosticTailChars = 10)
        out.append("abc")
        out.clear()
        assertEquals("", out.text)
        assertEquals(false, out.isTruncated)
    }
}
