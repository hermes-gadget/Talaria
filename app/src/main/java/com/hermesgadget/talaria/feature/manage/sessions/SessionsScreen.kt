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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.core.util.formatHermesTimestamp
import com.hermesgadget.talaria.domain.model.SessionStats
import com.hermesgadget.talaria.domain.model.SessionSummary
import com.hermesgadget.talaria.ui.components.CollapsibleSection
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import com.hermesgadget.talaria.ui.theme.LocalSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import java.io.ByteArrayOutputStream
import java.io.InputStream

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
    var compactSession by remember { mutableStateOf<SessionSummary?>(null) }
    var actionsExpanded by remember { mutableStateOf(false) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var requestGeneration by remember { mutableIntStateOf(0) }

    val knownSources = listOf(
        "" to stringResource(R.string.sessions_source_all),
        "cli" to stringResource(R.string.sessions_source_cli),
        "api" to stringResource(R.string.sessions_source_api),
        "telegram" to stringResource(R.string.sessions_source_telegram),
        "discord" to stringResource(R.string.sessions_source_discord),
        "slack" to stringResource(R.string.sessions_source_slack),
        "cron" to stringResource(R.string.sessions_source_cron),
        "webhook" to stringResource(R.string.sessions_source_webhook),
    )

    fun reload() {
        reloadNonce++
    }

    LaunchedEffect(tab, sourceFilter, query, reloadNonce) {
        val generation = ++requestGeneration
        val requestedTab = tab
        val requestedSource = sourceFilter
        val requestedQuery = query.trim()
        if (requestedQuery.isBlank()) {
            val apiSource = requestedSource.ifBlank { null }
            val result = repo.getSessionsPage(source = apiSource, limit = 100)
            if (generation != requestGeneration || requestedTab != tab ||
                requestedSource != sourceFilter || requestedQuery != query.trim()
            ) return@LaunchedEffect
            result
                .onSuccess { page ->
                    sessions = page.sessions.filter { SessionFilters.matchesTab(it.source, requestedTab) }
                    total = page.total
                    message = null
                }
                .onFailure { message = it.message }
            return@LaunchedEffect
        }
        delay(300)
        val result = repo.searchSessions(requestedQuery)
        if (generation != requestGeneration || requestedTab != tab ||
            requestedSource != sourceFilter || requestedQuery != query.trim()
        ) return@LaunchedEffect
        result
            .onSuccess {
                sessions = it.filter { session ->
                    SessionFilters.matchesTab(session.source, requestedTab) &&
                        (requestedSource.isBlank() || session.source.equals(requestedSource, ignoreCase = true))
                }
                // Search has no authoritative page total; do not display the
                // previous unfiltered count alongside these results.
                total = null
                message = null
            }
            .onFailure { message = it.message }
    }

    val importFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(::readBoundedImport)
                        ?: error("Could not read selected file")
                }
                withContext(Dispatchers.Default) {
                    parseImportSessions(
                        JsonConfig.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)),
                    )
                }
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
                    // N0.8: reload happens only after the delete is
                    // acknowledged (adminVm.bulkDeleteSelected -> loadSnapshot);
                    // reloading here races the async delete and can resurrect
                    // the just-deleted rows in the UI.
                    adminVm.bulkDeleteSelected()
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
                    // N0.8: reload only after the delete is acknowledged.
                    adminVm.deleteEmpty()
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

    compactSession?.let { session ->
        AlertDialog(
            onDismissRequest = { compactSession = null },
            title = { Text(stringResource(R.string.sessions_compact_title)) },
            text = { Text(stringResource(R.string.sessions_compact_body)) },
            confirmButton = {
                TextButton(onClick = {
                    compactSession = null
                    adminVm.compactSession(session.id) { reload() }
                }) { Text(stringResource(R.string.sessions_compact)) }
            },
            dismissButton = {
                TextButton(onClick = { compactSession = null }) {
                    Text(stringResource(R.string.sessions_cancel))
                }
            },
        )
    }

    val adminContent = (adminUi as? SessionAdminUiState.Content)?.value
    val visibleSessions = SessionFilters.prioritizePinned(sessions, adminContent?.pinnedIds.orEmpty())
    ScreenScaffold(
        stringResource(R.string.sessions_title),
        stringResource(R.string.sessions_subtitle),
        actions = {
            Box {
                IconButton(onClick = { actionsExpanded = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.sessions_more_actions),
                    )
                }
                DropdownMenu(
                    expanded = actionsExpanded,
                    onDismissRequest = { actionsExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessions_import)) },
                        onClick = {
                            actionsExpanded = false
                            importFileLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessions_refresh)) },
                        onClick = {
                            actionsExpanded = false
                            adminVm.refresh()
                            reload()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessions_prune)) },
                        onClick = {
                            actionsExpanded = false
                            confirmPrune = true
                        },
                    )
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.itemGap)) {
            (adminUi as? SessionAdminUiState.Failure)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error)
            }
            adminContent?.message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
            importMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            CollapsibleSection(stringResource(R.string.sessions_overview), collapsible = true) {
                adminContent?.stats?.let { StatsCards(it) }
            }
            CollapsibleSection(stringResource(R.string.sessions_administration), collapsible = true) {
                AdminActions(
                    admin = adminContent,
                    visibleIds = visibleSessions.map { it.id },
                    onSelectAll = { adminVm.selectAll(visibleSessions.map { it.id }) },
                    onClearSelection = adminVm::clearSelection,
                    onBulkDelete = { confirmBulkDelete = true },
                    onDeleteEmpty = { confirmEmptyDelete = true },
                )
            }
            CollapsibleSection(stringResource(R.string.sessions_filters)) {
                // Single compact filter row: tab chips + source dropdown chip.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    SessionTab.entries.forEach { t ->
                        FilterChip(
                            selected = tab == t,
                            onClick = { tab = t },
                            label = { Text(sessionTabLabel(t)) },
                        )
                    }
                    Box {
                        FilterChip(
                            selected = sourceFilter.isNotBlank(),
                            onClick = { sourceExpanded = true },
                            label = {
                                Text(knownSources.find { it.first == sourceFilter }?.second
                                    ?: stringResource(R.string.sessions_source_all))
                            },
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
                    label = { Text(stringResource(R.string.sessions_search)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                val totalCount = total
                val totalLabel = if (totalCount == null) {
                    ""
                } else {
                    stringResource(R.string.sessions_total, totalCount)
                }
                Text(
                    stringResource(
                        R.string.sessions_showing,
                        visibleSessions.size,
                        totalLabel,
                        sessionTabLabel(tab).lowercase(),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(modifier = Modifier.weight(1f, fill = true)) {
                items(visibleSessions, key = { it.id }) { session ->
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        session.title ?: session.preview ?: session.id,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (session.id in adminContent?.pinnedIds.orEmpty()) {
                                        Text(
                                            stringResource(R.string.sessions_pinned),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
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
                            }
                            SessionOverflowMenu(
                                pinned = session.id in adminContent?.pinnedIds.orEmpty(),
                                enabled = adminContent?.busy == false,
                                onResume = { onResume(session.id) },
                                onOpen = { onOpen(session.id) },
                                onTogglePin = { adminVm.togglePinned(session.id) },
                                onCompact = { compactSession = session },
                                onDelete = { deleteSession = session },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionOverflowMenu(
    pinned: Boolean,
    enabled: Boolean,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onCompact: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled,
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.sessions_more_actions),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessions_resume)) },
                onClick = { expanded = false; onResume() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessions_open)) },
                onClick = { expanded = false; onOpen() },
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(if (pinned) R.string.sessions_unpin else R.string.sessions_pin))
                },
                onClick = { expanded = false; onTogglePin() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessions_compact)) },
                onClick = { expanded = false; onCompact() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessions_delete)) },
                onClick = { expanded = false; onDelete() },
            )
        }
    }
}

@Composable
private fun sessionTabLabel(tab: SessionTab): String = stringResource(
    when (tab) {
        SessionTab.Chats -> R.string.sessions_tab_chats
        SessionTab.Automation -> R.string.sessions_tab_automation
        SessionTab.All -> R.string.sessions_tab_all
    },
)

@Composable
private fun StatsCards(stats: SessionStats) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            stringResource(R.string.sessions_stat_total) to stats.total,
            stringResource(R.string.sessions_stat_active_store) to stats.activeStore,
            stringResource(R.string.sessions_stat_archived) to stats.archived,
            stringResource(R.string.sessions_stat_messages) to stats.messages,
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
        TextButton(onClick = onSelectAll, enabled = visibleIds.isNotEmpty() && !admin.busy) {
            Text(stringResource(R.string.sessions_select_all))
        }
        TextButton(onClick = onClearSelection, enabled = selectedCount > 0 && !admin.busy) {
            Text(stringResource(R.string.sessions_clear))
        }
        TextButton(onClick = onBulkDelete, enabled = selectedCount > 0 && !admin.busy) {
            Text(stringResource(R.string.sessions_delete_selected, selectedCount))
        }
        TextButton(onClick = onDeleteEmpty, enabled = (admin.emptyCount ?: 0) > 0 && !admin.busy) {
            Text(stringResource(R.string.sessions_delete_empty, admin.emptyCount ?: 0))
        }
    }
}

private const val MAX_IMPORT_BYTES = 25 * 1024 * 1024

/** Reads no more than the advertised limit plus one byte, so oversize input is rejected early. */
private fun readBoundedImport(input: InputStream): ByteArray {
    val maxRead = MAX_IMPORT_BYTES + 1
    val buffer = ByteArray(8 * 1024)
    val output = ByteArrayOutputStream(buffer.size)
    var total = 0
    while (total < maxRead) {
        val read = input.read(buffer, 0, minOf(buffer.size, maxRead - total))
        if (read < 0) break
        if (read == 0) {
            val single = input.read()
            if (single < 0) break
            output.write(single)
            total++
        } else {
            output.write(buffer, 0, read)
            total += read
        }
    }
    require(total <= MAX_IMPORT_BYTES) { "Selected file is larger than 25 MB" }
    return output.toByteArray()
}
