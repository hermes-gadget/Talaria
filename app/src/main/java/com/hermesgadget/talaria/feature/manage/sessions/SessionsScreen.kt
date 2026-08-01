/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.manage.sessions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.core.util.formatHermesTimestamp
import com.hermesgadget.talaria.domain.model.SessionStats
import com.hermesgadget.talaria.domain.model.SessionSummary
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import com.hermesgadget.talaria.ui.theme.LocalSpacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onOpen: (String) -> Unit,
    onResume: (String) -> Unit,
    adminVm: SessionAdminViewModel = viewModel(factory = SessionAdminViewModel.factory()),
) {
    val repo = TalariaApp.instance.container.hermesRepository
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    val adminUi by adminVm.ui.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(SessionTab.Chats) }
    var sourceFilter by remember { mutableStateOf("") }
    var sourceExpanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var total by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var confirmPrune by remember { mutableStateOf(false) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var confirmEmptyDelete by remember { mutableStateOf(false) }
    var deleteSession by remember { mutableStateOf<SessionSummary?>(null) }
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

    fun reload() {
        scope.launch {
            val apiSource = sourceFilter.ifBlank { null }
            repo.getSessionsPage(source = apiSource, limit = 100)
                .onSuccess { page ->
                    sessions = page.sessions.filter { SessionFilters.matchesTab(it.source, tab) }
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
                .onSuccess { sessions = it.filter { SessionFilters.matchesTab(it.source, tab) } }
                .onFailure { message = it.message }
        }
    }

    val importFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().also { bytes ->
                        require(bytes.size <= MAX_IMPORT_BYTES) { "Selected file is larger than 25 MB" }
                    }.toString(Charsets.UTF_8)
                } ?: error("Could not read selected file")
                parseImportSessions(JsonConfig.json.parseToJsonElement(raw))
            }.onSuccess { imported ->
                importMessage = null
                adminVm.importSessions(imported)
                reload()
            }.onFailure { error -> importMessage = "Invalid session import: ${error.message}" }
        }
    }

    if (confirmPrune) {
        AlertDialog(
            onDismissRequest = { confirmPrune = false },
            title = { Text("Prune sessions?") },
            text = { Text("This permanently deletes old or unused sessions on the Hermes host.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPrune = false
                    scope.launch {
                        repo.pruneSessions()
                            .onSuccess {
                                message = "Prune requested"
                                reload()
                                adminVm.refresh()
                            }
                            .onFailure { message = it.message }
                    }
                }) { Text("Prune") }
            },
            dismissButton = { TextButton(onClick = { confirmPrune = false }) { Text("Cancel") } },
        )
    }

    if (confirmBulkDelete) {
        val selectedCount = (adminUi as? SessionAdminUiState.Content)?.value?.selectedIds?.size ?: 0
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("Delete selected sessions?") },
            text = { Text("Permanently delete $selectedCount selected session(s) from Hermes?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBulkDelete = false
                    adminVm.bulkDeleteSelected()
                    reload()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancel") } },
        )
    }

    if (confirmEmptyDelete) {
        val count = (adminUi as? SessionAdminUiState.Content)?.value?.emptyCount ?: 0
        AlertDialog(
            onDismissRequest = { confirmEmptyDelete = false },
            title = { Text("Delete empty sessions?") },
            text = { Text("Permanently delete all $count empty session(s) from Hermes?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmptyDelete = false
                    adminVm.deleteEmpty()
                    reload()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmEmptyDelete = false }) { Text("Cancel") } },
        )
    }

    deleteSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteSession = null },
            title = { Text("Delete session?") },
            text = { Text("Permanently delete '${session.title ?: session.id}' from Hermes?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteSession = null
                    scope.launch {
                        repo.deleteSession(session.id)
                            .onSuccess {
                                reload()
                                adminVm.refresh()
                            }
                            .onFailure { message = it.message }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteSession = null }) { Text("Cancel") } },
        )
    }

    val adminContent = (adminUi as? SessionAdminUiState.Content)?.value
    ScreenScaffold(
        "Sessions",
        "Browse · search · administer",
        actions = {
            TextButton(onClick = { importFileLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }) {
                Text("Import")
            }
            TextButton(onClick = { adminVm.refresh(); reload() }) { Text("Refresh") }
            TextButton(onClick = { confirmPrune = true }) { Text("Prune") }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.itemGap)) {
            adminContent?.stats?.let { StatsCards(it) }
            AdminActions(
                admin = adminContent,
                visibleIds = sessions.map { it.id },
                onSelectAll = { adminVm.selectAll(sessions.map { it.id }) },
                onClearSelection = adminVm::clearSelection,
                onBulkDelete = { confirmBulkDelete = true },
                onDeleteEmpty = { confirmEmptyDelete = true },
            )
            (adminUi as? SessionAdminUiState.Failure)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error)
            }
            adminContent?.message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
            importMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            // Single compact filter row: tab chips + source dropdown chip.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                SessionTab.entries.forEach { t ->
                    FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.name) })
                }
                Box {
                    FilterChip(
                        selected = sourceFilter.isNotBlank(),
                        onClick = { sourceExpanded = true },
                        label = { Text(knownSources.find { it.first == sourceFilter }?.second ?: "All sources") },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    )
                    DropdownMenu(expanded = sourceExpanded, onDismissRequest = { sourceExpanded = false }) {
                        knownSources.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { sourceFilter = value; sourceExpanded = false },
                            )
                        }
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
            Text(
                "Showing ${sessions.size}" + (total?.let { " · total $it" } ?: "") +
                    " · ${tab.name.lowercase()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(modifier = Modifier.weight(1f, fill = true)) {
                items(sessions, key = { it.id }) { session ->
                    val selected = adminContent?.selectedIds?.contains(session.id) == true
                    Surface(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { adminVm.setSelected(session.id, it) },
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable { onOpen(session.id) }
                                    .padding(4.dp),
                            ) {
                                Text(session.title ?: session.preview ?: session.id, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    listOfNotNull(
                                        session.source,
                                        session.model,
                                        session.message_count?.let { "$it msgs" },
                                        formatHermesTimestamp(session.last_active),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                session.preview?.takeIf { it.isNotBlank() }?.let {
                                    Text(it.take(120), style = MaterialTheme.typography.bodySmall)
                                }
                                Row {
                                    TextButton(onClick = { onResume(session.id) }) { Text("Resume") }
                                    TextButton(onClick = { onOpen(session.id) }) { Text("Open") }
                                    TextButton(onClick = { deleteSession = session }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCards(stats: SessionStats) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            "Total" to stats.total,
            "Active store" to stats.activeStore,
            "Archived" to stats.archived,
            "Messages" to stats.messages,
        ).forEach { (label, value) ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                    Text(value.toString(), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
    if (stats.bySource.isNotEmpty()) {
        Text(
            stats.bySource.entries.sortedBy { it.key }.joinToString(" · ") { "${it.key}: ${it.value}" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AdminActions(
    admin: SessionAdminContent?,
    visibleIds: List<String>,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onBulkDelete: () -> Unit,
    onDeleteEmpty: () -> Unit,
) {
    if (admin == null) return
    val selectedCount = admin.selectedIds.size
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onSelectAll, enabled = visibleIds.isNotEmpty() && !admin.busy) { Text("Select all") }
        TextButton(onClick = onClearSelection, enabled = selectedCount > 0 && !admin.busy) { Text("Clear") }
        TextButton(onClick = onBulkDelete, enabled = selectedCount > 0 && !admin.busy) {
            Text("Delete selected ($selectedCount)")
        }
        TextButton(onClick = onDeleteEmpty, enabled = (admin.emptyCount ?: 0) > 0 && !admin.busy) {
            Text("Delete empty (${admin.emptyCount ?: 0})")
        }
    }
}

private const val MAX_IMPORT_BYTES = 25 * 1024 * 1024
