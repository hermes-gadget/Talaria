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
        // F06: refuse a second run while one is already active.
        if (_ui.value.busy || _ui.value.action?.running == true) return
        _ui.update { it.copy(busy = true, action = null, error = null) }
        viewModelScope.launch {
            repo.runCuratorNow().fold(
                onSuccess = { action ->
                    _ui.update { it.copy(action = action) }
                    if (action.running && action.name.isNotBlank()) {
                        // F06: the run started asynchronously — follow it to a
                        // terminal status instead of dropping it on the floor.
                        trackToCompletion(action.name)
                    } else {
                        _ui.update { it.copy(busy = false) }
                        refresh()
                    }
                },
                onFailure = { error -> _ui.update { it.copy(busy = false, error = error.message) } },
            )
        }
    }

    /** F06: poll the named action until terminal, keeping busy=true while running. */
    private fun trackToCompletion(name: String) {
        viewModelScope.launch {
            repo.trackAction(name).fold(
                onSuccess = { final ->
                    _ui.update { it.copy(action = final, busy = false) }
                    refresh()
                },
                onFailure = { error ->
                    _ui.update { it.copy(busy = false, error = error.message) }
                    refresh()
                },
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
