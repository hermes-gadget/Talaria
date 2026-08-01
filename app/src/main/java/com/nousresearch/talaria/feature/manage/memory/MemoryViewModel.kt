/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.nousresearch.talaria.feature.manage.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.core.data.repo.HermesRepository
import com.nousresearch.talaria.domain.model.MemoryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MemoryUiState(
    val state: MemoryState? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val resetTarget: String? = null,
)

class MemoryViewModel(
    private val repo: HermesRepository = TalariaApp.instance.container.hermesRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(MemoryUiState())
    val ui: StateFlow<MemoryUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repo.getMemoryState().fold(
                onSuccess = { state -> _ui.update { it.copy(state = state, loading = false) } },
                onFailure = { error -> _ui.update { it.copy(loading = false, error = error.message) } },
            )
        }
    }

    fun activate(name: String) = mutate { repo.setMemoryProvider(name) }

    fun requestReset(target: String) = _ui.update { it.copy(resetTarget = target) }
    fun cancelReset() = _ui.update { it.copy(resetTarget = null) }
    fun confirmReset() {
        val target = _ui.value.resetTarget ?: return
        _ui.update { it.copy(resetTarget = null) }
        mutate { repo.resetMemory(target) }
    }

    private fun mutate(block: suspend () -> Result<MemoryState>) {
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            block().fold(
                onSuccess = { state -> _ui.update { it.copy(state = state, busy = false) } },
                onFailure = { error -> _ui.update { it.copy(busy = false, error = error.message) } },
            )
        }
    }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MemoryViewModel() as T
        }
    }
}
