/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedTextBufferTest {
    @Test
    fun `append only uses remaining capacity and retains a diagnostic tail`() {
        val buffer = BoundedTextBuffer(maxChars = 5, diagnosticTailChars = 3)

        buffer.append("123456")
        buffer.append("789")

        assertEquals("12345", buffer.text)
        assertEquals("789", buffer.diagnosticTail)
        assertEquals(4, buffer.droppedCharCount)
        assertTrue(buffer.isTruncated)
        assertEquals("12345\n\nTRUNCATED", buffer.displayText("TRUNCATED"))
    }

    @Test
    fun `clear removes retained payload and truncation state`() {
        val buffer = BoundedTextBuffer(maxChars = 2, diagnosticTailChars = 2)
        buffer.append("abcd")

        buffer.clear()

        assertEquals("", buffer.text)
        assertEquals("", buffer.diagnosticTail)
        assertEquals(0, buffer.droppedCharCount)
        assertFalse(buffer.isTruncated)
        buffer.append("next")
        assertEquals("ne", buffer.text)
    }
}
