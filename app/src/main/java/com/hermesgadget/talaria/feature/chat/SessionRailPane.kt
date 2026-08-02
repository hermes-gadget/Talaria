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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.domain.model.SessionSummary
import com.hermesgadget.talaria.feature.manage.sessions.SessionFilters
import com.hermesgadget.talaria.feature.manage.sessions.SessionTab

/** Session list rendered as a persistent side panel on expanded screens. */
@Composable
internal fun SessionRailPane(
    sessions: List<SessionSummary>,
    sessionBranchOrigins: Map<String, String>,
    openSessionIds: Set<String>,
    sessionRailTab: SessionTab,
    onTabSelect: (SessionTab) -> Unit,
    onNewSession: () -> Unit,
    onRefreshSessions: () -> Unit,
    onOpenAllSessions: () -> Unit,
    onResumeSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(304.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.chat_sessions),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onNewSession) {
                    Text(stringResource(R.string.common_new_agent), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onRefreshSessions) {
                    Text(stringResource(R.string.common_refresh), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onOpenAllSessions) {
                    Text(stringResource(R.string.common_all_sessions), style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SessionTab.entries.forEach { tab ->
                    FilterChip(
                        selected = sessionRailTab == tab,
                        onClick = { onTabSelect(tab) },
                        label = {
                            Text(
                                when (tab) {
                                    SessionTab.Chats -> stringResource(R.string.sessions_tab_chats)
                                    SessionTab.Automation -> stringResource(R.string.sessions_tab_automation)
                                    SessionTab.All -> stringResource(R.string.sessions_tab_all)
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
            HorizontalDivider()
            val filtered = sessions.filter {
                SessionFilters.matchesTab(it.source, sessionRailTab)
            }
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.chat_empty_reading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(filtered, key = { it.id }) { s ->
                        val isOpen = s.id in openSessionIds
                        val parentId = sessionBranchOrigins[s.id]
                        val parentTitle = parentId?.let { id ->
                            sessions.firstOrNull { it.id == id }?.title?.takeIf { it.isNotBlank() }
                                ?: id.take(8)
                        }
                        ListItem(
                            headlineContent = {
                                Text(
                                    (s.title ?: s.preview ?: s.id.take(8)) +
                                        if (isOpen) stringResource(R.string.chat_open_suffix) else "",
                                    color = if (isOpen) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        stringResource(
                                            R.string.chat_session_metadata,
                                            s.source ?: stringResource(R.string.chat_cli_source),
                                            s.model ?: stringResource(R.string.chat_unknown_model),
                                            s.message_count ?: 0,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    if (parentTitle != null) {
                                        Text(
                                            stringResource(R.string.chat_branch_from, parentTitle),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onResumeSession(s.id) },
                        )
                    }
                }
            }
        }
    }
}
