/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.hermesgadget.talaria.feature.manage.sessions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExportFilenameTest {
    @Test
    fun `export filename cannot traverse directories`() {
        val filename = safeSessionExportFilename("../../secrets/session\\id")

        assertFalse(filename.contains('/'))
        assertFalse(filename.contains('\\'))
        assertFalse(filename.contains(".."))
        assertTrue(filename.endsWith(".md"))
    }

    @Test
    fun `sanitization collisions retain distinct hash suffixes`() {
        assertNotEquals(safeSessionExportFilename("a/b"), safeSessionExportFilename("a\\b"))
    }
}
