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

package com.hermesgadget.talaria.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerInputStateTest {
    @Test
    fun `history key separates equal session ids across profiles and connections`() {
        val profileA = ChatInputHistoryKey.forSession("connection-a", "profile-a", "shared-id")
        val profileB = ChatInputHistoryKey.forSession("connection-a", "profile-b", "shared-id")
        val connectionB = ChatInputHistoryKey.forSession("connection-b", "profile-a", "shared-id")

        assertNotEquals(profileA, profileB)
        assertNotEquals(profileA, connectionB)
        assertEquals(
            profileA,
            ChatInputHistoryKey.forSession("connection-a", "profile-a", "shared-id"),
        )
    }

    @Test
    fun `history key length prefixes prevent component boundary collisions`() {
        val first = ChatInputHistoryKey.forSession("ab", "c", "session")
        val second = ChatInputHistoryKey.forSession("a", "bc", "session")

        assertNotEquals(first, second)
    }

    @Test
    fun queuePreservesSubmissionOrderAndSkipsBlankPrompts() {
        var queue = emptyList<String>()
        queue = ComposerQueue.enqueue(queue, " first ")
        queue = ComposerQueue.enqueue(queue, "second")
        queue = ComposerQueue.enqueue(queue, "   ")

        val first = ComposerQueue.dequeue(queue)
        val second = ComposerQueue.dequeue(first.second)

        assertEquals("first", first.first)
        assertEquals("second", second.first)
        assertEquals(emptyList<String>(), second.second)
    }

    @Test
    fun inputHistoryRestoresDraftAfterWalkingBackAndForward() {
        val history = InputHistoryNavigator(listOf("one", "two"))

        assertEquals("two", history.previous("current draft"))
        assertEquals("one", history.previous("two"))
        assertEquals("two", history.next())
        assertEquals("current draft", history.next())
        assertNull(history.next())
    }

    @Test
    fun inputHistoryKeepsOnlyTheLastFiftyEntries() {
        val history = InputHistoryNavigator()
        repeat(55) { history.record("draft-$it") }

        assertEquals(50, history.snapshot.size)
        assertEquals("draft-5", history.snapshot.first())
        assertEquals("draft-54", history.snapshot.last())
    }

    @Test
    fun emptyHistoryDoesNotInterceptNavigation() {
        val history = InputHistoryNavigator()

        assertNull(history.previous("draft"))
        assertNull(history.next())
    }
}
