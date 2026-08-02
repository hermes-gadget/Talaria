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

/** Session list tabs — mirrors the web dashboard Chats / Automation / All. */
enum class SessionTab { Chats, Automation, All }

/**
 * Pure session filtering rules, extracted from the screen so they are
 * unit-testable without Compose.
 */
object SessionFilters {

    /** Source substrings that classify a session as automation, matching web. */
    val AUTOMATION_SOURCES = listOf("cron", "automat", "webhook")

    fun matchesTab(source: String?, tab: SessionTab): Boolean {
        val src = source.orEmpty().lowercase()
        return when (tab) {
            SessionTab.All -> true
            SessionTab.Automation -> AUTOMATION_SOURCES.any { src.contains(it) }
            SessionTab.Chats -> AUTOMATION_SOURCES.none { src.contains(it) }
        }
    }

    /** Keeps the server's ordering while surfacing locally pinned sessions first. */
    fun prioritizePinned(sessions: List<SessionSummary>, pinnedIds: Set<String>): List<SessionSummary> =
        sessions.sortedWith(compareByDescending { it.id in pinnedIds })
}
