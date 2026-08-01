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
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwitchAccount
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
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import com.hermesgadget.talaria.ui.navigation.Routes
import com.hermesgadget.talaria.ui.theme.LocalSpacing

private data class ManageItem(
    val title: String,
    val subtitle: String,
    val route: String,
    val icon: ImageVector,
)

private data class ManageSection(val title: String, val items: List<ManageItem>)

private val manageSections = listOf(
    ManageSection(
        "Agents",
        listOf(
            ManageItem("Status", "Version, gateway, live sessions", Routes.STATUS, Icons.Filled.MonitorHeart),
            ManageItem("Sessions", "Browse, search, resume, export", Routes.SESSIONS, Icons.Filled.Forum),
            ManageItem("Artifacts", "Images and files from agent sessions", Routes.ARTIFACTS, Icons.Filled.Folder),
            ManageItem("Cron", "Scheduled agent jobs", Routes.CRON, Icons.Filled.Schedule),
            ManageItem("Analytics", "Token usage & cost", Routes.ANALYTICS, Icons.Filled.BarChart),
        ),
    ),
    ManageSection(
        "Capabilities",
        listOf(
            ManageItem("Config", "Schema-driven config.yaml editor", Routes.CONFIG, Icons.Filled.Tune),
            ManageItem("API Keys", ".env secrets (catalog + redacted)", Routes.API_KEYS, Icons.Filled.Key),
            ManageItem("Models", "Providers & active model", Routes.MODELS, Icons.Filled.SmartToy),
            ManageItem("Skills", "Skills, toolsets & Hub", Routes.SKILLS, Icons.Filled.AutoAwesome),
            ManageItem("MCP", "Model Context Protocol servers", Routes.MCP, Icons.Filled.Hub),
        ),
    ),
    ManageSection(
        "Messaging",
        listOf(
            ManageItem("Channels", "Messaging platforms", Routes.CHANNELS, Icons.Filled.Campaign),
            ManageItem("Pairing", "Approve messaging users", Routes.PAIRING, Icons.Filled.Link),
            ManageItem("Webhooks", "Dynamic subscriptions", Routes.WEBHOOKS, Icons.Filled.Webhook),
        ),
    ),
    ManageSection(
        "System",
        listOf(
            ManageItem("Profiles", "Isolated Hermes homes", Routes.PROFILES, Icons.Filled.SwitchAccount),
            ManageItem("Files", "Browse the host filesystem", Routes.FILES, Icons.Filled.Folder),
            ManageItem("Memory", "Providers & retrieval", Routes.MEMORY, Icons.Filled.Psychology),
            ManageItem("Learning", "Skills graph & clusters", Routes.LEARNING, Icons.Filled.Insights),
            ManageItem("Curator", "Automatic session upkeep", Routes.CURATOR, Icons.Filled.CleaningServices),
            ManageItem("Logs", "Agent / gateway / errors", Routes.LOGS, Icons.AutoMirrored.Filled.Article),
            ManageItem("System", "Host stats, doctor, portal", Routes.SYSTEM, Icons.Filled.Dns),
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
        "Manage",
        showProfileSwitcher = true,
        actions = {
            IconButton(onClick = { paletteOpen = true }) {
                Icon(Icons.Filled.Search, contentDescription = "Search settings")
            }
        },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            manageSections.forEach { section ->
                item(key = "h-${section.title}") {
                    Text(
                        section.title,
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
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    item.subtitle,
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
    val all = remember { manageSections.flatMap { s -> s.items.map { s.title to it } } }
    val results = remember(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            all
        } else {
            all.filter { (section, item) ->
                item.title.contains(q, ignoreCase = true) ||
                    item.subtitle.contains(q, ignoreCase = true) ||
                    section.contains(q, ignoreCase = true)
            }
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
            placeholder = { Text("Jump to a setting…") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (results.isEmpty()) {
                item {
                    Text(
                        "No matches",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(results, key = { it.second.route }) { (section, item) ->
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
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "$section · ${item.subtitle}",
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
