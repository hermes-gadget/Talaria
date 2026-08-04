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
}
