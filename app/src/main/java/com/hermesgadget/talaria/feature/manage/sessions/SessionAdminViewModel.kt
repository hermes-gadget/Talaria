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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.domain.model.BulkDeleteSessionsResponse
import com.hermesgadget.talaria.domain.model.EmptySessionCount
import com.hermesgadget.talaria.domain.model.EmptySessionsDeleteResponse
import com.hermesgadget.talaria.domain.model.LatestDescendantResponse
import com.hermesgadget.talaria.domain.model.SessionImportResponse
import com.hermesgadget.talaria.domain.model.SessionStats
import com.hermesgadget.talaria.domain.model.effectiveManagementProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

interface SessionAdminGateway {
    suspend fun stats(): SessionStats
    suspend fun bulkDelete(ids: List<String>): BulkDeleteSessionsResponse
    suspend fun emptyCount(): EmptySessionCount
    suspend fun deleteEmpty(): EmptySessionsDeleteResponse
    suspend fun importSessions(sessions: JsonArray): SessionImportResponse
}

class HermesSessionAdminGateway(
    private val api: HermesApi,
    private val profileProvider: () -> String? = {
        TalariaApp.instance.container.connectionStore.activeProfile()?.effectiveManagementProfile()
    },
) : SessionAdminGateway {
    private fun profile(): String? = profileProvider()?.takeIf { it.isNotBlank() }

    override suspend fun stats(): SessionStats = parseSessionStats(api.getSessionStatsRaw(profile()))

    override suspend fun bulkDelete(ids: List<String>): BulkDeleteSessionsResponse =
        parseBulkDeleteResponse(
            api.bulkDeleteSessionsRaw(
                buildJsonObject {
                    put("ids", JsonArray(ids.map { JsonPrimitive(it) }))
                    profile()?.let { put("profile", it) }
                },
            ),
        )

    override suspend fun emptyCount(): EmptySessionCount =
        parseEmptySessionCount(api.getEmptySessionCountRaw(profile()))

    override suspend fun deleteEmpty(): EmptySessionsDeleteResponse =
        parseEmptyDeleteResponse(api.deleteEmptySessionsRaw(profile()))

    override suspend fun importSessions(sessions: JsonArray): SessionImportResponse =
        parseSessionImportResponse(
            api.importSessionsRaw(
                buildJsonObject {
                    put("sessions", sessions)
                    profile()?.let { put("profile", it) }
                },
            ),
        )
}

data class SessionAdminContent(
    val stats: SessionStats? = null,
    val emptyCount: Int? = null,
    val selectedIds: Set<String> = emptySet(),
    val busy: Boolean = false,
    val message: String? = null,
)

sealed interface SessionAdminUiState {
    data object Loading : SessionAdminUiState
    data class Content(val value: SessionAdminContent) : SessionAdminUiState
    data class Failure(val message: String, val previous: SessionAdminContent? = null) : SessionAdminUiState
}

class SessionAdminViewModel(
    private val gateway: SessionAdminGateway,
) : ViewModel() {
    private val _ui = MutableStateFlow<SessionAdminUiState>(SessionAdminUiState.Loading)
    val ui: StateFlow<SessionAdminUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        _ui.value = SessionAdminUiState.Loading
        viewModelScope.launch { loadSnapshot() }
    }

    fun setSelected(id: String, selected: Boolean) {
        _ui.update { state ->
            val content = contentOf(state) ?: return@update state
            SessionAdminUiState.Content(content.copy(selectedIds = toggleSessionSelection(content.selectedIds, id, selected)))
        }
    }

    fun selectAll(ids: Collection<String>) {
        _ui.update { state ->
            val content = contentOf(state) ?: return@update state
            SessionAdminUiState.Content(content.copy(selectedIds = ids.toSet()))
        }
    }

    fun clearSelection() {
        _ui.update { state ->
            val content = contentOf(state) ?: return@update state
            SessionAdminUiState.Content(content.copy(selectedIds = emptySet()))
        }
    }

    fun bulkDeleteSelected() {
        val content = contentOf(_ui.value) ?: return
        val ids = content.selectedIds.toList()
        if (ids.isEmpty()) {
            setMessage("Select at least one session")
            return
        }
        setBusy(true)
        viewModelScope.launch {
            runCatching { gateway.bulkDelete(ids) }
                .onSuccess { result -> loadSnapshot("Deleted ${result.deleted.coerceAtLeast(ids.size)} session(s)") }
                .onFailure { error -> setFailure(error.message ?: "Bulk delete failed") }
        }
    }

    fun deleteEmpty() {
        setBusy(true)
        viewModelScope.launch {
            runCatching { gateway.deleteEmpty() }
                .onSuccess { result -> loadSnapshot("Deleted ${result.deleted} empty session(s)") }
                .onFailure { error -> setFailure(error.message ?: "Could not delete empty sessions") }
        }
    }

    fun importSessions(sessions: JsonArray) {
        if (sessions.isEmpty()) {
            setMessage("The selected file contains no sessions")
            return
        }
        setBusy(true)
        viewModelScope.launch {
            runCatching { gateway.importSessions(sessions) }
                .onSuccess { result ->
                    loadSnapshot(
                        "Imported ${result.imported} session(s)" +
                            if (result.skipped > 0) ", skipped ${result.skipped}" else "",
                    )
                }
                .onFailure { error -> setFailure(error.message ?: "Session import failed") }
        }
    }

    private suspend fun loadSnapshot(message: String? = null) {
        runCatching {
            val stats = gateway.stats()
            val empty = gateway.emptyCount().count
            stats to empty
        }.onSuccess { (stats, empty) ->
            _ui.value = SessionAdminUiState.Content(
                SessionAdminContent(stats = stats, emptyCount = empty, message = message),
            )
        }.onFailure { error -> setFailure(error.message ?: "Could not load session administration") }
    }

    private fun setBusy(busy: Boolean) {
        _ui.update { state ->
            val content = contentOf(state) ?: SessionAdminContent()
            SessionAdminUiState.Content(content.copy(busy = busy, message = null))
        }
    }

    private fun setMessage(message: String) {
        _ui.update { state ->
            val content = contentOf(state) ?: return@update state
            SessionAdminUiState.Content(content.copy(message = message))
        }
    }

    private fun setFailure(message: String) {
        _ui.update { state -> SessionAdminUiState.Failure(message, contentOf(state)) }
    }

    private fun contentOf(state: SessionAdminUiState): SessionAdminContent? = when (state) {
        SessionAdminUiState.Loading -> null
        is SessionAdminUiState.Content -> state.value
        is SessionAdminUiState.Failure -> state.previous
    }

    companion object {
        fun factory(
            gateway: SessionAdminGateway = HermesSessionAdminGateway(
                TalariaApp.instance.container.clientFactory.api(),
            ),
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionAdminViewModel(gateway) as T
        }
    }
}

interface LatestDescendantGateway {
    suspend fun latest(sessionId: String): LatestDescendantResponse
}

class HermesLatestDescendantGateway(
    private val api: HermesApi,
    private val profileProvider: () -> String? = {
        TalariaApp.instance.container.connectionStore.activeProfile()?.effectiveManagementProfile()
    },
) : LatestDescendantGateway {
    override suspend fun latest(sessionId: String): LatestDescendantResponse =
        parseLatestDescendant(api.getLatestDescendantRaw(sessionId, profileProvider()?.takeIf { it.isNotBlank() }))
}

sealed interface LatestDescendantUiState {
    data object Idle : LatestDescendantUiState
    data object Loading : LatestDescendantUiState
    data class Success(val response: LatestDescendantResponse) : LatestDescendantUiState
    data class Failure(val message: String) : LatestDescendantUiState
}

class LatestDescendantViewModel(
    private val gateway: LatestDescendantGateway,
) : ViewModel() {
    private val _ui = MutableStateFlow<LatestDescendantUiState>(LatestDescendantUiState.Idle)
    val ui: StateFlow<LatestDescendantUiState> = _ui.asStateFlow()

    fun load(sessionId: String) {
        _ui.value = LatestDescendantUiState.Loading
        viewModelScope.launch {
            runCatching { gateway.latest(sessionId) }
                .onSuccess { _ui.value = LatestDescendantUiState.Success(it) }
                .onFailure { _ui.value = LatestDescendantUiState.Failure(it.message ?: "Could not find latest descendant") }
        }
    }

    fun consume() {
        _ui.value = LatestDescendantUiState.Idle
    }

    companion object {
        fun factory(
            gateway: LatestDescendantGateway = HermesLatestDescendantGateway(
                TalariaApp.instance.container.clientFactory.api(),
            ),
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LatestDescendantViewModel(gateway) as T
        }
    }
}

internal fun toggleSessionSelection(selected: Set<String>, id: String, checked: Boolean): Set<String> =
    if (checked) selected + id else selected - id

internal fun parseSessionStats(root: JsonElement): SessionStats {
    val obj = root as? JsonObject ?: return SessionStats()
    val bySource = (obj["by_source"] as? JsonObject ?: obj["bySource"] as? JsonObject)
        ?.mapNotNull { (key, value) -> value.asInt()?.let { key to it } }
        ?.toMap()
        .orEmpty()
    return SessionStats(
        total = obj["total"].asInt() ?: 0,
        activeStore = obj["active_store"].asInt() ?: obj["activeStore"].asInt() ?: 0,
        archived = obj["archived"].asInt() ?: 0,
        messages = obj["messages"].asInt() ?: 0,
        bySource = bySource,
    )
}

internal fun parseBulkDeleteResponse(root: JsonElement): BulkDeleteSessionsResponse {
    val obj = root as? JsonObject ?: return BulkDeleteSessionsResponse()
    return BulkDeleteSessionsResponse(
        ok = obj["ok"].asBoolean() ?: false,
        deleted = obj["deleted"].asInt() ?: 0,
    )
}

internal fun parseEmptySessionCount(root: JsonElement): EmptySessionCount =
    EmptySessionCount((root as? JsonObject)?.get("count").asInt() ?: 0)

internal fun parseEmptyDeleteResponse(root: JsonElement): EmptySessionsDeleteResponse {
    val obj = root as? JsonObject ?: return EmptySessionsDeleteResponse()
    return EmptySessionsDeleteResponse(
        ok = obj["ok"].asBoolean() ?: false,
        deleted = obj["deleted"].asInt() ?: 0,
    )
}

internal fun parseLatestDescendant(root: JsonElement): LatestDescendantResponse {
    val obj = root as? JsonObject ?: error("Invalid latest descendant response")
    val requested = obj["requested_session_id"].asString()
        ?: obj["requestedSessionId"].asString()
        ?: error("Missing requested session id")
    val session = obj["session_id"].asString()
        ?: obj["sessionId"].asString()
        ?: error("Missing latest session id")
    val path = ((obj["path"] as? JsonArray)?.mapNotNull { it.asString() }).orEmpty()
    return LatestDescendantResponse(
        requestedSessionId = requested,
        sessionId = session,
        path = path,
        changed = obj["changed"].asBoolean() ?: (requested != session),
    )
}

internal fun parseSessionImportResponse(root: JsonElement): SessionImportResponse {
    val obj = root as? JsonObject ?: return SessionImportResponse()
    return SessionImportResponse(
        ok = obj["ok"].asBoolean() ?: false,
        imported = obj["imported"].asInt() ?: 0,
        skipped = obj["skipped"].asInt() ?: 0,
        detached = obj["detached"].asInt() ?: 0,
        importedIds = (obj["imported_ids"] as? JsonArray)?.mapNotNull { it.asString() }.orEmpty(),
        skippedIds = (obj["skipped_ids"] as? JsonArray)?.mapNotNull { it.asString() }.orEmpty(),
        errors = (obj["errors"] as? JsonArray)?.mapNotNull { it.asString() }.orEmpty(),
    )
}

internal fun parseImportSessions(root: JsonElement): JsonArray {
    val candidates = when (root) {
        is JsonArray -> root
        is JsonObject -> (root["sessions"] as? JsonArray) ?: JsonArray(listOf(root))
        else -> JsonArray(emptyList())
    }
    if (candidates.any { it !is JsonObject }) error("Session import must contain JSON objects")
    return candidates
}

private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.asInt(): Int? = asString()?.toIntOrNull()
private fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
