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
import com.hermesgadget.talaria.core.data.repo.SavedSessionFilter
import com.hermesgadget.talaria.core.data.repo.SessionOrganizationSnapshot

/** Session list tabs — mirrors the web dashboard Chats / Automation / All. */
enum class SessionTab { Chats, Automation, All }

/** Local-only organization chips shown alongside the server-backed list filters. */
sealed interface SessionOrganizationFilter {
    data object All : SessionOrganizationFilter
    data object Pinned : SessionOrganizationFilter
    data object Favorites : SessionOrganizationFilter
    data class Saved(val id: Long) : SessionOrganizationFilter
}

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

    fun matchesOrganizationFilter(
        session: SessionSummary,
        filter: SessionOrganizationFilter,
        pinnedIds: Set<String>,
        organization: SessionOrganizationSnapshot,
    ): Boolean = when (filter) {
        SessionOrganizationFilter.All -> true
        SessionOrganizationFilter.Pinned -> session.id in pinnedIds
        SessionOrganizationFilter.Favorites -> session.id in organization.favoriteSessionIds
        is SessionOrganizationFilter.Saved -> organization.savedFilters
            .firstOrNull { it.id == filter.id }
            ?.let { matchesSavedFilter(session, it, organization.collectionIdsFor(session.id)) }
            ?: false
    }

    /** A saved filter is an AND of its optional remote and local predicates. */
    fun matchesSavedFilter(
        session: SessionSummary,
        filter: SavedSessionFilter,
        assignedCollectionIds: Set<Long>,
    ): Boolean = matchesIgnoreCase(session.source, filter.source) &&
        matchesIgnoreCase(session.platform, filter.platform) &&
        matchesIgnoreCase(session.end_reason, filter.endReason) &&
        (filter.labelId == null || filter.labelId in assignedCollectionIds) &&
        (filter.groupId == null || filter.groupId in assignedCollectionIds)

    private fun matchesIgnoreCase(actual: String?, expected: String?): Boolean =
        expected.isNullOrBlank() || actual.equals(expected, ignoreCase = true)
}
