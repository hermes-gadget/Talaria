/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.manage.kanban

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.ui.components.CollapsibleSection
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.hermesgadget.talaria.core.util.suspendResult


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KanbanScreen(vm: KanbanViewModel = viewModel(factory = KanbanViewModel.factory())) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val taskState by vm.task.collectAsStateWithLifecycle()
    val runState by vm.run.collectAsStateWithLifecycle()
    var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var showBoardDialog by rememberSaveable { mutableStateOf(false) }
    var createTaskStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTaskId by rememberSaveable { mutableStateOf<String?>(null) }

    var boardSlug by rememberSaveable { mutableStateOf("") }
    var boardName by rememberSaveable { mutableStateOf("") }
    var taskTitle by rememberSaveable { mutableStateOf("") }
    var taskBody by rememberSaveable { mutableStateOf("") }
    var taskAssignee by rememberSaveable { mutableStateOf("") }

    val content = when (state) {
        is KanbanUiState.Content -> (state as KanbanUiState.Content).value
        is KanbanUiState.Failure -> (state as KanbanUiState.Failure).previous
        KanbanUiState.Loading -> null
    }
    val error = (state as? KanbanUiState.Failure)?.message
    val busy = content?.busy == true

    LaunchedEffect(content?.currentBoard) {
        if (!content?.currentBoard.isNullOrBlank()) boardSlug = content!!.currentBoard
    }

    if (showBoardDialog) {
        AlertDialog(
            onDismissRequest = { showBoardDialog = false },
            title = { Text(stringResource(R.string.kanban_new_board)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = boardSlug,
                        onValueChange = { boardSlug = it },
                        label = { Text(stringResource(R.string.kanban_board_slug)) },
                        singleLine = true,
                        enabled = !busy,
                    )
                    OutlinedTextField(
                        value = boardName,
                        onValueChange = { boardName = it },
                        label = { Text(stringResource(R.string.kanban_board_name)) },
                        singleLine = true,
                        enabled = !busy,
                    )
                    error?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = boardSlug.isNotBlank() && !busy,
                    onClick = {
                        vm.createKanbanBoard(
                            slug = boardSlug.trim(),
                            name = boardName.trim(),
                            switch = true,
                            onSuccess = {
                                showBoardDialog = false
                                boardSlug = ""
                                boardName = ""
                            },
                        )
                    },
                ) { Text(stringResource(R.string.kanban_create_board)) }
            },
            dismissButton = {
                TextButton(onClick = { showBoardDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    createTaskStatus?.let { status ->
        AlertDialog(
            onDismissRequest = { createTaskStatus = null },
            title = { Text(stringResource(R.string.kanban_create_task)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(statusLabel(status))
                    OutlinedTextField(
                        value = taskTitle,
                        onValueChange = { taskTitle = it },
                        label = { Text(stringResource(R.string.kanban_task_title)) },
                        singleLine = true,
                        enabled = !busy,
                    )
                    OutlinedTextField(
                        value = taskBody,
                        onValueChange = { taskBody = it },
                        label = { Text(stringResource(R.string.kanban_task_body)) },
                        minLines = 3,
                        enabled = !busy,
                    )
                    OutlinedTextField(
                        value = taskAssignee,
                        onValueChange = { taskAssignee = it },
                        label = { Text(stringResource(R.string.kanban_task_assignee)) },
                        singleLine = true,
                        enabled = !busy,
                    )
                    error?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = taskTitle.isNotBlank() && !busy,
                    onClick = {
                        vm.createKanbanTask(
                            title = taskTitle.trim(),
                            body = taskBody.trim(),
                            assignee = taskAssignee.trim(),
                            status = status,
                            onSuccess = {
                                createTaskStatus = null
                                taskTitle = ""
                                taskBody = ""
                                taskAssignee = ""
                            },
                        )
                    },
                ) { Text(stringResource(R.string.kanban_add_task)) }
            },
            dismissButton = {
                TextButton(onClick = { createTaskStatus = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    deleteTaskId?.let { taskId ->
        AlertDialog(
            onDismissRequest = { deleteTaskId = null },
            title = { Text(stringResource(R.string.kanban_delete_task_title)) },
            text = { Text(stringResource(R.string.kanban_delete_task_message)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        deleteTaskId = null
                        selectedTaskId = null
                        vm.deleteKanbanTask(taskId)
                    },
                ) { Text(stringResource(R.string.kanban_delete_task_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTaskId = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    ScreenScaffold(
        title = stringResource(R.string.kanban_title),
        subtitle = stringResource(R.string.kanban_subtitle),
        actions = {
            TextButton(enabled = !busy && content?.refreshing != true, onClick = vm::refresh) {
                Text(stringResource(R.string.common_refresh))
            }
        },
    ) {
        when {
            content == null && state is KanbanUiState.Loading -> LoadingBox()
            content == null -> ErrorBox(
                error.orEmpty().ifBlank { stringResource(R.string.kanban_error_generic) },
                onRetry = vm::refresh,
            )
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    error?.takeIf { it.isNotBlank() }?.let { message ->
                        item { Text(message, color = MaterialTheme.colorScheme.error) }
                    }
                    item {
                        BoardToolbar(
                            content = content!!,
                            selectedBoard = boardSlug,
                            busy = busy,
                            onSelected = {
                                boardSlug = it
                                vm.switchKanbanBoard(it)
                            },
                            onNewBoard = { showBoardDialog = true },
                        )
                    }
                    item {
                        CollapsibleSection(
                            title = stringResource(R.string.kanban_stats_section),
                            collapsible = true,
                        ) {
                            StatsSection(content = content!!)
                        }
                    }
                    item {
                        CollapsibleSection(
                            title = stringResource(R.string.kanban_board_section),
                        ) {
                            BoardColumns(
                                columns = content!!.columns,
                                busy = busy,
                                onCreate = { createTaskStatus = it },
                                onOpen = {
                                    selectedTaskId = it
                                    vm.openTask(it)
                                },
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
        }
    }

    if (selectedTaskId != null) {
        ModalBottomSheet(
            onDismissRequest = {
                selectedTaskId = null
                vm.closeTask()
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            TaskDetailSheet(
                state = taskState,
                runState = runState,
                busy = busy,
                onClose = {
                    selectedTaskId = null
                    vm.closeTask()
                },
                onStatusSelected = { status ->
                    selectedTaskId?.let { taskId ->
                        vm.patchKanbanTask(taskId, buildJsonObject { put("status", status) })
                    }
                },
                onAddComment = { body, onSuccess ->
                    selectedTaskId?.let { vm.addKanbanTaskComment(it, body, onSuccess) }
                },
                onInspectRun = vm::getKanbanRun,
                onTerminateRun = { runId -> selectedTaskId?.let { vm.terminateKanbanRun(runId, it) } },
                onDelete = { selectedTaskId?.let { deleteTaskId = it } },
            )
        }
    }
}

@Composable
private fun BoardToolbar(
    content: KanbanContent,
    selectedBoard: String,
    busy: Boolean,
    onSelected: (String) -> Unit,
    onNewBoard: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val current = content.boards.firstOrNull { it.slug == selectedBoard }
        ?: content.boards.firstOrNull { it.isCurrent }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(enabled = !busy, onClick = { expanded = true }) {
                Text(current?.name?.ifBlank { current.slug } ?: selectedBoard.ifBlank {
                    stringResource(R.string.kanban_no_boards)
                })
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                content.boards.forEach { board ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(board.name.ifBlank { board.slug })
                                if (board.description.isNotBlank()) {
                                    Text(board.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelected(board.slug)
                        },
                    )
                }
            }
        }
        OutlinedButton(enabled = !busy, onClick = onNewBoard) {
            Text(stringResource(R.string.kanban_new_board))
        }
    }
}

@Composable
private fun StatsSection(content: KanbanContent) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (content.stats.byStatus.isEmpty()) {
            Text(stringResource(R.string.kanban_no_stats))
        } else {
            val labels = content.stats.byStatus.entries.associate { (status, _) -> status to statusLabel(status) }
            Text(
                content.stats.byStatus.entries.joinToString(" · ") { (status, count) ->
                    "${labels[status] ?: status}: $count"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        content.stats.oldestReadyAgeSeconds?.let {
            Text(
                pluralStringResource(R.plurals.kanban_oldest_ready, it, it),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (content.assignees.isNotEmpty()) {
            Text(stringResource(R.string.kanban_assignees), style = MaterialTheme.typography.titleSmall)
            content.assignees.forEach { assignee ->
                val total = assignee.counts.values.sum()
                Text("${assignee.name}: $total", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (content.workers.isNotEmpty()) {
            Text(stringResource(R.string.kanban_active_workers), style = MaterialTheme.typography.titleSmall)
            content.workers.forEach { worker ->
                Text(
                    listOfNotNull(
                        worker.taskTitle.ifBlank { worker.taskId },
                        worker.profile.takeIf { it.isNotBlank() },
                        worker.startedAt.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun BoardColumns(
    columns: List<KanbanColumn>,
    busy: Boolean,
    onCreate: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    if (columns.all { it.tasks.isEmpty() }) {
        Text(stringResource(R.string.kanban_no_tasks))
    }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 520.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(columns, key = { it.name }) { column ->
            Card(
                modifier = Modifier.width(230.dp).height(440.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(statusLabel(column.name), style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${column.tasks.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(enabled = !busy, onClick = { onCreate(column.name) }) {
                            Text(stringResource(R.string.kanban_create_task))
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        column.tasks.forEach { task ->
                            TaskCard(task = task, onOpen = onOpen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(task: KanbanTaskRow, onOpen: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(task.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(task.title.ifBlank { task.id }, style = MaterialTheme.typography.titleSmall)
            task.assignee.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            Text(
                listOfNotNull(
                    task.priority?.let { stringResource(R.string.kanban_priority, it) },
                    task.commentCount.takeIf { it > 0 }?.let {
                        pluralStringResource(R.plurals.kanban_comments_count, it, it)
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            task.latestSummary.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
        }
    }
}

@Composable
private fun TaskDetailSheet(
    state: KanbanTaskState?,
    runState: KanbanRunState?,
    busy: Boolean,
    onClose: () -> Unit,
    onStatusSelected: (String) -> Unit,
    onAddComment: (String, () -> Unit) -> Unit,
    onInspectRun: (String) -> Unit,
    onTerminateRun: (String) -> Unit,
    onDelete: () -> Unit,
) {
    when (val current = state) {
        null, KanbanTaskState.Loading -> Box(
            modifier = Modifier.fillMaxWidth().height(360.dp),
            contentAlignment = Alignment.Center,
        ) { androidx.compose.material3.CircularProgressIndicator() }
        is KanbanTaskState.Failure -> ErrorBox(
            current.message.orEmpty().ifBlank { stringResource(R.string.kanban_error_generic) },
            onRetry = null,
        )
        is KanbanTaskState.Content -> {
            val detail = current.value
            var comment by rememberSaveable(detail.task.id) { mutableStateOf("") }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 720.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        detail.task.title.ifBlank { detail.task.id },
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) }
                }
                Text(
                    listOfNotNull(
                        statusLabel(detail.task.status),
                        detail.task.assignee.takeIf { it.isNotBlank() },
                        detail.task.tenant.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPicker(
                        selected = detail.task.status,
                        enabled = !busy,
                        onSelected = onStatusSelected,
                    )
                    TextButton(enabled = !busy, onClick = onDelete) {
                        Text(stringResource(R.string.kanban_delete_task))
                    }
                }
                if (detail.task.body.isNotBlank()) {
                    CollapsibleSection(
                        title = stringResource(R.string.kanban_description),
                        collapsible = true,
                    ) { Text(detail.task.body) }
                }
                detail.task.latestSummary.takeIf { it.isNotBlank() }?.let { summary ->
                    CollapsibleSection(
                        title = stringResource(R.string.kanban_summary),
                        collapsible = true,
                    ) { Text(summary) }
                }
                CollapsibleSection(title = stringResource(R.string.kanban_comments_section)) {
                    detail.commentError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    if (detail.comments.isEmpty()) {
                        Text(stringResource(R.string.kanban_no_comments))
                    } else {
                        detail.comments.forEach { item ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    listOfNotNull(
                                        item.author.takeIf { it.isNotBlank() },
                                        item.createdAt.takeIf { it.isNotBlank() },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                                Text(item.body)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.kanban_comment_hint)) },
                        minLines = 2,
                        enabled = !busy && !detail.commentBusy,
                    )
                    Button(
                        enabled = comment.isNotBlank() && !busy && !detail.commentBusy,
                        onClick = {
                            onAddComment(comment.trim()) { comment = "" }
                        },
                    ) { Text(stringResource(R.string.kanban_send_comment)) }
                }
                CollapsibleSection(
                    title = stringResource(R.string.kanban_log_section),
                    collapsible = true,
                ) {
                    if (detail.log.content.isBlank()) {
                        Text(stringResource(R.string.kanban_no_log))
                    } else {
                        Text(detail.log.content, fontFamily = FontFamily.Monospace)
                        if (detail.log.truncated) {
                            Text(stringResource(R.string.kanban_log_truncated))
                        }
                    }
                }
                CollapsibleSection(
                    title = stringResource(R.string.kanban_runs_section),
                    collapsible = true,
                ) {
                    if (detail.runs.isEmpty()) {
                        Text(stringResource(R.string.kanban_no_runs))
                    } else {
                        detail.runs.forEach { run ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        listOfNotNull(
                                            run.id,
                                            run.profile.takeIf { it.isNotBlank() },
                                            run.status.takeIf { it.isNotBlank() },
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { onInspectRun(run.id) }) {
                                            Text(stringResource(R.string.kanban_inspect_run))
                                        }
                                        if (run.endedAt.isBlank()) {
                                            TextButton(
                                                enabled = !busy,
                                                onClick = { onTerminateRun(run.id) },
                                            ) {
                                                Text(stringResource(R.string.kanban_terminate_run))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                when (val selectedRun = runState) {
                    is KanbanRunState.Content -> CollapsibleSection(
                        title = stringResource(R.string.kanban_run_detail),
                        collapsible = true,
                    ) {
                        RunDetail(run = selectedRun.value)
                    }
                    is KanbanRunState.Failure -> Text(
                        selectedRun.message.orEmpty().ifBlank { stringResource(R.string.kanban_error_generic) },
                        color = MaterialTheme.colorScheme.error,
                    )
                    KanbanRunState.Loading -> androidx.compose.material3.CircularProgressIndicator()
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun StatusPicker(selected: String, enabled: Boolean, onSelected: (String) -> Unit) {
    var expanded by rememberSaveable(selected) { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }) {
            Text(statusLabel(selected))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DEFAULT_KANBAN_COLUMNS.forEach { status ->
                DropdownMenuItem(
                    text = { Text(statusLabel(status)) },
                    onClick = {
                        expanded = false
                        onSelected(status)
                    },
                )
            }
        }
    }
}

@Composable
private fun RunDetail(run: KanbanRunRow) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(listOfNotNull(run.status, run.outcome).filter { it.isNotBlank() }.joinToString(" · "))
        run.summary.takeIf { it.isNotBlank() }?.let { Text(it) }
        run.error.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        run.startedAt.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun statusLabel(status: String): String = when (status.lowercase()) {
    KANBAN_TRIAGE -> stringResource(R.string.kanban_status_triage)
    KANBAN_TODO -> stringResource(R.string.kanban_status_todo)
    KANBAN_SCHEDULED -> stringResource(R.string.kanban_status_scheduled)
    KANBAN_READY -> stringResource(R.string.kanban_status_ready)
    KANBAN_RUNNING -> stringResource(R.string.kanban_status_running)
    KANBAN_BLOCKED -> stringResource(R.string.kanban_status_blocked)
    KANBAN_REVIEW -> stringResource(R.string.kanban_status_review)
    KANBAN_DONE -> stringResource(R.string.kanban_status_done)
    else -> status.ifBlank { stringResource(R.string.kanban_unknown) }
}

