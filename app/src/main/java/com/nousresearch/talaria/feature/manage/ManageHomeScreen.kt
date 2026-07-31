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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.ui.components.ScreenScaffold
import com.nousresearch.talaria.ui.navigation.Routes
import androidx.compose.ui.Modifier

private data class ManageItem(val title: String, val subtitle: String, val route: String)

@Composable
fun ManageHomeScreen(onOpen: (String) -> Unit) {
    val items = listOf(
        ManageItem("Status", "Version, gateway, live sessions", Routes.STATUS),
        ManageItem("Sessions", "Browse, search, resume, export", Routes.SESSIONS),
        ManageItem("Config", "config.yaml form editor", Routes.CONFIG),
        ManageItem("API Keys", ".env secrets (redacted)", Routes.API_KEYS),
        ManageItem("Cron", "Scheduled agent jobs", Routes.CRON),
        ManageItem("Skills", "Toggle skills & hub", Routes.SKILLS),
        ManageItem("MCP", "Model Context Protocol servers", Routes.MCP),
        ManageItem("Channels", "Messaging platforms", Routes.CHANNELS),
        ManageItem("Pairing", "Approve messaging users", Routes.PAIRING),
        ManageItem("Webhooks", "Dynamic subscriptions", Routes.WEBHOOKS),
        ManageItem("Profiles", "Isolated Hermes homes", Routes.PROFILES),
        ManageItem("Logs", "Agent / gateway / errors", Routes.LOGS),
        ManageItem("Analytics", "Token usage & cost", Routes.ANALYTICS),
        ManageItem("System", "Host stats, gateway ops, portal", Routes.SYSTEM),
    )
    ScreenScaffold("Manage", "Dashboard surfaces adapted for mobile") {
        LazyColumn {
            items(items) { item ->
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onOpen(item.route) },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    androidx.compose.foundation.layout.Column(modifier = Modifier.padding(14.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleLarge)
                        Text(item.subtitle, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
