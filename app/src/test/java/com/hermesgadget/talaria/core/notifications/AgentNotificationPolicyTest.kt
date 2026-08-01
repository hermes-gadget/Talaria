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

import com.hermesgadget.talaria.core.network.HermesSideEvent
import com.hermesgadget.talaria.core.network.PromptKind
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNotificationPolicyTest {
    private val identity = AgentThreadIdentity("tab-1", "Research agent", "session-1")

    @Test
    fun permissionAlertUsesThreadAgentNameAndSession() {
        val alert = AgentNotificationPolicy.alert(
            identity,
            HermesSideEvent.Prompt(
                kind = PromptKind.APPROVAL,
                message = "Run the build?",
                sessionId = "session-1",
                requestId = null,
                choices = listOf("once", "deny"),
                raw = JsonObject(emptyMap()),
            ),
        ) as AgentAlert.PermissionRequired

        assertEquals("Research agent", alert.agentName)
        assertEquals("session-1", alert.sessionId)
        assertEquals("session-1", alert.notificationKey)
        assertEquals("Permission required: Run the build?", alert.body)
    }

    @Test
    fun completionAlertIncludesAgentAndFailureState() {
        val alert = AgentNotificationPolicy.alert(
            identity,
            HermesSideEvent.MessageComplete("session-1", "Build failed", "error", null, null),
        ) as AgentAlert.TaskFinished

        assertEquals("Research agent", alert.agentName)
        assertEquals("Build failed", alert.body)
        assertTrue(alert.failed)
        assertFalse(alert.background)
    }

    @Test
    fun backgroundCompletionIsReportable() {
        val alert = AgentNotificationPolicy.alert(
            identity,
            HermesSideEvent.BackgroundComplete("session-1", "bg-1", "Report ready", false),
        ) as AgentAlert.TaskFinished

        assertEquals("Report ready", alert.body)
        assertFalse(alert.failed)
        assertTrue(alert.background)
    }

    @Test
    fun unrelatedEventsDoNotAlert() {
        assertEquals(null, AgentNotificationPolicy.alert(identity, HermesSideEvent.MessageStart("session-1")))
    }
}
