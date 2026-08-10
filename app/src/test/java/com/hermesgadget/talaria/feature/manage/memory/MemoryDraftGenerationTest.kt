package com.hermesgadget.talaria.feature.manage.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDraftGenerationTest {
    @Test
    fun `a secret completion only matches the submitted value`() {
        assertTrue(memoryDraftValueMatchesSubmission("submitted", "submitted"))
        assertFalse(memoryDraftValueMatchesSubmission("typed-after-save", "submitted"))
        assertFalse(memoryDraftValueMatchesSubmission(null, "submitted"))
    }

    @Test
    fun `a load may replace only the generation it started with`() {
        assertTrue(isMemoryConfigLoadCurrent(4L, 4L))
        assertFalse(isMemoryConfigLoadCurrent(5L, 4L))
    }
}
