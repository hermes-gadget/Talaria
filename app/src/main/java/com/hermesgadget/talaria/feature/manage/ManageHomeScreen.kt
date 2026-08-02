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

package com.hermesgadget.talaria.feature.manage

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.hermesgadget.talaria.R
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import com.hermesgadget.talaria.ui.navigation.Routes
import com.hermesgadget.talaria.ui.theme.LocalSpacing

private data class ManageItem(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val route: String,
    val icon: ImageVector,
)

private data class ManageSection(@StringRes val titleRes: Int, val items: List<ManageItem>)

private data class LocalizedManageItem(
    val section: String,
    val item: ManageItem,
    val title: String,
    val subtitle: String,
)

private val manageSections = listOf(
    ManageSection(
        R.string.manage_agents,
        listOf(
            ManageItem(R.string.manage_status_title, R.string.manage_status_subtitle, Routes.STATUS, Icons.Filled.MonitorHeart),
            ManageItem(R.string.manage_command_center_title, R.string.manage_command_center_subtitle, Routes.COMMAND_CENTER, Icons.Filled.Dashboard),
            ManageItem(R.string.manage_sessions_title, R.string.manage_sessions_subtitle, Routes.SESSIONS, Icons.Filled.Forum),
            ManageItem(R.string.manage_artifacts_title, R.string.manage_artifacts_subtitle, Routes.ARTIFACTS, Icons.Filled.Folder),
            ManageItem(R.string.manage_cron_title, R.string.manage_cron_subtitle, Routes.CRON, Icons.Filled.Schedule),
            ManageItem(R.string.manage_analytics_title, R.string.manage_analytics_subtitle, Routes.ANALYTICS, Icons.Filled.BarChart),
        ),
    ),
    ManageSection(
        R.string.manage_capabilities,
        listOf(
            ManageItem(R.string.manage_config_title, R.string.manage_config_subtitle, Routes.CONFIG, Icons.Filled.Tune),
            ManageItem(R.string.manage_api_keys_title, R.string.manage_api_keys_subtitle, Routes.API_KEYS, Icons.Filled.Key),
            ManageItem(R.string.manage_models_title, R.string.manage_models_subtitle, Routes.MODELS, Icons.Filled.SmartToy),
            ManageItem(R.string.manage_skills_title, R.string.manage_skills_subtitle, Routes.SKILLS, Icons.Filled.AutoAwesome),
            ManageItem(R.string.manage_mcp_title, R.string.manage_mcp_subtitle, Routes.MCP, Icons.Filled.Hub),
        ),
    ),
    ManageSection(
        R.string.manage_messaging,
        listOf(
            ManageItem(R.string.manage_channels_title, R.string.manage_channels_subtitle, Routes.CHANNELS, Icons.Filled.Campaign),
            ManageItem(R.string.manage_pairing_title, R.string.manage_pairing_subtitle, Routes.PAIRING, Icons.Filled.Link),
            ManageItem(R.string.manage_webhooks_title, R.string.manage_webhooks_subtitle, Routes.WEBHOOKS, Icons.Filled.Webhook),
        ),
    ),
    ManageSection(
        R.string.manage_system_section,
        listOf(
            ManageItem(R.string.manage_profiles_title, R.string.manage_profiles_subtitle, Routes.PROFILES, Icons.Filled.SwitchAccount),
            ManageItem(R.string.manage_files_title, R.string.manage_files_subtitle, Routes.FILES, Icons.Filled.Folder),
            ManageItem(R.string.manage_review_title, R.string.manage_review_subtitle, Routes.REVIEW, Icons.Filled.Code),
            ManageItem(R.string.manage_memory_title, R.string.manage_memory_subtitle, Routes.MEMORY, Icons.Filled.Psychology),
            ManageItem(R.string.manage_learning_title, R.string.manage_learning_subtitle, Routes.LEARNING, Icons.Filled.Insights),
            ManageItem(R.string.manage_curator_title, R.string.manage_curator_subtitle, Routes.CURATOR, Icons.Filled.CleaningServices),
            ManageItem(R.string.manage_logs_title, R.string.manage_logs_subtitle, Routes.LOGS, Icons.AutoMirrored.Filled.Article),
            ManageItem(R.string.manage_terminal_title, R.string.manage_terminal_subtitle, Routes.TERMINAL, Icons.Filled.Terminal),
            ManageItem(R.string.manage_themes_title, R.string.manage_themes_subtitle, Routes.THEMES, Icons.Filled.Palette),
            ManageItem(R.string.manage_system_title, R.string.manage_system_subtitle, Routes.SYSTEM, Icons.Filled.Dns),
        ),
    ),
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ManageHomeScreen(onOpen: (String) -> Unit) {
    val spacing = LocalSpacing.current
    var paletteOpen by remember { mutableStateOf(false) }

    if (paletteOpen) {
        CommandPalette(
            onDismiss = { paletteOpen = false },
            onOpen = { route ->
                paletteOpen = false
                onOpen(route)
            },
        )
    }

    ScreenScaffold(
        stringResource(R.string.manage_title),
        showProfileSwitcher = true,
        actions = {
            IconButton(onClick = { paletteOpen = true }) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.manage_search_settings),
                )
            }
        },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            manageSections.forEach { section ->
                item(key = "h-${section.titleRes}") {
                    Text(
                        stringResource(section.titleRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(
                            start = spacing.xs,
                            top = spacing.md,
                            bottom = spacing.xs,
                        ),
                    )
                }
                items(section.items, key = { it.route }) { item ->
                    ManageRow(item, onClick = { onOpen(item.route) })
                }
            }
        }
    }
}

@Composable
private fun ManageRow(item: ManageItem, onClick: () -> Unit) {
    val spacing = LocalSpacing.current
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = spacing.md),
            ) {
                Text(stringResource(item.titleRes), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(item.subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Quick-jump command palette (roadmap 15.7). A searchable, fuzzy-filtered list of
 * every Manage destination so users can reach any settings screen in two taps
 * instead of scrolling the grouped menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandPalette(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = mutableListOf<LocalizedManageItem>()
    for (section in manageSections) {
        for (item in section.items) {
            all += LocalizedManageItem(
                section = stringResource(section.titleRes),
                item = item,
                title = stringResource(item.titleRes),
                subtitle = stringResource(item.subtitleRes),
            )
        }
    }
    val q = query.trim()
    val results = if (q.isEmpty()) {
        all
    } else {
        all.filter { result ->
            result.title.contains(q, ignoreCase = true) ||
                result.subtitle.contains(q, ignoreCase = true) ||
                result.section.contains(q, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.manage_jump_to_setting)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (results.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.manage_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(results, key = { it.item.route }) { result ->
                val item = result.item
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(item.route) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(result.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${result.section} · ${result.subtitle}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
