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
package com.nousresearch.talaria.feature.manage.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.core.data.repo.HermesRepository
import com.nousresearch.talaria.domain.model.ModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val providers: List<ModelProvider> = emptyList(),
    val currentModel: String? = null,
    val currentProvider: String? = null,
    val message: String? = null,
    val setting: String? = null,
    val pendingProvider: String? = null,
    val pendingModel: String? = null,
    val confirmMessage: String? = null,
)

/** Model management (roadmap 15.12): provider catalog + set active model via the model API. */
class ModelsViewModel(
    private val repo: HermesRepository = TalariaApp.instance.container.hermesRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(ModelsUiState())
    val ui: StateFlow<ModelsUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repo.getModelInfo().onSuccess { info ->
                _ui.update { it.copy(currentModel = info.model, currentProvider = info.provider) }
            }
            repo.getModelProviders().fold(
                onSuccess = { list -> _ui.update { it.copy(loading = false, providers = list) } },
                onFailure = { e -> _ui.update { it.copy(loading = false, error = e.message) } },
            )
        }
    }

    fun setModel(provider: String, model: String, confirmExpensive: Boolean = false) {
        _ui.update { it.copy(setting = model, message = null) }
        viewModelScope.launch {
            repo.setModel(provider, model, confirmExpensive).fold(
                onSuccess = { result ->
                    if (result.confirmRequired) {
                        _ui.update {
                            it.copy(
                                setting = null,
                                pendingProvider = provider,
                                pendingModel = model,
                                confirmMessage = result.confirmMessage
                                    ?: "This model may be unusually expensive. Continue?",
                            )
                        }
                    } else {
                        _ui.update {
                            it.copy(
                                setting = null,
                                pendingProvider = null,
                                pendingModel = null,
                                confirmMessage = null,
                                message = "Set model to $provider / $model",
                            )
                        }
                        refresh()
                    }
                },
                onFailure = { e -> _ui.update { it.copy(setting = null, message = "Failed: ${e.message}") } },
            )
        }
    }

    fun confirmPendingModel() {
        val provider = _ui.value.pendingProvider ?: return
        val model = _ui.value.pendingModel ?: return
        setModel(provider, model, confirmExpensive = true)
    }

    fun dismissModelConfirmation() = _ui.update {
        it.copy(pendingProvider = null, pendingModel = null, confirmMessage = null)
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ModelsViewModel() as T
        }
    }
}
