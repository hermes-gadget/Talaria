/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.manage.learning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.core.network.ConnectionScope
import com.hermesgadget.talaria.core.network.ConnectionScopeObserver
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.domain.model.LearningNodeDetail
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LearningUiState(
    val graph: LearningGraphSnapshot? = null,
    val selected: LearningMapNode? = null,
    val detail: LearningNodeDetail? = null,
    val draft: String = "",
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val confirmDelete: Boolean = false,
)

class LearningViewModel(
    private val repo: HermesRepository = TalariaApp.instance.container.hermesRepository,
    private val graphSource: LearningGraphSource = LearningGraphSource(
        clientFactory = TalariaApp.instance.container.clientFactory,
        connectionStore = TalariaApp.instance.container.connectionStore,
    ),
    private val scopeFlow: StateFlow<ConnectionScope?>? = null,
) : ViewModel() {
    private val _ui = MutableStateFlow(LearningUiState())
    val ui: StateFlow<LearningUiState> = _ui.asStateFlow()

    /** Monotonic generation for identity-safe async loads (N0.8). */
    private var loadGeneration = 0L
    private var boundScope: ConnectionScope? = scopeFlow?.value
    private var scopeObserver: ConnectionScopeObserver? = null
    private var graphJob: Job? = null
    private var detailJob: Job? = null
    private var mutationJob: Job? = null

    init {
        scopeObserver = scopeFlow?.let { flow ->
            ConnectionScopeObserver(flow, viewModelScope) { next -> rebind(next) }
        }
        if (scopeFlow == null || boundScope != null) refresh()
    }

    private fun rebind(next: ConnectionScope?) {
        boundScope = next
        loadGeneration += 1
        graphJob?.cancel()
        detailJob?.cancel()
        mutationJob?.cancel()
        _ui.value = LearningUiState(loading = next != null)
        if (next != null) refresh()
    }

    private fun isCurrentScope(expected: ConnectionScope?): Boolean =
        scopeObserver?.isCurrent(expected) != false

    fun refresh() {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val snapshot = expectedScope?.snapshot
        val generation = ++loadGeneration
        graphJob?.cancel()
        _ui.update { it.copy(loading = true, error = null) }
        graphJob = viewModelScope.launch {
            graphSource.load(snapshot).fold(
                onSuccess = { graph ->
                    // N0.8: a stale response from a refresh that was superseded by
                    // a newer refresh or scope switch must never overwrite the
                    // current scope's graph, even if the source ignores
                    // cancellation (e.g. a blocked or external load).
                    if (generation == loadGeneration && isCurrentScope(expectedScope)) {
                        _ui.update { it.copy(graph = graph, loading = false) }
                    }
                },
                onFailure = { error ->
                    if (generation == loadGeneration && isCurrentScope(expectedScope)) {
                        _ui.update { it.copy(loading = false, error = error.message) }
                    }
                },
            )
        }
    }

    fun open(node: LearningMapNode) {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val generation = ++loadGeneration
        val nodeId = node.id
        detailJob?.cancel()
        _ui.update { it.copy(selected = node, detail = null, draft = "", busy = true, error = null) }
        detailJob = viewModelScope.launch {
            if (!isCurrentScope(expectedScope)) return@launch
            repo.getLearningNode(nodeId, expectedScope?.snapshot).fold(
                onSuccess = { detail ->
                    // N0.8: a stale response for a previously opened node must
                    // never overwrite the currently selected node's detail.
                    if (generation == loadGeneration &&
                        isCurrentScope(expectedScope) &&
                        _ui.value.selected?.id == nodeId
                    ) {
                        _ui.update { it.copy(detail = detail, draft = detail.content, busy = false) }
                    }
                },
                onFailure = { error ->
                    if (generation == loadGeneration &&
                        isCurrentScope(expectedScope) &&
                        _ui.value.selected?.id == nodeId
                    ) {
                        _ui.update { it.copy(busy = false, error = error.message) }
                    }
                },
            )
        }
    }

    fun updateDraft(value: String) = _ui.update { it.copy(draft = value) }
    fun close() {
        loadGeneration += 1
        detailJob?.cancel()
        _ui.update { it.copy(selected = null, detail = null, confirmDelete = false, error = null) }
    }
    fun requestDelete() = _ui.update { it.copy(confirmDelete = true) }
    fun cancelDelete() = _ui.update { it.copy(confirmDelete = false) }

    fun save() {
        val node = _ui.value.selected ?: return
        val draft = _ui.value.draft
        mutate { snapshot -> repo.updateLearningNode(node.id, draft, snapshot) }
    }

    fun confirmDelete() {
        val node = _ui.value.selected ?: return
        _ui.update { it.copy(confirmDelete = false) }
        mutate { snapshot -> repo.deleteLearningNode(node.id, snapshot) }
    }

    private fun mutate(
        block: suspend (ConnectionSnapshot?) -> Result<com.hermesgadget.talaria.domain.model.LearningGraph>,
    ) {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val snapshot = expectedScope?.snapshot
        mutationJob?.cancel()
        _ui.update { it.copy(busy = true, error = null) }
        mutationJob = viewModelScope.launch {
            if (!isCurrentScope(expectedScope)) return@launch
            block(snapshot).fold(
                onSuccess = { fallbackGraph ->
                    if (!isCurrentScope(expectedScope)) return@fold
                    graphSource.load(snapshot).fold(
                        onSuccess = { graph ->
                            if (isCurrentScope(expectedScope)) {
                                _ui.update { LearningUiState(graph = graph, loading = false) }
                            }
                        },
                        onFailure = { error ->
                            if (isCurrentScope(expectedScope)) {
                                _ui.update {
                                    LearningUiState(
                                        graph = LearningGraphSnapshot.fromTyped(fallbackGraph),
                                        loading = false,
                                        error = "Saved, but the enriched graph could not be reloaded: ${error.message}",
                                    )
                                }
                            }
                        },
                    )
                },
                onFailure = { error ->
                    if (isCurrentScope(expectedScope)) {
                        _ui.update { it.copy(busy = false, error = error.message) }
                    }
                },
            )
        }
    }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = TalariaApp.instance.container
                return LearningViewModel(
                    graphSource = LearningGraphSource(
                        clientFactory = container.clientFactory,
                        connectionStore = container.connectionStore,
                    ),
                    scopeFlow = container.connectionStore.scope,
                ) as T
            }
        }
    }
}
