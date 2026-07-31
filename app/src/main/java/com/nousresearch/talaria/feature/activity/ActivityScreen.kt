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

package com.nousresearch.talaria.feature.activity

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.ui.components.ScreenScaffold
import java.text.DateFormat
import java.util.Date

private enum class ActivityFilter(val label: String, val match: (String) -> Boolean) {
    All("All", { true }),
    Pairing("Pairing", { it.contains("pair", ignoreCase = true) }),
    Cron("Cron", { it.contains("cron", ignoreCase = true) }),
    Gateway("Gateway", { it.contains("gateway", ignoreCase = true) }),
    Chat("Chat", { it.contains("chat", ignoreCase = true) || it.contains("pty", ignoreCase = true) }),
}

@Composable
fun ActivityScreen() {
    val events by TalariaApp.instance.container.hermesRepository.observeActivity()
        .collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf(ActivityFilter.All) }
    val filtered = remember(events, filter) {
        events.filter { filter.match(it.type) || filter.match(it.title) }
    }

    ScreenScaffold("Activity", "Local timeline of sync, pairing, and errors") {
        Column {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                ActivityFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            LazyColumn {
                items(filtered, key = { it.id }) { e ->
                    Surface(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(e.title, style = MaterialTheme.typography.titleMedium)
                            Text(e.body, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${e.type} · ${DateFormat.getDateTimeInstance().format(Date(e.createdAt))}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (events.isEmpty()) {
                                "No activity yet. Background sync will populate this feed."
                            } else {
                                "No events for this filter."
                            },
                        )
                    }
                }
            }
        }
    }
}
