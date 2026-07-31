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

package com.nousresearch.talaria.feature.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Dns
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.ui.components.ScreenScaffold
import com.nousresearch.talaria.ui.navigation.Routes
import com.nousresearch.talaria.ui.theme.LocalSpacing

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
            ManageItem("Cron", "Scheduled agent jobs", Routes.CRON, Icons.Filled.Schedule),
            ManageItem("Analytics", "Token usage & cost", Routes.ANALYTICS, Icons.Filled.BarChart),
        ),
    ),
    ManageSection(
        "Capabilities",
        listOf(
            ManageItem("Config", "Schema-driven config.yaml editor", Routes.CONFIG, Icons.Filled.Tune),
            ManageItem("API Keys", ".env secrets (catalog + redacted)", Routes.API_KEYS, Icons.Filled.Key),
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
            ManageItem("Memory", "Providers & retrieval", Routes.MEMORY, Icons.Filled.Psychology),
            ManageItem("Curator", "Automatic session upkeep", Routes.CURATOR, Icons.Filled.CleaningServices),
            ManageItem("Logs", "Agent / gateway / errors", Routes.LOGS, Icons.AutoMirrored.Filled.Article),
            ManageItem("System", "Host stats, doctor, portal", Routes.SYSTEM, Icons.Filled.Dns),
        ),
    ),
)

@Composable
fun ManageHomeScreen(onOpen: (String) -> Unit) {
    val spacing = LocalSpacing.current
    ScreenScaffold("Manage", showProfileSwitcher = true) {
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
