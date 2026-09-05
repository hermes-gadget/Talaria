/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B26: legacy epoch-seconds rows, current epoch-millis rows, and ISO rows
 * must share one ordering unit. A 2024 ISO session may not sort ahead of a
 * newer epoch-seconds row.
 */
class TimestampNormalizationTest {

    @Test
    fun `epoch seconds scale to millis`() {
        // 2025-01-01T00:00:00Z = 1735689600 (s) = 1735689600000 (ms)
        assertEquals(1735689600000L, normalizeTimestampMillis("1735689600"))
    }

    @Test
    fun `epoch millis pass through`() {
        assertEquals(1735689600123L, normalizeTimestampMillis("1735689600123"))
    }

    @Test
    fun `iso timestamps parse to millis`() {
        assertEquals(1735689600000L, normalizeTimestampMillis("2025-01-01T00:00:00Z"))
    }

    @Test
    fun `mixed legacy and current rows order correctly`() {
        val oldIso = 1735689600000L // 2025-01-01
        val newerSeconds = 1767225600L // 2026-01-01 (in seconds)
        val newerMillis = normalizeTimestampMillis(newerSeconds.toString())!!
        assertTrue("newer seconds row must sort after older ISO row", newerMillis > oldIso)
    }

    @Test
    fun `garbage values are rejected`() {
        assertNull(normalizeTimestampMillis("not-a-number"))
        assertNull(normalizeTimestampMillis(""))
        assertNull(normalizeTimestampMillis(null))
        assertNull(normalizeTimestampMillis("NaN"))
        assertNull(normalizeTimestampMillis("Infinity"))
        assertNull(normalizeTimestampMillis("-999"))
        assertNull(normalizeTimestampMillis("99999999999999"))
    }
}
