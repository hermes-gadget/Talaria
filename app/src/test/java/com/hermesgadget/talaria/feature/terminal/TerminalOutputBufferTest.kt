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

package com.hermesgadget.talaria.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOutputBufferTest {
    @Test
    fun buffersChunksStripsAnsiAndKeepsLineBreaks() {
        val buffer = TerminalOutputBuffer()

        buffer.append("\u001B[32mfirst\u001B[0m\n")
        val output = buffer.append("second\r\n")

        assertEquals("first\nsecond\n", output)
        assertFalse(output.contains("\u001B"))
        assertFalse(output.contains('\r'))
    }

    @Test
    fun `bounds long output to the newest characters and displays truncation`() {
        val buffer = TerminalOutputBuffer(maxChars = 5)

        assertEquals("23456", buffer.append("123456"))
        assertEquals("6789a", buffer.append("789a"))
        assertTrue(buffer.isTruncated)
        assertEquals(5, buffer.droppedChars)
        assertTrue(buffer.displayText.startsWith(TerminalOutputBuffer.TRUNCATION_MARKER))
        assertTrue(buffer.diagnosticTailText.isNotEmpty())
    }

    @Test
    fun `escape sequence split across frames is parsed as one sequence`() {
        val buffer = TerminalOutputBuffer()
        // CSI intro arrives in one frame, the rest in the next (M3).
        buffer.append("\u001B[")
        val output = buffer.append("31mRED\u001B[0m")

        assertEquals("RED", output)
        assertFalse(output.contains("31m"))
        assertFalse(output.contains("\u001B"))
    }
}
