/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.data.repo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B77: an in-flight writer must not repopulate the cache after a clear().
 * The epoch-checked put overload drops stale writes; the plain overload
 * stays for internal callers guarded by the stamp check (B08).
 */
class ResponseCacheStaleEpochTest {

    @Test
    fun `put with stale epoch is dropped after clear`() {
        val cache = ResponseCache(maxEntries = 8, maxWeight = 1024)
        val before = cache.currentEpoch
        cache.put("k", "v", observedEpoch = before)
        assertEquals("v", cache.peek("k", 20_000L))

        cache.clear()
        // The writer captured `before`; clear() bumped the epoch — write must drop.
        cache.put("k", "stale", observedEpoch = before)
        assertNull(cache.peek("k", 20_000L))

        // A writer that observed the NEW epoch still stores.
        cache.put("k", "fresh", observedEpoch = cache.currentEpoch)
        assertEquals("fresh", cache.peek("k", 20_000L))
    }

    @Test
    fun `plain put still stores (stamp-guarded callers rely on it)`() {
        val cache = ResponseCache(maxEntries = 8, maxWeight = 1024)
        val epoch = cache.currentEpoch
        cache.clear()
        cache.put("k", "v") // internal fetch path guards via B08 stamp
        assertEquals("v", cache.peek("k", 20_000L))
        assertTrue(epoch != cache.currentEpoch)
    }

    @Test
    fun `plain put with ttl still works`() {
        val cache = ResponseCache(maxEntries = 8, maxWeight = 1024)
        cache.put("k", "v", ttlMs = 5000)
        assertEquals("v", cache.peek("k", 20_000L))
    }
}
