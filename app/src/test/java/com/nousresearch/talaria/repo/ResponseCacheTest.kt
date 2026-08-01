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
package com.nousresearch.talaria.repo

import com.nousresearch.talaria.core.data.repo.ResponseCache
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseCacheTest {

    @Test
    fun `second read within ttl reuses cached value without fetching`() = runTest {
        var clock = 0L
        val cache = ResponseCache(now = { clock })
        var fetches = 0

        val first = cache.readThrough("skills", ttlMs = 100) { fetches++; listOf("a") }
        clock = 50
        val second = cache.readThrough("skills", ttlMs = 100) { fetches++; listOf("b") }

        assertEquals(listOf("a"), first.getOrNull())
        assertEquals(listOf("a"), second.getOrNull())
        assertEquals("fetched only once", 1, fetches)
    }

    @Test
    fun `read after ttl expiry fetches again`() = runTest {
        var clock = 0L
        val cache = ResponseCache(now = { clock })
        var fetches = 0

        cache.readThrough("skills", ttlMs = 100) { fetches++; "a" }
        clock = 200
        val stale = cache.readThrough("skills", ttlMs = 100) { fetches++; "b" }

        assertEquals("b", stale.getOrNull())
        assertEquals(2, fetches)
    }

    @Test
    fun `invalidate forces a refetch on next read`() = runTest {
        val cache = ResponseCache(now = { 0L })
        var fetches = 0

        cache.readThrough("cron", ttlMs = 10_000) { fetches++; "old" }
        cache.invalidate("cron")
        val fresh = cache.readThrough("cron", ttlMs = 10_000) { fetches++; "new" }

        assertEquals("new", fresh.getOrNull())
        assertEquals(2, fetches)
    }

    @Test
    fun `clear drops every key`() = runTest {
        val cache = ResponseCache(now = { 0L })
        var fetches = 0
        cache.readThrough("a", ttlMs = 10_000) { fetches++; 1 }
        cache.readThrough("b", ttlMs = 10_000) { fetches++; 2 }

        cache.clear()
        cache.readThrough("a", ttlMs = 10_000) { fetches++; 1 }
        cache.readThrough("b", ttlMs = 10_000) { fetches++; 2 }

        assertEquals("both keys refetched after clear", 4, fetches)
    }

    @Test
    fun `failed fetch is not cached`() = runTest {
        val cache = ResponseCache(now = { 0L })
        var fetches = 0

        val failed = cache.readThrough<String>("x", ttlMs = 10_000) {
            fetches++
            throw IllegalStateException("boom")
        }
        val retried = cache.readThrough("x", ttlMs = 10_000) { fetches++; "ok" }

        assertEquals(true, failed.isFailure)
        assertEquals("ok", retried.getOrNull())
        assertEquals(2, fetches)
    }
}
