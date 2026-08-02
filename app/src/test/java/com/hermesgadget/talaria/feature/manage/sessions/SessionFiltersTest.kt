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
package com.hermesgadget.talaria.feature.manage.sessions

import com.hermesgadget.talaria.domain.model.SessionSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFiltersTest {

    @Test
    fun `All tab matches everything including blank sources`() {
        assertTrue(SessionFilters.matchesTab(null, SessionTab.All))
        assertTrue(SessionFilters.matchesTab("", SessionTab.All))
        assertTrue(SessionFilters.matchesTab("discord", SessionTab.All))
        assertTrue(SessionFilters.matchesTab("cron", SessionTab.All))
    }

    @Test
    fun `Automation tab matches cron webhook and automation sources`() {
        assertTrue(SessionFilters.matchesTab("cron", SessionTab.Automation))
        assertTrue(SessionFilters.matchesTab("webhook", SessionTab.Automation))
        assertTrue(SessionFilters.matchesTab("automation", SessionTab.Automation))
        assertTrue(SessionFilters.matchesTab("cron:0 9 * * *", SessionTab.Automation))
    }

    @Test
    fun `Automation tab excludes chat sources and blanks`() {
        assertFalse(SessionFilters.matchesTab("discord", SessionTab.Automation))
        assertFalse(SessionFilters.matchesTab("cli", SessionTab.Automation))
        assertFalse(SessionFilters.matchesTab("api", SessionTab.Automation))
        assertFalse(SessionFilters.matchesTab(null, SessionTab.Automation))
        assertFalse(SessionFilters.matchesTab("", SessionTab.Automation))
    }

    @Test
    fun `Chats tab matches interactive sources only`() {
        assertTrue(SessionFilters.matchesTab("discord", SessionTab.Chats))
        assertTrue(SessionFilters.matchesTab("cli", SessionTab.Chats))
        assertTrue(SessionFilters.matchesTab("telegram", SessionTab.Chats))
        assertTrue(SessionFilters.matchesTab(null, SessionTab.Chats))
    }

    @Test
    fun `Chats tab excludes automation sources`() {
        assertFalse(SessionFilters.matchesTab("cron", SessionTab.Chats))
        assertFalse(SessionFilters.matchesTab("webhook", SessionTab.Chats))
        assertFalse(SessionFilters.matchesTab("automation", SessionTab.Chats))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(SessionFilters.matchesTab("CRON", SessionTab.Automation))
        assertTrue(SessionFilters.matchesTab("WebHook", SessionTab.Automation))
        assertTrue(SessionFilters.matchesTab("Discord", SessionTab.Chats))
    }

    @Test
    fun `pinned sessions are promoted without changing unpinned order`() {
        val sessions = listOf(
            SessionSummary(id = "first"),
            SessionSummary(id = "pinned"),
            SessionSummary(id = "last"),
        )

        assertEquals(
            listOf("pinned", "first", "last"),
            SessionFilters.prioritizePinned(sessions, setOf("pinned")).map { it.id },
        )
    }
}
