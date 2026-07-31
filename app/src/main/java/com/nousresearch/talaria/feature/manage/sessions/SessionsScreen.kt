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
package com.nousresearch.talaria.feature.manage.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.SessionSummary
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SessionTab { Chats, Automation, All }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(onOpen: (String) -> Unit, onResume: (String) -> Unit) {
    val repo = TalariaApp.instance.container.hermesRepository
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(SessionTab.Chats) }
    var sourceFilter by remember { mutableStateOf("") }
    var sourceExpanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var total by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmPrune by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val knownSources = listOf(
        "" to "All sources",
        "cli" to "cli",
        "api" to "api",
        "telegram" to "telegram",
        "discord" to "discord",
        "slack" to "slack",
        "cron" to "cron",
        "webhook" to "webhook",
    )

    fun matchesTab(s: SessionSummary): Boolean {
        val src = s.source.orEmpty().lowercase()
        return when (tab) {
            SessionTab.All -> true
            SessionTab.Automation -> src.contains("cron") || src.contains("automat") || src.contains("webhook")
            SessionTab.Chats -> !(src.contains("cron") || src.contains("automat") || src.contains("webhook"))
        }
    }

    fun reload() {
        scope.launch {
            val apiSource = sourceFilter.ifBlank { null }
            repo.getSessionsPage(source = apiSource, limit = 100)
                .onSuccess { page ->
                    sessions = page.sessions.filter(::matchesTab)
                    total = page.total
                    message = null
                }
                .onFailure { message = it.message }
        }
    }

    LaunchedEffect(tab, sourceFilter) { reload() }

    LaunchedEffect(query) {
        searchJob?.cancel()
        if (query.isBlank()) {
            reload()
            return@LaunchedEffect
        }
        searchJob = scope.launch {
            delay(300)
            repo.searchSessions(query)
                .onSuccess { sessions = it.filter(::matchesTab) }
                .onFailure { message = it.message }
        }
    }

    if (confirmPrune) {
        AlertDialog(
            onDismissRequest = { confirmPrune = false },
            title = { Text("Prune sessions?") },
            text = { Text("This permanently deletes old/unused sessions on the Hermes host.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPrune = false
                    scope.launch {
                        repo.pruneSessions()
                            .onSuccess {
                                message = "Prune requested"
                                reload()
                            }
                            .onFailure { message = it.message }
                    }
                }) { Text("Prune") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPrune = false }) { Text("Cancel") }
            },
        )
    }

    ScreenScaffold("Sessions", "Browse · search · resume", actions = {
        TextButton(onClick = { reload() }) { Text("Refresh") }
        TextButton(onClick = { confirmPrune = true }) { Text("Prune") }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SessionTab.entries.forEach { t ->
                    FilterChip(
                        selected = tab == t,
                        onClick = { tab = t },
                        label = { Text(t.name) },
                    )
                }
            }
            ExposedDropdownMenuBox(
                expanded = sourceExpanded,
                onExpandedChange = { sourceExpanded = it },
            ) {
                OutlinedTextField(
                    value = knownSources.find { it.first == sourceFilter }?.second ?: sourceFilter,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Source") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sourceExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
                )
                ExposedDropdownMenu(expanded = sourceExpanded, onDismissRequest = { sourceExpanded = false }) {
                    knownSources.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                sourceFilter = value
                                sourceExpanded = false
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search sessions (FTS)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Showing ${sessions.size}" + (total?.let { " · total $it" } ?: "") +
                        " · ${tab.name.lowercase()}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(12.dp),
                )
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(modifier = Modifier.weight(1f, fill = true)) {
                items(sessions, key = { it.id }) { s ->
                    Surface(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onOpen(s.id) },
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(s.title ?: s.preview ?: s.id, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    s.source,
                                    s.model,
                                    s.message_count?.let { "$it msgs" },
                                    s.last_active,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            s.preview?.takeIf { it.isNotBlank() }?.let {
                                Text(it.take(120), style = MaterialTheme.typography.bodySmall)
                            }
                            Row {
                                TextButton(onClick = { onResume(s.id) }) { Text("Resume") }
                                TextButton(onClick = { onOpen(s.id) }) { Text("Open") }
                                TextButton(onClick = {
                                    scope.launch {
                                        repo.deleteSession(s.id)
                                            .onSuccess { reload() }
                                            .onFailure { message = it.message }
                                    }
                                }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
}
