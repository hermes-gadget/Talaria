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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive

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

    // Identity of an in-flight fetch: which cache epoch (clears) and which
    // per-key invalidation generation it started in. Callers may only join an
    // in-flight fetch whose stamp matches their own view; a reader that
    // arrives after an invalidation always runs its own fetch (B08).
    private data class InFlightStamp(
        val epoch: Long,
        val generation: Long,
    )

    private class InFlight(
        val stamp: InFlightStamp,
        val gate: CompletableDeferred<Result<Any?>>,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var totalWeight = 0L

    // M18/B08: single-flight + invalidation stamping. readThrough callers with
    // the same key share one fetch; a fetch that started before invalidate()
    // must not put its stale result back after the invalidation.
    private val inFlight = HashMap<String, InFlight>()
    private val keyGenerations = HashMap<String, Long>()
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
        put(key, value, ttlMs, observedEpoch = null)
    }

    /**
     * B77: epoch-checked store. An in-flight fetch that began before
     * [clear] (or an epoch invalidation) must not repopulate the cache
     * with pre-clear data. Pass [observedEpoch] captured when the fetch
     * started; a stale epoch is dropped silently (the fetch's caller will
     * simply miss the cache and refetch next time).
     */
    fun put(key: String, value: Any?, ttlMs: Long = DEFAULT_ENTRY_TTL_MS, observedEpoch: Long?) {
        if (observedEpoch != null && observedEpoch != epoch.get()) {
            return
        }
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
        bumpGenerationLocked(key)
        removeLocked(key)
    }

    /** Drop keys whose names begin with [prefix]. */
    fun invalidatePrefix(prefix: String) = synchronized(lock) {
        entries.keys.filter { it.startsWith(prefix) }.forEach(::removeLocked)
        // B08: bump every matching key we know about AND every matching key
        // that currently has a fetch in flight — a first-time fetch has no
        // generation entry yet, so keyGenerations alone would miss it.
        (keyGenerations.keys.asSequence() + inFlight.keys.asSequence())
            .filter { it.startsWith(prefix) }
            .toSet()
            .forEach(::bumpGenerationLocked)
    }

    /** Drop keys matching an arbitrary predicate, useful for deleted scopes. */
    fun invalidateWhere(predicate: (String) -> Boolean) = synchronized(lock) {
        entries.keys.filter(predicate).forEach(::removeLocked)
        (keyGenerations.keys.asSequence() + inFlight.keys.asSequence())
            .filter(predicate)
            .toSet()
            .forEach(::bumpGenerationLocked)
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

    /** B77: current clear/invalidation epoch, for stale-write rejection. */
    val currentEpoch: Long
        get() = epoch.get()

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
     * (single-flight), but only when the in-flight fetch started in the same
     * invalidation generation: a reader that arrives after [invalidate] (or
     * any prefix/predicate invalidation, or a [clear]) runs its own fetch
     * instead of joining pre-invalidation work (B08).
     *
     * If the fetch leader is cancelled, its gate is completed exceptionally so
     * joined callers fail (and can retry) instead of waiting forever (B07).
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> readThrough(
        key: String,
        ttlMs: Long,
        fetch: suspend () -> T,
    ): Result<T> {
        peek(key, ttlMs)?.let { return Result.success(it as T) }

        val stamp: InFlightStamp
        val gate: CompletableDeferred<Result<Any?>>
        val isLeader: Boolean
        synchronized(lock) {
            stamp = InFlightStamp(epoch.get(), keyGenerations[key] ?: 0L)
            val existing = inFlight[key]
            if (existing != null && existing.stamp == stamp) {
                gate = existing.gate
                isLeader = false
            } else {
                gate = CompletableDeferred()
                inFlight[key] = InFlight(stamp, gate)
                isLeader = true
            }
        }

        if (!isLeader) {
            return awaitJoinedResult<T>(gate)
        }

        try {
            val result = try {
                Result.success(fetch())
            } catch (cancelled: CancellationException) {
                // B07: every leader exit — success, failure, or cancellation —
                // must release joined callers. Complete the gate
                // exceptionally, then rethrow our own cancellation.
                gate.completeExceptionally(cancelled)
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            // Skip the put when the key was invalidated (or the whole cache
            // cleared) while the fetch was in flight: the caller asked for
            // fresh data, not a restored stale value. The stamp comparison and
            // the store happen under the same lock so an invalidation cannot
            // slip between them (B08).
            val mayStore = synchronized(lock) {
                stamp.epoch == epoch.get() && stamp.generation == (keyGenerations[key] ?: 0L)
            }
            if (mayStore) {
                put(key, result.getOrNull(), ttlMs)
            }
            gate.complete(result)
            return result
        } finally {
            synchronized(lock) {
                // Remove only if we are still the registered fetch; a newer
                // generation's leader may have replaced us.
                val registered = inFlight[key]
                if (registered != null && registered.gate === gate) {
                    inFlight.remove(key)
                }
            }
        }
    }

    /**
     * Await a joined fetch result. A leader cancellation is surfaced as a
     * failed result (joiners are free to retry); a cancellation of the joiner
     * itself still propagates.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> awaitJoinedResult(
        gate: CompletableDeferred<Result<Any?>>,
    ): Result<T> = try {
        val result = gate.await()
        if (result.isSuccess) {
            Result.success(result.getOrNull() as T)
        } else {
            Result.failure(result.exceptionOrNull() ?: IllegalStateException("fetch failed"))
        }
    } catch (cancelled: CancellationException) {
        // Rethrow only when this caller itself was cancelled; a cancelled
        // leader must not strand us in a cancelled state.
        coroutineContext.ensureActive()
        Result.failure(cancelled)
    }

    /**
     * Record an invalidation for [key]. The generation map is bounded: when it
     * would exceed [MAX_GENERATION_KEYS] distinct keys it is cleared and the
     * cache epoch is bumped, which safely invalidates every in-flight stamp
     * (they can no longer match) instead of unbounded growth (P02).
     */
    private fun bumpGenerationLocked(key: String) {
        if (keyGenerations.size >= MAX_GENERATION_KEYS && !keyGenerations.containsKey(key)) {
            keyGenerations.clear()
            epoch.incrementAndGet()
        }
        keyGenerations.merge(key, 1L, Long::plus)
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
        const val MAX_GENERATION_KEYS = 512

        /**
         * Payload-aware weight: byte arrays, strings and collections count
         * their real size; unknown objects (small DTOs) cost a fixed 256.
         * Aggregates are measured honestly — no clamping — so an entry whose
         * real payload exceeds the cache budget is rejected by the normal
         * `weight > maxWeight` check instead of clamping itself into
         * eligibility (P02).
         */
        fun defaultWeight(value: Any?): Long = when (value) {
            null -> 0L
            is ByteArray -> value.size.toLong()
            is String -> value.length.toLong() * 2L
            is CharSequence -> value.length.toLong() * 2L
            is Collection<*> -> value.sumOf { defaultWeight(it) }
            is Map<*, *> -> value.entries.sumOf {
                defaultWeight(it.key) + defaultWeight(it.value)
            }
            else -> 256L
        }
    }
}
