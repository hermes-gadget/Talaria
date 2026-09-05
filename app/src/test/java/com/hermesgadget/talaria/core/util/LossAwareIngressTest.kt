/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LossAwareIngressTest {
    private data class Item(
        val id: String,
        val retention: IngressRetention,
        val key: String? = null,
    )

    private fun ingress(capacity: Int) = LossAwareIngress<Item>(
        capacity = capacity,
        retention = { it.retention },
        coalesceKey = { it.key },
    )

    @Test
    fun `critical boundaries stay ordered while replaceable deltas keep latest value`() {
        val queue = ingress(capacity = 4)

        assertEquals(IngressOffer.ACCEPTED, queue.offer(Item("prompt", IngressRetention.LOSSLESS)))
        assertEquals(
            IngressOffer.ACCEPTED,
            queue.offer(Item("delta-1", IngressRetention.REPLACEABLE, key = "delta")),
        )
        assertEquals(
            IngressOffer.COALESCED,
            queue.offer(Item("delta-2", IngressRetention.REPLACEABLE, key = "delta")),
        )
        assertEquals(IngressOffer.ACCEPTED, queue.offer(Item("complete", IngressRetention.LOSSLESS)))
        assertEquals(IngressOffer.ACCEPTED, queue.offer(Item("boundary", IngressRetention.LOSSLESS)))

        assertEquals(
            listOf("prompt", "delta-2", "complete", "boundary"),
            buildList {
                while (true) add(queue.poll()?.id ?: break)
            },
        )
        assertEquals(1, queue.metrics().coalescedReplaceable)
        assertEquals(3, queue.metrics().acceptedLossless)
    }

    @Test
    fun `replaceable overflow is counted without evicting critical items`() {
        val queue = ingress(capacity = 3)
        queue.offer(Item("status-old", IngressRetention.REPLACEABLE, key = "status"))
        queue.offer(Item("start", IngressRetention.LOSSLESS))
        queue.offer(Item("complete", IngressRetention.LOSSLESS))

        assertEquals(
            IngressOffer.ACCEPTED,
            queue.offer(Item("progress-new", IngressRetention.REPLACEABLE, key = "progress")),
        )
        assertEquals(
            IngressOffer.REJECTED_LOSSLESS,
            queue.offer(Item("prompt-over-bound", IngressRetention.LOSSLESS)),
        )

        assertEquals(
            listOf("start", "complete", "progress-new"),
            buildList {
                while (true) add(queue.poll()?.id ?: break)
            },
        )
        val metrics = queue.metrics()
        assertEquals(2, metrics.acceptedLossless)
        assertEquals(2, metrics.acceptedReplaceable)
        assertEquals(1, metrics.droppedReplaceable)
        assertEquals(1, metrics.rejectedLossless)
        assertEquals(3, metrics.highWaterMark)
        assertEquals(3, metrics.capacity)
        assertFalse(metrics.toString().contains("status-old"))
        assertTrue(queue.poll() == null)
    }

    @Test
    fun `B14 merged coalesce accumulates ordered text instead of dropping it`() {
        data class Delta(val sessionId: String, val text: String)

        val queue = LossAwareIngress<Delta>(
            capacity = 8,
            retention = { IngressRetention.REPLACEABLE },
            coalesceKey = { it.sessionId },
            merge = { old, new -> Delta(old.sessionId, old.text + new.text) },
        )
        queue.offer(Delta("s1", "Hello, "))
        assertEquals(IngressOffer.COALESCED, queue.offer(Delta("s1", "wor")))
        assertEquals(IngressOffer.COALESCED, queue.offer(Delta("s1", "ld!")))

        val drained = buildList {
            while (true) add(queue.poll() ?: break)
        }
        // Earlier chunks survive: nothing was replaced away.
        assertEquals(listOf(Delta("s1", "Hello, world!")), drained)
    }

    @Test
    fun `B14 merge returning null falls back to replacement`() {
        data class Delta(val sessionId: String, val text: String)

        val queue = LossAwareIngress<Delta>(
            capacity = 8,
            retention = { IngressRetention.REPLACEABLE },
            coalesceKey = { it.sessionId },
            merge = { _, _ -> null },
        )
        queue.offer(Delta("s1", "old"))
        assertEquals(IngressOffer.COALESCED, queue.offer(Delta("s1", "new")))

        val drained = buildList {
            while (true) add(queue.poll() ?: break)
        }
        assertEquals(listOf(Delta("s1", "new")), drained)
    }

    @Test
    fun `B14 merge overflow beyond budget evicts and reports drop`() {
        data class Delta(val sessionId: String, val text: String)

        val queue = LossAwareIngress<Delta>(
            capacity = 2,
            retention = { IngressRetention.REPLACEABLE },
            coalesceKey = { it.sessionId },
            merge = { old, new -> Delta(old.sessionId, old.text + new.text) },
            sizeOf = { it.text.length },
            mergeBudget = 8,
        )
        queue.offer(Delta("s1", "123456"))
        // Merged length 12 exceeds budget 8: the merged result must be refused.
        assertEquals(
            IngressOffer.DROPPED_REPLACEABLE,
            queue.offer(Delta("s1", "789012")),
        )
        assertEquals(1, queue.metrics().droppedReplaceable)
    }
}
