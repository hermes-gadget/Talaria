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


internal const val KANBAN_TRIAGE = "triage"
internal const val KANBAN_TODO = "todo"
internal const val KANBAN_SCHEDULED = "scheduled"
internal const val KANBAN_READY = "ready"
internal const val KANBAN_RUNNING = "running"
internal const val KANBAN_BLOCKED = "blocked"
internal const val KANBAN_REVIEW = "review"
internal const val KANBAN_DONE = "done"

internal val DEFAULT_KANBAN_COLUMNS = listOf(
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
    private val api: HermesApi = TalariaApp.instance.container.clientFactory.apiForActive(),
) : ViewModel() {
    private val _ui = MutableStateFlow<KanbanUiState>(KanbanUiState.Loading)
    val ui: StateFlow<KanbanUiState> = _ui.asStateFlow()

    private val _task = MutableStateFlow<KanbanTaskState?>(null)
    val task: StateFlow<KanbanTaskState?> = _task.asStateFlow()

    private val _run = MutableStateFlow<KanbanRunState?>(null)
    val run: StateFlow<KanbanRunState?> = _run.asStateFlow()

    private var pendingTaskCreation: PendingKanbanTaskCreation? = null

    // H1: monotonic generation so a slow board load can never replace the
    // board the user is actually looking at (or mutate after leaving).
    private var loadGeneration = 0L
    private var refreshJob: Job? = null

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
        refreshJob?.cancel()
        val generation = ++loadGeneration
        refreshJob = viewModelScope.launch {
            suspendResult { loadContent(board) }
                .onSuccess {
                    if (generation != loadGeneration) return@launch
                    _ui.value = KanbanUiState.Content(it)
                }
                .onFailure { error ->
                    if (generation != loadGeneration) return@launch
                    _ui.value = KanbanUiState.Failure(error.message, previous)
                }
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
            suspendResult { api.switchKanbanBoard(slug) }
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

    fun patchKanbanTask(taskId: String, body: JsonObject) = mutate(
        block = { api.patchKanbanTask(taskId, body) },
    )

    fun deleteKanbanTask(taskId: String) {
        val previous = currentContent() ?: return
        if (previous.busy) return // N0.8 re-entry guard: no overlapping mutations
        _ui.value = KanbanUiState.Content(previous.copy(busy = true))
        viewModelScope.launch {
            suspendResult { api.deleteKanbanTask(taskId) }
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
            suspendResult {
                coroutineScope {
                    val task = async { api.getKanbanTask(taskId) }
                    val comments = async { suspendResult { api.getKanbanTaskComments(taskId) }.getOrNull() }
                    val log = async { suspendResult { api.getKanbanTaskLog(taskId) }.getOrNull() }
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
            suspendResult {
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
            suspendResult { api.getKanbanRun(runId) }
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
            suspendResult { api.terminateKanbanRun(runId) }
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
            suspendResult { block() }
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
        val boardsResponse = async { suspendResult { api.getKanbanBoards() }.getOrNull() }
        val statsResponse = async { suspendResult { api.getKanbanStats() }.getOrNull() }
        val assigneesResponse = async { suspendResult { api.getKanbanAssignees() }.getOrNull() }
        val workersResponse = async { suspendResult { api.getKanbanActiveWorkers() }.getOrNull() }

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
            api: HermesApi = TalariaApp.instance.container.clientFactory.apiForActive(),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = KanbanViewModel(api) as T
        }
    }
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
