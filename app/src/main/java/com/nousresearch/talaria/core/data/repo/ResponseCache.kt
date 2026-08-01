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

package com.nousresearch.talaria.core.data.repo

import java.util.concurrent.ConcurrentHashMap

/**
 * Tiny in-memory read-through cache that makes flipping between Manage menus
 * feel instant: the first visit hits the network, subsequent visits within the
 * TTL return the last decoded value synchronously (no thread hop, no spinner).
 *
 * Entries are keyed per active connection so switching profiles never shows the
 * previous profile's data. Mutations invalidate the affected key so a toggle is
 * never masked by a stale hit.
 *
 * This is deliberately a value cache, not a request de-duplicator: it trades a
 * bounded staleness window (the TTL) for zero-latency re-navigation. Live
 * surfaces that must never be stale (Status polling, pairing) simply don't route
 * through it.
 */
class ResponseCache(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val value: Any?, val storedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Fresh cached value for [key], or null when absent/expired. */
    fun peek(key: String, ttlMs: Long): Any? {
        val e = entries[key] ?: return null
        return if (now() - e.storedAt <= ttlMs) e.value else null
    }

    fun put(key: String, value: Any?) {
        entries[key] = Entry(value, now())
    }

    /** Drop a single key (call after a mutation to its data). */
    fun invalidate(key: String) {
        entries.remove(key)
    }

    /** Drop everything (call on profile/management-scope change or disconnect). */
    fun clear() {
        entries.clear()
    }

    /**
     * Return a fresh cached value if present, otherwise run [fetch], store the
     * success, and return it. On a hit nothing suspends, so the caller resumes on
     * the same frame. Failures are never cached.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> readThrough(
        key: String,
        ttlMs: Long,
        fetch: suspend () -> T,
    ): Result<T> {
        peek(key, ttlMs)?.let { return Result.success(it as T) }
        return runCatching { fetch() }.onSuccess { put(key, it) }
    }
}
