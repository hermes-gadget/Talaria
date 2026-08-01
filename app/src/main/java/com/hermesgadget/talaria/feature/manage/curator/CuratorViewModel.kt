/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.manage.curator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.domain.model.ActionStatus
import com.hermesgadget.talaria.domain.model.CuratorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CuratorUiState(
    val state: CuratorState? = null,
    val action: ActionStatus? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

class CuratorViewModel(
    private val repo: HermesRepository = TalariaApp.instance.container.hermesRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(CuratorUiState())
    val ui: StateFlow<CuratorUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repo.getCuratorState().fold(
                onSuccess = { state -> _ui.update { it.copy(state = state, loading = false) } },
                onFailure = { error -> _ui.update { it.copy(loading = false, error = error.message) } },
            )
        }
    }

    fun setPaused(paused: Boolean) {
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            repo.setCuratorPaused(paused).fold(
                onSuccess = { state -> _ui.update { it.copy(state = state, busy = false) } },
                onFailure = { error -> _ui.update { it.copy(busy = false, error = error.message) } },
            )
        }
    }

    fun runNow() {
        _ui.update { it.copy(busy = true, action = null, error = null) }
        viewModelScope.launch {
            repo.runCuratorNow().fold(
                onSuccess = { action ->
                    _ui.update { it.copy(action = action, busy = false) }
                    refresh()
                },
                onFailure = { error -> _ui.update { it.copy(busy = false, error = error.message) } },
            )
        }
    }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CuratorViewModel() as T
        }
    }
}
