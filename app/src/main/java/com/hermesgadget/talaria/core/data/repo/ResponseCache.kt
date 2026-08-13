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

package com.hermesgadget.talaria.core.data.repo

import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * A small, thread-safe weighted LRU read-through cache.
 *
 * Callers still provide a read TTL because different repository surfaces have
 * different freshness requirements. Entries also carry the TTL used when they
 * were stored, so a longer later read cannot resurrect an older value. Both
 * entry count and approximate weight are bounded; oversized values are never
 * retained.
 */
class ResponseCache(
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxWeight: Long = DEFAULT_MAX_WEIGHT,
    private val weigher: (Any?) -> Long = ::defaultWeight,
) {
    private data class Entry(
        val value: Any,
        val storedAt: Long,
        val expiresAt: Long,
        val weight: Long,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var totalWeight = 0L

    // M18: single-flight + invalidation stamping. readThrough callers with the
    // same key share one fetch; a fetch that started before invalidate() must
    // not put its stale result back after the invalidation.
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Result<Any?>>>()
    private val keyGenerations = ConcurrentHashMap<String, Long>()
    private val epoch = AtomicLong(0L)

    init {
        require(maxEntries > 0) { "Response cache entry count must be positive" }
        require(maxWeight > 0L) { "Response cache weight must be positive" }
    }

    /** Fresh cached value for [key], or null when absent/expired. */
    fun peek(key: String, ttlMs: Long): Any? = synchronized(lock) {
        val currentTime = now()
        pruneExpiredLocked(currentTime)
        val entry = entries[key] ?: return@synchronized null
        val requestedExpiry = expiry(entry.storedAt, ttlMs)
        if (currentTime <= entry.expiresAt && currentTime <= requestedExpiry) {
            entry.value
        } else {
            removeLocked(key)
            null
        }
    }

    /** Store a value with its own expiry. Nulls are intentionally not cached. */
    fun put(key: String, value: Any?, ttlMs: Long = DEFAULT_ENTRY_TTL_MS) {
        if (value == null || ttlMs < 0L) {
            invalidate(key)
            return
        }
        val weight = weigher(value).coerceAtLeast(0L)
        synchronized(lock) {
            val currentTime = now()
            pruneExpiredLocked(currentTime)
            removeLocked(key)
            if (weight > maxWeight || ttlMs == 0L) return
            entries[key] = Entry(
                value = value,
                storedAt = currentTime,
                expiresAt = expiry(currentTime, ttlMs),
                weight = weight,
            )
            totalWeight = safeAdd(totalWeight, weight)
            trimLocked()
        }
    }

    /** Drop a single key (call after a mutation to its data). */
    fun invalidate(key: String) = synchronized(lock) {
        keyGenerations.merge(key, 1L, Long::plus)
        removeLocked(key)
    }

    /** Drop keys whose names begin with [prefix]. */
    fun invalidatePrefix(prefix: String) = synchronized(lock) {
        entries.keys.filter { it.startsWith(prefix) }.forEach(::removeLocked)
        keyGenerations.keys.filter { it.startsWith(prefix) }.forEach { key ->
            keyGenerations.merge(key, 1L, Long::plus)
        }
    }

    /** Drop keys matching an arbitrary predicate, useful for deleted scopes. */
    fun invalidateWhere(predicate: (String) -> Boolean) = synchronized(lock) {
        entries.keys.filter(predicate).forEach(::removeLocked)
        keyGenerations.keys.filter(predicate).forEach { key ->
            keyGenerations.merge(key, 1L, Long::plus)
        }
    }

    /** Drop expired values even when no caller happens to read their key. */
    fun pruneExpired() = synchronized(lock) {
        pruneExpiredLocked(now())
    }

    /** Drop everything (call on profile/management-scope change or disconnect). */
    fun clear() = synchronized(lock) {
        entries.clear()
        totalWeight = 0L
        epoch.incrementAndGet()
        keyGenerations.clear()
    }

    /** Exposed for deterministic boundary tests and diagnostics. */
    internal val entryCount: Int
        get() = synchronized(lock) { entries.size }

    /** Exposed for deterministic boundary tests and diagnostics. */
    internal val currentWeight: Long
        get() = synchronized(lock) { totalWeight }

    /**
     * Return a fresh cached value if present, otherwise run [fetch], store the
     * success, and return it. Failures are never cached.
     *
     * Concurrent callers for the same key share one in-flight fetch
     * (single-flight). A fetch that started before [invalidate] on its key
     * does not put its stale result back afterwards (M18).
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> readThrough(
        key: String,
        ttlMs: Long,
        fetch: suspend () -> T,
    ): Result<T> {
        peek(key, ttlMs)?.let { return Result.success(it as T) }
        val capturedEpoch = epoch.get()
        val capturedGeneration = keyGenerations[key] ?: 0L

        // Join an in-flight fetch for the same key instead of duplicating it.
        inFlight[key]?.let { join ->
            return join.await().let { result ->
                if (result.isSuccess) {
                    Result.success(result.getOrNull() as T)
                } else {
                    Result.failure(result.exceptionOrNull() ?: IllegalStateException("fetch failed"))
                }
            }
        }
        val gate = CompletableDeferred<Result<Any?>>()
        val winner = inFlight.putIfAbsent(key, gate) ?: gate
        if (winner !== gate) {
            return winner.await().let { result ->
                if (result.isSuccess) {
                    Result.success(result.getOrNull() as T)
                } else {
                    Result.failure(result.exceptionOrNull() ?: IllegalStateException("fetch failed"))
                }
            }
        }
        try {
            val result = try {
                Result.success(fetch())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            // Skip the put when the key was invalidated (or the whole cache
            // cleared) while the fetch was in flight: the caller asked for
            // fresh data, not a restored stale value.
            if (capturedEpoch == epoch.get() &&
                capturedGeneration == (keyGenerations[key] ?: 0L)
            ) {
                put(key, result.getOrNull(), ttlMs)
            }
            gate.complete(result)
            return result
        } finally {
            inFlight.remove(key, gate)
        }
    }

    private fun pruneExpiredLocked(currentTime: Long) {
        entries.entries
            .filter { currentTime > it.value.expiresAt }
            .map { it.key }
            .forEach(::removeLocked)
    }

    private fun trimLocked() {
        val iterator = entries.entries.iterator()
        while ((entries.size > maxEntries || totalWeight > maxWeight) && iterator.hasNext()) {
            val entry = iterator.next()
            totalWeight -= entry.value.weight
            iterator.remove()
        }
    }

    private fun removeLocked(key: String) {
        entries.remove(key)?.let { totalWeight -= it.weight }
    }

    private fun expiry(storedAt: Long, ttlMs: Long): Long {
        val safeTtl = ttlMs.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - storedAt < safeTtl) Long.MAX_VALUE else storedAt + safeTtl
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 64
        const val DEFAULT_MAX_WEIGHT = 8L * 1024L * 1024L
        const val DEFAULT_ENTRY_TTL_MS = 20_000L

        fun defaultWeight(value: Any?): Long = when (value) {
            null -> 0L
            is ByteArray -> value.size.toLong()
            is String -> value.length.toLong() * 2L
            is CharSequence -> value.length.toLong() * 2L
            is Collection<*> -> value.sumOf { defaultWeight(it) }.coerceAtMost(DEFAULT_MAX_WEIGHT)
            is Map<*, *> -> value.entries.sumOf {
                defaultWeight(it.key) + defaultWeight(it.value)
            }.coerceAtMost(DEFAULT_MAX_WEIGHT)
            else -> 256L
        }
    }
}
