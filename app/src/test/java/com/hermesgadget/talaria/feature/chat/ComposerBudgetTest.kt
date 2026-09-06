package com.hermesgadget.talaria.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P06: the queued-prompt list and persisted composer history are bounded so a
 * runaway loop or a giant paste cannot grow memory / the prefs file without limit.
 */
class ComposerBudgetTest {

    @Test
    fun `queue enqueue is capped at MAX_QUEUE`() {
        var queue = listOf<String>()
        for (i in 1..ComposerQueue.MAX_QUEUE + 250) {
            queue = ComposerQueue.enqueue(queue, "p$i")
        }
        assertEquals(ComposerQueue.MAX_QUEUE, queue.size)
        // The newest prompts survive; the earliest were dropped.
        assertEquals("p${ComposerQueue.MAX_QUEUE + 250}", queue.last())
    }

    @Test
    fun `blank prompts still do not enqueue`() {
        val queue = ComposerQueue.enqueue(listOf("a"), "   ")
        assertEquals(listOf("a"), queue)
    }

    @Test
    fun `dequeue returns head and tail`() {
        val (head, tail) = ComposerQueue.dequeue(listOf("first", "second"))
        assertEquals("first", head)
        assertEquals(listOf("second"), tail)
    }

    @Test
    fun `requeueFront puts failed prompt back at front`() {
        val queue = ComposerQueue.requeueFront(listOf("b", "c"), "a")
        assertEquals(listOf("a", "b", "c"), queue)
    }

    @Test
    fun `history entry constant keeps individual prompts bounded`() {
        assertTrue("entry cap must exist and be bounded", ChatInputHistoryStore.MAX_ENTRY_CHARS in 1024..65536)
        assertTrue("namespace budget must exist", ChatInputHistoryStore.NAMESPACE_BUDGET_CHARS >= ChatInputHistoryStore.MAX_ENTRY_CHARS * 10)
    }
}
