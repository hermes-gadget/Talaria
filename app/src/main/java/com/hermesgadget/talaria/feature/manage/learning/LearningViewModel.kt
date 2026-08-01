/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.manage.learning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.domain.model.LearningNodeDetail
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
) : ViewModel() {
    private val _ui = MutableStateFlow(LearningUiState())
    val ui: StateFlow<LearningUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            graphSource.load().fold(
                onSuccess = { graph -> _ui.update { it.copy(graph = graph, loading = false) } },
                onFailure = { error -> _ui.update { it.copy(loading = false, error = error.message) } },
            )
        }
    }

    fun open(node: LearningMapNode) {
        _ui.update { it.copy(selected = node, detail = null, draft = "", busy = true, error = null) }
        viewModelScope.launch {
            repo.getLearningNode(node.id).fold(
                onSuccess = { detail -> _ui.update { it.copy(detail = detail, draft = detail.content, busy = false) } },
                onFailure = { error -> _ui.update { it.copy(busy = false, error = error.message) } },
            )
        }
    }

    fun updateDraft(value: String) = _ui.update { it.copy(draft = value) }
    fun close() = _ui.update { it.copy(selected = null, detail = null, confirmDelete = false, error = null) }
    fun requestDelete() = _ui.update { it.copy(confirmDelete = true) }
    fun cancelDelete() = _ui.update { it.copy(confirmDelete = false) }

    fun save() {
        val node = _ui.value.selected ?: return
        val draft = _ui.value.draft
        mutate { repo.updateLearningNode(node.id, draft) }
    }

    fun confirmDelete() {
        val node = _ui.value.selected ?: return
        _ui.update { it.copy(confirmDelete = false) }
        mutate { repo.deleteLearningNode(node.id) }
    }

    private fun mutate(block: suspend () -> Result<com.hermesgadget.talaria.domain.model.LearningGraph>) {
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            block().fold(
                onSuccess = { fallbackGraph ->
                    graphSource.load().fold(
                        onSuccess = { graph ->
                            _ui.update { LearningUiState(graph = graph, loading = false) }
                        },
                        onFailure = { error ->
                            _ui.update {
                                LearningUiState(
                                    graph = LearningGraphSnapshot.fromTyped(fallbackGraph),
                                    loading = false,
                                    error = "Saved, but the enriched graph could not be reloaded: ${error.message}",
                                )
                            }
                        },
                    )
                },
                onFailure = { error -> _ui.update { it.copy(busy = false, error = error.message) } },
            )
        }
    }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LearningViewModel() as T
        }
    }
}
