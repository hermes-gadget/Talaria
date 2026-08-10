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

internal const val KANBAN_TRIAGE = "triage"
internal const val KANBAN_TODO = "todo"
internal const val KANBAN_SCHEDULED = "scheduled"
internal const val KANBAN_READY = "ready"
internal const val KANBAN_RUNNING = "running"
internal const val KANBAN_BLOCKED = "blocked"
internal const val KANBAN_REVIEW = "review"
internal const val KANBAN_DONE = "done"

private val DEFAULT_KANBAN_COLUMNS = listOf(
    KANBAN_TRIAGE,
    KANBAN_TODO,
    KANBAN_SCHEDULED,
    KANBAN_READY,
    KANBAN_RUNNING,
    KANBAN_BLOCKED,
    KANBAN_REVIEW,
    KANBAN_DONE,
)

internal data class KanbanTaskRow(
    val id: String,
    val title: String,
    val body: String,
    val status: String,
    val assignee: String,
    val priority: Int?,
    val tenant: String,
    val commentCount: Int,
    val latestSummary: String,
)

internal data class KanbanColumn(
    val name: String,
    val tasks: List<KanbanTaskRow>,
)

internal data class KanbanBoardRow(
    val slug: String,
    val name: String,
    val description: String,
    val total: Int,
    val isCurrent: Boolean,
)

internal data class KanbanStats(
    val byStatus: Map<String, Int> = emptyMap(),
    val byAssignee: Map<String, Map<String, Int>> = emptyMap(),
    val oldestReadyAgeSeconds: Int? = null,
)

internal data class KanbanAssignee(
    val name: String,
    val counts: Map<String, Int> = emptyMap(),
)

internal data class KanbanWorker(
    val runId: String,
    val taskId: String,
    val taskTitle: String,
    val profile: String,
    val startedAt: String,
)

internal data class KanbanRunRow(
    val id: String,
    val taskId: String,
    val profile: String,
    val status: String,
    val outcome: String,
    val summary: String,
    val error: String,
    val startedAt: String,
    val endedAt: String,
)

internal data class KanbanComment(
    val id: String,
    val author: String,
    val body: String,
    val createdAt: String,
)

internal data class KanbanLog(
    val content: String,
    val exists: Boolean,
    val truncated: Boolean,
)

internal data class KanbanTaskDetail(
    val task: KanbanTaskRow,
    val comments: List<KanbanComment>,
    val log: KanbanLog,
    val runs: List<KanbanRunRow>,
    val commentError: String? = null,
    val commentBusy: Boolean = false,
)

internal data class KanbanContent(
    val columns: List<KanbanColumn> = emptyList(),
    val boards: List<KanbanBoardRow> = emptyList(),
    val currentBoard: String = "",
    val stats: KanbanStats = KanbanStats(),
    val assignees: List<KanbanAssignee> = emptyList(),
    val workers: List<KanbanWorker> = emptyList(),
    val refreshing: Boolean = false,
    val busy: Boolean = false,
)

internal sealed interface KanbanUiState {
    data object Loading : KanbanUiState
    data class Content(val value: KanbanContent) : KanbanUiState
    data class Failure(val message: String?, val previous: KanbanContent? = null) : KanbanUiState
}

internal sealed interface KanbanTaskState {
    data object Loading : KanbanTaskState
    data class Content(val value: KanbanTaskDetail) : KanbanTaskState
    data class Failure(val message: String?) : KanbanTaskState
}

internal sealed interface KanbanRunState {
    data object Loading : KanbanRunState
    data class Content(val value: KanbanRunRow) : KanbanRunState
    data class Failure(val message: String?) : KanbanRunState
}

private data class KanbanTaskCreationRequest(
    val title: String,
    val body: String,
    val assignee: String,
    val status: String,
)

private data class PendingKanbanTaskCreation(
    val request: KanbanTaskCreationRequest,
    val taskId: String,
)

private class KanbanTaskStatusReconciliationException(
    taskId: String,
    status: String,
    cause: Throwable,
) : IllegalStateException(
    "Task $taskId was created in $KANBAN_TODO, but moving it to $status failed. " +
        "Retry to reconcile it; another task will not be created.",
    cause,
)

internal class KanbanViewModel(
    private val api: HermesApi = TalariaApp.instance.container.clientFactory.api(),
) : ViewModel() {
    private val _ui = MutableStateFlow<KanbanUiState>(KanbanUiState.Loading)
    val ui: StateFlow<KanbanUiState> = _ui.asStateFlow()

    private val _task = MutableStateFlow<KanbanTaskState?>(null)
    val task: StateFlow<KanbanTaskState?> = _task.asStateFlow()

    private val _run = MutableStateFlow<KanbanRunState?>(null)
    val run: StateFlow<KanbanRunState?> = _run.asStateFlow()

    private var pendingTaskCreation: PendingKanbanTaskCreation? = null

    init {
        refresh()
    }

    fun refresh(board: String? = currentContent()?.currentBoard?.takeIf { it.isNotBlank() }) {
        val previous = currentContent()
        _ui.value = if (previous == null) {
            KanbanUiState.Loading
        } else {
            KanbanUiState.Content(previous.copy(refreshing = true, busy = false))
        }
        viewModelScope.launch {
            runCatching { loadContent(board) }
                .onSuccess { _ui.value = KanbanUiState.Content(it) }
                .onFailure { error -> _ui.value = KanbanUiState.Failure(error.message, previous) }
        }
    }

    fun createKanbanBoard(
        slug: String,
        name: String,
        switch: Boolean,
        onSuccess: () -> Unit = {},
    ) = mutate(
        block = {
            api.createKanbanBoard(
                buildJsonObject {
                    put("slug", slug)
                    if (name.isNotBlank()) put("name", name)
                    put("switch", switch)
                },
            )
        },
        onSuccess = onSuccess,
    )

    fun switchKanbanBoard(slug: String) {
        val previous = currentContent() ?: return
        _ui.value = KanbanUiState.Content(previous.copy(busy = true))
        viewModelScope.launch {
            runCatching { api.switchKanbanBoard(slug) }
                .onSuccess { refresh(slug) }
                .onFailure { error ->
                    _ui.value = KanbanUiState.Failure(error.message, currentContent()?.copy(busy = false))
                }
        }
    }

    fun createKanbanTask(
        title: String,
        body: String,
        assignee: String,
        status: String,
        onSuccess: () -> Unit = {},
    ) = mutate(
        block = {
            val request = KanbanTaskCreationRequest(title, body, assignee, status)
            val createBody = buildJsonObject {
                put("title", title)
                if (body.isNotBlank()) put("body", body)
                if (assignee.isNotBlank()) put("assignee", assignee)
                put("triage", status == KANBAN_TRIAGE)
            }
            val pending = pendingTaskCreation
            if (pending != null && pending.request != request) {
                error(
                    "Task ${pending.taskId} still needs status reconciliation. " +
                        "Retry that draft before creating another task.",
                )
            }
            if (status in setOf(KANBAN_TRIAGE, KANBAN_TODO)) {
                api.createKanbanTask(createBody)
            } else {
                val taskId = pending?.taskId ?: run {
                    val created = api.createKanbanTask(createBody)
                    val id = created.taskId()
                    require(id.isNotBlank()) { "Hermes did not return the created Kanban task id" }
                    pendingTaskCreation = PendingKanbanTaskCreation(request, id)
                    id
                }
                try {
                    patchKanbanTaskStatusWithRetry(taskId, status)
                    pendingTaskCreation = null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw KanbanTaskStatusReconciliationException(taskId, status, error)
                }
            }
        },
        onSuccess = onSuccess,
    )

    fun patchKanbanTask(taskId: String, body: JsonObject) = mutate {
        api.patchKanbanTask(taskId, body)
    }

    fun deleteKanbanTask(taskId: String) {
        val previous = currentContent() ?: return
        if (previous.busy) return // N0.8 re-entry guard: no overlapping mutations
        _ui.value = KanbanUiState.Content(previous.copy(busy = true))
        viewModelScope.launch {
            runCatching { api.deleteKanbanTask(taskId) }
                .onSuccess {
                    _task.value = null
                    refresh()
                }
                .onFailure { error ->
                    _ui.value = KanbanUiState.Failure(error.message, currentContent()?.copy(busy = false))
                }
        }
    }

    // N0.8: monotonic generations so a stale detail/run response for a
    // previously opened item can never overwrite the currently open one.
    private var taskGeneration = 0L
    private var runGeneration = 0L

    fun openTask(taskId: String) {
        val generation = ++taskGeneration
        _task.value = KanbanTaskState.Loading
        _run.value = null
        viewModelScope.launch {
            runCatching {
                coroutineScope {
                    val task = async { api.getKanbanTask(taskId) }
                    val comments = async { runCatching { api.getKanbanTaskComments(taskId) }.getOrNull() }
                    val log = async { runCatching { api.getKanbanTaskLog(taskId) }.getOrNull() }
                    parseTaskDetail(task.await(), comments.await(), log.await())
                }
            }.onSuccess {
                if (generation == taskGeneration) _task.value = KanbanTaskState.Content(it)
            }.onFailure { error ->
                if (generation == taskGeneration) _task.value = KanbanTaskState.Failure(error.message)
            }
        }
    }

    fun closeTask() {
        ++taskGeneration
        ++runGeneration
        _task.value = null
        _run.value = null
    }

    fun addKanbanTaskComment(taskId: String, body: String, onSuccess: () -> Unit = {}) {
        val current = _task.value as? KanbanTaskState.Content ?: return
        if (current.value.commentBusy) return
        val generation = taskGeneration
        _task.value = current.copy(value = current.value.copy(commentBusy = true, commentError = null))
        viewModelScope.launch {
            runCatching {
                api.addKanbanTaskComment(
                    taskId,
                    buildJsonObject { put("body", body) },
                )
            }.onSuccess {
                if (generation != taskGeneration) return@onSuccess
                onSuccess()
                refresh()
                openTask(taskId)
            }.onFailure { error ->
                if (generation != taskGeneration) return@onFailure
                val latest = _task.value as? KanbanTaskState.Content ?: return@onFailure
                _task.value = latest.copy(
                    value = latest.value.copy(
                        commentBusy = false,
                        commentError = error.message,
                    ),
                )
            }
        }
    }

    fun getKanbanRun(runId: String) {
        val generation = ++runGeneration
        _run.value = KanbanRunState.Loading
        viewModelScope.launch {
            runCatching { api.getKanbanRun(runId) }
                .map(::parseRun)
                .onSuccess {
                    if (generation == runGeneration) _run.value = KanbanRunState.Content(it)
                }
                .onFailure { error ->
                    if (generation == runGeneration) _run.value = KanbanRunState.Failure(error.message)
                }
        }
    }

    fun terminateKanbanRun(runId: String, taskId: String) {
        if ((_run.value as? KanbanRunState.Content) == null && _run.value != KanbanRunState.Loading) return
        val generation = runGeneration
        viewModelScope.launch {
            runCatching { api.terminateKanbanRun(runId) }
                .onSuccess {
                    if (generation != runGeneration) return@onSuccess
                    refresh()
                    openTask(taskId)
                    getKanbanRun(runId)
                }
                .onFailure { error ->
                    if (generation == runGeneration) _run.value = KanbanRunState.Failure(error.message)
                }
        }
    }

    private fun mutate(block: suspend () -> Unit, onSuccess: () -> Unit = {}) {
        val previous = currentContent() ?: return
        if (previous.busy) return // re-entry guard: no overlapping mutations
        _ui.value = KanbanUiState.Content(previous.copy(busy = true, refreshing = false))
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess {
                    refresh()
                    onSuccess()
                }
                .onFailure { error ->
                    _ui.value = KanbanUiState.Failure(error.message, currentContent()?.copy(busy = false))
                }
        }
    }

    private suspend fun patchKanbanTaskStatusWithRetry(taskId: String, status: String) {
        val body = buildJsonObject { put("status", status) }
        try {
            api.patchKanbanTask(taskId, body)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            try {
                api.patchKanbanTask(taskId, body)
            } catch (retryError: CancellationException) {
                throw retryError
            }
        }
    }

    private suspend fun loadContent(board: String?): KanbanContent = coroutineScope {
        val boardResponse = async { api.getKanbanBoard(board = board) }
        val boardsResponse = async { runCatching { api.getKanbanBoards() }.getOrNull() }
        val statsResponse = async { runCatching { api.getKanbanStats() }.getOrNull() }
        val assigneesResponse = async { runCatching { api.getKanbanAssignees() }.getOrNull() }
        val workersResponse = async { runCatching { api.getKanbanActiveWorkers() }.getOrNull() }

        val boardRoot = boardResponse.await()
        val boardsRoot = boardsResponse.await()
        val boards = boardsRoot?.let(::parseBoards).orEmpty()
        val current = boardsRoot?.asObject()?.string("current")
            .orEmpty()
            .ifBlank { board.orEmpty() }
            .ifBlank { boards.firstOrNull { it.isCurrent }?.slug.orEmpty() }
        KanbanContent(
            columns = parseColumns(boardRoot),
            boards = boards,
            currentBoard = current,
            stats = statsResponse.await()?.let(::parseStats) ?: KanbanStats(),
            assignees = assigneesResponse.await()?.let(::parseAssignees).orEmpty(),
            workers = workersResponse.await()?.let(::parseWorkers).orEmpty(),
        )
    }

    private fun currentContent(): KanbanContent? = when (val state = _ui.value) {
        is KanbanUiState.Content -> state.value
        is KanbanUiState.Failure -> state.previous
        KanbanUiState.Loading -> null
    }

    companion object {
        fun factory(
            api: HermesApi = TalariaApp.instance.container.clientFactory.api(),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = KanbanViewModel(api) as T
        }
    }
}

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

private fun parseColumns(element: JsonElement): List<KanbanColumn> {
    val root = element.asObject() ?: return DEFAULT_KANBAN_COLUMNS.map { KanbanColumn(it, emptyList()) }
    val source = root.arrayObjects("columns").associateBy { it.string("name") }
    val names = (DEFAULT_KANBAN_COLUMNS + source.keys).distinct().filter { it.isNotBlank() }
    return names.map { name ->
        KanbanColumn(
            name = name,
            tasks = source[name]?.arrayObjects("tasks")?.mapNotNull(::parseTask).orEmpty(),
        )
    }
}

private fun parseBoards(element: JsonElement): List<KanbanBoardRow> {
    val root = element.asObject() ?: return emptyList()
    val current = root.string("current")
    return root.arrayObjects("boards").mapNotNull { board ->
        val slug = board.string("slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        KanbanBoardRow(
            slug = slug,
            name = board.string("name").ifBlank { slug },
            description = board.string("description"),
            total = board.int("total") ?: board.objectIntMap("counts").values.sum(),
            isCurrent = board.boolean("is_current") || slug == current,
        )
    }
}

private fun parseStats(element: JsonElement): KanbanStats {
    val root = element.asObject() ?: return KanbanStats()
    return KanbanStats(
        byStatus = root.objectIntMap("by_status"),
        byAssignee = root.objectObjectIntMap("by_assignee"),
        oldestReadyAgeSeconds = root.int("oldest_ready_age_seconds"),
    )
}

private fun parseAssignees(element: JsonElement): List<KanbanAssignee> {
    val root = element.asObject()
    val rows = root?.arrayObjects("assignees") ?: (element as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    return rows.mapNotNull { row ->
        val name = row.string("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        KanbanAssignee(name, row.objectIntMap("counts"))
    }
}

private fun parseWorkers(element: JsonElement): List<KanbanWorker> {
    val root = element.asObject()
    val rows = root?.arrayObjects("workers").orEmpty()
    return rows.map { row ->
        KanbanWorker(
            runId = row.string("run_id"),
            taskId = row.string("task_id"),
            taskTitle = row.string("task_title"),
            profile = row.string("profile"),
            startedAt = row.string("started_at"),
        )
    }
}

private fun parseTaskDetail(
    taskElement: JsonElement,
    commentsElement: JsonElement?,
    logElement: JsonElement?,
): KanbanTaskDetail {
    val root = taskElement.asObject() ?: throw IllegalStateException()
    val taskObject = root["task"] as? JsonObject ?: root
    val task = parseTask(taskObject) ?: throw IllegalStateException()
    val comments = commentsElement?.let(::parseComments)?.takeIf { it.isNotEmpty() }
        ?: parseComments(root)
    val runs = root.arrayObjects("runs").map(::parseRun)
    return KanbanTaskDetail(
        task = task,
        comments = comments,
        log = logElement?.let(::parseLog) ?: KanbanLog("", false, false),
        runs = runs,
    )
}

private fun parseComments(element: JsonElement): List<KanbanComment> {
    val root = element.asObject()
    val rows = root?.arrayObjects("comments") ?: (element as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    return rows.map { row ->
        KanbanComment(
            id = row.string("id"),
            author = row.string("author"),
            body = row.string("body"),
            createdAt = row.string("created_at"),
        )
    }
}

private fun parseLog(element: JsonElement): KanbanLog {
    val root = element.asObject()
    return KanbanLog(
        content = root?.string("content") ?: element.asPrimitiveString(),
        exists = root?.boolean("exists") ?: true,
        truncated = root?.boolean("truncated") ?: false,
    )
}

private fun parseRun(element: JsonElement): KanbanRunRow {
    val root = (element.asObject()?.get("run") as? JsonObject)
        ?: element.asObject()
        ?: JsonObject(emptyMap())
    return parseRun(root)
}

private fun parseRun(root: JsonObject): KanbanRunRow = KanbanRunRow(
    id = root.string("id"),
    taskId = root.string("task_id"),
    profile = root.string("profile"),
    status = root.string("status"),
    outcome = root.string("outcome"),
    summary = root.string("summary"),
    error = root.string("error"),
    startedAt = root.string("started_at"),
    endedAt = root.string("ended_at"),
)

private fun parseTask(obj: JsonObject): KanbanTaskRow? {
    val id = obj.string("id").takeIf { it.isNotBlank() } ?: return null
    return KanbanTaskRow(
        id = id,
        title = obj.string("title"),
        body = obj.string("body"),
        status = obj.string("status").ifBlank { KANBAN_TODO },
        assignee = obj.string("assignee"),
        priority = obj.int("priority"),
        tenant = obj.string("tenant"),
        commentCount = obj.int("comment_count") ?: 0,
        latestSummary = obj.string("latest_summary").ifBlank { obj.string("result") },
    )
}

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

private fun JsonObject.arrayObjects(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.boolean(key: String, default: Boolean = false): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: default

private fun JsonObject.objectIntMap(key: String): Map<String, Int> =
    (this[key] as? JsonObject)?.mapValues { (_, value) -> value.jsonPrimitive.intOrNull ?: 0 }.orEmpty()

private fun JsonObject.objectObjectIntMap(key: String): Map<String, Map<String, Int>> =
    (this[key] as? JsonObject)?.mapValues { (_, value) ->
        (value as? JsonObject)?.mapValues { (_, count) -> count.jsonPrimitive.intOrNull ?: 0 }.orEmpty()
    }.orEmpty()

private fun JsonElement.asPrimitiveString(): String =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonElement.taskId(): String {
    val root = asObject() ?: return ""
    return root.string("id").ifBlank { taskObject()?.string("id").orEmpty() }
}

private fun JsonElement.taskObject(): JsonObject? =
    (this as? JsonObject)?.get("task") as? JsonObject
