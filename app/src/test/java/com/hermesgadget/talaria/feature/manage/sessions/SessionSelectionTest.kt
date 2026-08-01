/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.manage.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSelectionTest {
    @Test
    fun selectionAddsAndRemovesIdsWithoutMutatingTheOriginalSet() {
        val original = setOf("a")

        val selected = toggleSessionSelection(original, "b", checked = true)
        val removed = toggleSessionSelection(selected, "a", checked = false)

        assertEquals(setOf("a"), original)
        assertEquals(setOf("a", "b"), selected)
        assertEquals(setOf("b"), removed)
    }
}
