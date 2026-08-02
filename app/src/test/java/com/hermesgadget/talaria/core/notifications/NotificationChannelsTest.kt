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

package com.hermesgadget.talaria.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationChannelsTest {
    @Test
    fun agentIdMappingIsStableAndUsesFixedSlots() {
        val first = NotificationChannels.channelForAgent("session-alpha")

        assertEquals(first, NotificationChannels.channelForAgent("session-alpha"))
        assertTrue(NotificationChannels.agentChannelSlots.dropLast(1).contains(first))
    }

    @Test
    fun blankAgentIdUsesFallbackChannel() {
        val fallback = NotificationChannels.agentChannelSlots.last()

        assertEquals(fallback, NotificationChannels.channelForAgent(null))
        assertEquals(fallback, NotificationChannels.channelForAgent("   "))
    }

    @Test
    fun channelSlotsHaveUserVisibleNames() {
        assertEquals(
            listOf("Agent 1", "Agent 2", "Agent 3", "Agent 4", "Other agents"),
            NotificationChannels.agentChannelSlots.map(AgentNotificationChannel::displayName),
        )
    }
}
