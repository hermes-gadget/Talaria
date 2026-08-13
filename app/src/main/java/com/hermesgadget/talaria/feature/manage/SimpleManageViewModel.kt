/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.hermesgadget.talaria.feature.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SimpleUiState<T>(
    val loading: Boolean = true,
    val error: String? = null,
    val data: T? = null,
)

class SimpleManageViewModel(
    private val loader: suspend HermesRepository.() -> Result<*>,
    private val repo: HermesRepository = TalariaApp.instance.container.hermesRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(SimpleUiState<Any>())
    val ui: StateFlow<SimpleUiState<Any>> = _ui.asStateFlow()

    private var loadGeneration = 0L
    private var refreshJob: Job? = null

    init { refresh() }

    fun refresh() {
        refreshJob?.cancel()
        val generation = ++loadGeneration
        refreshJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            loader(repo).fold(
                onSuccess = { data ->
                    if (generation == loadGeneration) _ui.update { it.copy(loading = false, data = data) }
                },
                onFailure = { e ->
                    if (generation == loadGeneration) _ui.update { it.copy(loading = false, error = e.message) }
                },
            )
        }
    }

    companion object {
        fun factory(loader: suspend HermesRepository.() -> Result<*>) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SimpleManageViewModel(loader) as T
            }
    }
}
