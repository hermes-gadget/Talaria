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

package com.hermesgadget.talaria.core.util

import java.util.ArrayDeque

/** Whether an ingress item may be replaced when a newer item for the same key arrives. */
enum class IngressRetention {
    LOSSLESS,
    REPLACEABLE,
}

/** Result of a non-blocking bounded ingress offer. */
enum class IngressOffer {
    ACCEPTED,
    COALESCED,
    DROPPED_REPLACEABLE,
    REJECTED_LOSSLESS,
}

/** Payload-free counters for a bounded ingress. */
data class LossAwareIngressMetrics(
    val acceptedLossless: Long,
    val acceptedReplaceable: Long,
    val coalescedReplaceable: Long,
    val droppedReplaceable: Long,
    val rejectedLossless: Long,
    val highWaterMark: Int,
    val capacity: Int,
)

/**
 * A small, synchronized FIFO for callback-based ingress.
 *
 * Lossless items are never evicted. Replaceable items keep their original FIFO
 * position while their value is updated, and the oldest replaceable item is
 * evicted before a newer replaceable item is rejected. If the lossless bound is
 * exhausted, the caller gets an explicit rejection and can apply transport
 * backpressure/closure; no critical item is silently discarded.
 *
 * B14: when [merge] is supplied, a coalesced arrival is combined with the
 * queued value instead of replacing it. Ordered incremental payloads (message
 * deltas) must accumulate: replacement loses earlier text, so the merged
 * result is stored and a merge overflow (exceeding [mergeBudget]) evicts the
 * entry and reports DROPPED_REPLACEABLE so callers can fall back to
 * authoritative reconciliation instead of presenting a truncated stream as
 * complete.
 */
class LossAwareIngress<T>(
    private val capacity: Int,
    private val retention: (T) -> IngressRetention,
    private val coalesceKey: (T) -> Any? = { null },
    private val merge: ((old: T, new: T) -> T?)? = null,
    private val sizeOf: (T) -> Int = { 1 },
    private val mergeBudget: Int = 64 * 1024,
) {
    private data class Entry<T>(
        var value: T,
        val key: Any?,
        val retention: IngressRetention,
    )

    private val queue = ArrayDeque<Entry<T>>(capacity)
    private var acceptedLossless = 0L
    private var acceptedReplaceable = 0L
    private var coalescedReplaceable = 0L
    private var droppedReplaceable = 0L
    private var rejectedLossless = 0L
    private var highWaterMark = 0

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun offer(value: T): IngressOffer {
        val itemRetention = retention(value)
        val key = coalesceKey(value)
        if (itemRetention == IngressRetention.REPLACEABLE && key != null) {
            queue.firstOrNull { it.retention == IngressRetention.REPLACEABLE && it.key == key }
                ?.let {
                    // B14: merge when a merger is configured, so ordered
                    // incremental payloads accumulate instead of being replaced
                    // (which silently loses earlier text).
                    if (merge != null && it.value != null) {
                        @Suppress("UNCHECKED_CAST")
                        val merged = merge(it.value as T, value)
                        if (merged != null) {
                            if (entrySize(merged) > mergeBudget) {
                                // B14: merged result exceeds the byte budget —
                                // evict the entry and report the loss so the
                                // consumer can reconcile authoritatively
                                // instead of presenting a truncated stream.
                                queue.remove(it)
                                droppedReplaceable += 1
                                return IngressOffer.DROPPED_REPLACEABLE
                            }
                            it.value = merged
                            coalescedReplaceable += 1
                            return IngressOffer.COALESCED
                        }
                    }
                    it.value = value
                    coalescedReplaceable += 1
                    return IngressOffer.COALESCED
                }
        }

        if (queue.size >= capacity) {
            if (itemRetention == IngressRetention.LOSSLESS) {
                rejectedLossless += 1
                return IngressOffer.REJECTED_LOSSLESS
            }
            val evicted = queue.firstOrNull { it.retention == IngressRetention.REPLACEABLE }
            if (evicted == null) {
                droppedReplaceable += 1
                return IngressOffer.DROPPED_REPLACEABLE
            }
            queue.remove(evicted)
            droppedReplaceable += 1
        }

        queue.addLast(Entry(value, key, itemRetention))
        highWaterMark = maxOf(highWaterMark, queue.size)
        if (itemRetention == IngressRetention.LOSSLESS) {
            acceptedLossless += 1
        } else {
            acceptedReplaceable += 1
        }
        return IngressOffer.ACCEPTED
    }

    @Synchronized
    fun poll(): T? = if (queue.isEmpty()) null else queue.removeFirst().value

    /** Approximate payload size for the B14 merge budget (caller-supplied). */
    private fun entrySize(value: T): Int = sizeOf(value)

    @Synchronized
    fun clear() {
        queue.clear()
    }

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun metrics(): LossAwareIngressMetrics = LossAwareIngressMetrics(
        acceptedLossless = acceptedLossless,
        acceptedReplaceable = acceptedReplaceable,
        coalescedReplaceable = coalescedReplaceable,
        droppedReplaceable = droppedReplaceable,
        rejectedLossless = rejectedLossless,
        highWaterMark = highWaterMark,
        capacity = capacity,
    )
}
