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

package com.hermesgadget.talaria.feature.manage.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.domain.model.MemoryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.hermesgadget.talaria.core.util.suspendResult

data class MemoryUiState(
    val state: MemoryState? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val resetTarget: String? = null,
    val configs: Map<String, MemoryProviderConfigUiState> = emptyMap(),
    val oauth: Map<String, MemoryProviderOAuthUiState> = emptyMap(),
    val setupBusy: Set<String> = emptySet(),
)

class MemoryViewModel(
    private val repo: HermesRepository = TalariaApp.instance.container.hermesRepository,
    private val api: HermesApi = TalariaApp.instance.container.clientFactory.api(),
) : ViewModel() {
    private val _ui = MutableStateFlow(MemoryUiState())
    val ui: StateFlow<MemoryUiState> = _ui.asStateFlow()
    private val oauthJobs = mutableMapOf<String, Job>()
    private val configJobs = mutableMapOf<String, Job>()

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

    /** Load one provider's declared config schema and probe its OAuth capability. */
    fun loadProvider(name: String) {
        loadConfig(name)
        probeOAuth(name)
    }

    fun updateConfigDraft(provider: String, key: String, value: String) {
        _ui.update { current ->
            val existing = current.configs[provider] ?: MemoryProviderConfigUiState()
            current.copy(
                configs = current.configs + (provider to existing.copy(
                    values = existing.values + (key to value),
                    draftGeneration = existing.draftGeneration + 1,
                    error = null,
                )),
            )
        }
    }

    fun saveProviderConfig(provider: String) {
        val configState = _ui.value.configs[provider] ?: return
        val config = configState.config ?: return
        val values = config.fields
            .asSequence()
            .filter { field ->
                val value = configState.values[field.key].orEmpty()
                value != configState.savedValues[field.key].orEmpty() &&
                    (field.kind != MemoryProviderFieldKind.SECRET || value.isNotBlank())
            }
            .associate { field -> field.key to configState.values[field.key].orEmpty() }

        if (values.isEmpty()) return
        val submittedGeneration = configState.draftGeneration
        _ui.update { current ->
            current.copy(
                configs = current.configs + (provider to configState.copy(saving = true, error = null)),
                error = null,
            )
        }
        viewModelScope.launch {
            suspendResult { updateMemoryProviderConfig(provider, values, MEMORY_CONFIG_SURFACE) }
                .fold(
                    onSuccess = {
                        _ui.update { current ->
                            val latest = current.configs[provider] ?: return@update current
                            val nextValues = latest.values.toMutableMap()
                            val nextSaved = latest.savedValues.toMutableMap()
                            val latestConfig = latest.config ?: config
                            for ((key, value) in values) {
                                val field = latestConfig.fields.firstOrNull { it.key == key }
                                val submittedValueStillCurrent =
                                    latest.draftGeneration == submittedGeneration ||
                                        memoryDraftValueMatchesSubmission(latest.values[key], value)
                                if (field?.kind == MemoryProviderFieldKind.SECRET) {
                                    // A secret is only cleared when the value
                                    // visible now is the one that was sent.
                                    // A newer value stays in the editor.
                                    if (submittedValueStillCurrent) nextValues[key] = ""
                                } else if (submittedValueStillCurrent) {
                                    nextSaved[key] = value
                                }
                            }
                            val nextFields = latestConfig.fields.map { field ->
                                if (field.key !in values) {
                                    field
                                } else if (field.kind == MemoryProviderFieldKind.SECRET) {
                                    // The submitted secret is stored server-side
                                    // even when a newer draft remains visible.
                                    field.copy(isSet = true)
                                } else if (memoryFieldValueStillSubmitted(field, latest, values)) {
                                    field.copy(value = values[field.key].orEmpty())
                                } else {
                                    field
                                }
                            }
                            current.copy(
                                configs = current.configs + (provider to latest.copy(
                                    config = latestConfig.copy(fields = nextFields),
                                    values = nextValues,
                                    savedValues = nextSaved,
                                    saving = false,
                                    error = null,
                                )),
                            )
                        }
                        refresh()
                    },
                    onFailure = { error ->
                        _ui.update { current ->
                            val latest = current.configs[provider] ?: return@update current
                            current.copy(
                                configs = current.configs + (provider to latest.copy(
                                    saving = false,
                                    error = error.message,
                                )),
                            )
                        }
                    },
                )
        }
    }

    fun setupProvider(provider: String) {
        val values = _ui.value.configs[provider]?.values.orEmpty()
        if (provider in _ui.value.setupBusy) return
        _ui.update { current ->
            current.copy(
                setupBusy = current.setupBusy + provider,
                error = null,
            )
        }
        viewModelScope.launch {
            suspendResult {
                setupMemoryProvider(provider, values).also(::requireSuccessfulAction)
            }.fold(
                onSuccess = {
                    _ui.update { current ->
                        current.copy(setupBusy = current.setupBusy - provider)
                    }
                    refresh()
                    loadProvider(provider)
                },
                onFailure = { error ->
                    _ui.update { current ->
                        current.copy(
                            setupBusy = current.setupBusy - provider,
                            error = error.message,
                        )
                    }
                },
            )
        }
    }

    fun startOAuth(provider: String) {
        oauthJobs[provider]?.cancel()
        val job = viewModelScope.launch {
            _ui.update { current ->
                val previous = current.oauth[provider] ?: MemoryProviderOAuthUiState(supported = true)
                current.copy(
                    oauth = current.oauth + (provider to previous.copy(
                        supported = true,
                        starting = true,
                        loading = false,
                        error = null,
                        status = previous.status?.copy(state = "pending")
                            ?: MemoryProviderOAuthStatus(state = "pending"),
                    )),
                )
            }
            try {
                val started = startMemoryProviderOAuth(provider)
                val startedStatus = suspendResult { parseMemoryProviderOAuthStatus(started) }.getOrNull()
                if (startedStatus != null) {
                    setOAuthStatus(provider, startedStatus)
                    if (startedStatus.state != "pending") return@launch
                }

                val deadline = System.currentTimeMillis() + MEMORY_OAUTH_TIMEOUT_MS
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(MEMORY_OAUTH_POLL_INTERVAL_MS)
                    val next = try {
                        getMemoryProviderOAuthStatus(provider)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        if (System.currentTimeMillis() >= deadline) throw failure
                        null
                    } ?: continue
                    setOAuthStatus(provider, next)
                    if (next.state != "pending") return@launch
                }
                throw IllegalStateException(MEMORY_ERROR_OAUTH_TIMEOUT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _ui.update { current ->
                    val previous = current.oauth[provider] ?: MemoryProviderOAuthUiState(supported = true)
                    current.copy(
                        oauth = current.oauth + (provider to previous.copy(
                            supported = true,
                            starting = false,
                            error = error.message,
                            status = previous.status?.copy(state = "error", detail = error.message.orEmpty())
                                ?: MemoryProviderOAuthStatus(state = "error", detail = error.message.orEmpty()),
                        )),
                    )
                }
            } finally {
                oauthJobs.remove(provider)
                _ui.update { current ->
                    val previous = current.oauth[provider] ?: return@update current
                    current.copy(
                        oauth = current.oauth + (provider to previous.copy(starting = false)),
                    )
                }
            }
        }
        oauthJobs[provider] = job
    }

    fun cancelOAuth(provider: String) {
        oauthJobs.remove(provider)?.cancel()
        _ui.update { current ->
            val previous = current.oauth[provider] ?: return@update current
            current.copy(
                oauth = current.oauth + (provider to previous.copy(
                    starting = false,
                    status = previous.status?.copy(state = "idle"),
                    error = null,
                )),
            )
        }
    }

    private fun loadConfig(name: String) {
        configJobs[name]?.cancel()
        val previous = _ui.value.configs[name] ?: MemoryProviderConfigUiState()
        val requestedGeneration = previous.draftGeneration
        _ui.update { current ->
            current.copy(
                configs = current.configs + (name to previous.copy(loading = true, error = null)),
            )
        }
        val job = viewModelScope.launch {
            try {
                val config = getMemoryProviderConfig(name, MEMORY_CONFIG_SURFACE)
                val values = config.fields.associate { field ->
                    field.key to if (field.kind == MemoryProviderFieldKind.SECRET) "" else field.value
                }
                _ui.update { current ->
                    val latest = current.configs[name] ?: MemoryProviderConfigUiState()
                    if (!isMemoryConfigLoadCurrent(latest.draftGeneration, requestedGeneration)) {
                        // Keep a draft edited while the request was in flight;
                        // only clear its loading marker.
                        current.copy(
                            configs = current.configs + (name to latest.copy(loading = false)),
                        )
                    } else {
                        current.copy(
                            configs = current.configs + (name to MemoryProviderConfigUiState(
                                config = config,
                                values = values,
                                savedValues = values,
                                draftGeneration = requestedGeneration,
                                loading = false,
                            )),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _ui.update { current ->
                    val latest = current.configs[name] ?: MemoryProviderConfigUiState()
                    current.copy(
                        configs = current.configs + (name to latest.copy(
                            loading = false,
                            error = error.message,
                        )),
                    )
                }
            }
        }
        configJobs[name] = job
    }

    private fun probeOAuth(provider: String) {
        val previous = _ui.value.oauth[provider] ?: MemoryProviderOAuthUiState()
        _ui.update { current ->
            current.copy(
                oauth = current.oauth + (provider to previous.copy(loading = true, error = null)),
            )
        }
        viewModelScope.launch {
            suspendResult { getMemoryProviderOAuthStatus(provider) }
                .fold(
                    onSuccess = { status ->
                        _ui.update { current ->
                            current.copy(
                                oauth = current.oauth + (provider to MemoryProviderOAuthUiState(
                                    supported = true,
                                    status = status,
                                )),
                            )
                        }
                    },
                    onFailure = {
                        // A 404 is the server's capability signal for providers
                        // without an OAuth flow. Hide that affordance entirely.
                        _ui.update { current ->
                            current.copy(
                                oauth = current.oauth + (provider to MemoryProviderOAuthUiState(
                                    supported = false,
                                )),
                            )
                        }
                    },
                )
        }
    }

    private fun setOAuthStatus(provider: String, status: MemoryProviderOAuthStatus) {
        _ui.update { current ->
            val previous = current.oauth[provider] ?: MemoryProviderOAuthUiState(supported = true)
            current.copy(
                oauth = current.oauth + (provider to previous.copy(
                    supported = true,
                    status = status,
                    error = status.detail.takeIf { status.state == "error" },
                )),
            )
        }
    }

    private suspend fun getMemoryProviderConfig(name: String, surface: String): MemoryProviderConfig =
        parseMemoryProviderConfig(api.getMemoryProviderConfig(name = name, surface = surface))

    private suspend fun updateMemoryProviderConfig(
        name: String,
        values: Map<String, String>,
        surface: String,
    ): JsonElement = api.updateMemoryProviderConfig(
        name = name,
        body = valuesBody(values),
        surface = surface,
    )

    private suspend fun setupMemoryProvider(name: String, values: Map<String, String>): JsonElement =
        api.setupMemoryProvider(name = name, body = valuesBody(values))

    private suspend fun startMemoryProviderOAuth(provider: String): JsonElement =
        api.startMemoryProviderOAuth(provider)

    private suspend fun getMemoryProviderOAuthStatus(provider: String): MemoryProviderOAuthStatus =
        parseMemoryProviderOAuthStatus(api.getMemoryProviderOAuthStatus(provider))

    private fun valuesBody(values: Map<String, String>): JsonObject = buildJsonObject {
        put(
            "values",
            buildJsonObject {
                values.forEach { (key, value) -> put(key, value) }
            },
        )
    }

    private fun requireSuccessfulAction(response: JsonElement) {
        val obj = response as? JsonObject ?: return
        val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull ?: return
        if (!ok) {
            val detail = (obj["detail"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["error"] as? JsonPrimitive)?.contentOrNull
                ?: MEMORY_ERROR_SETUP_FAILED
            error(detail)
        }
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

    override fun onCleared() {
        oauthJobs.values.forEach { it.cancel() }
        oauthJobs.clear()
        configJobs.values.forEach { it.cancel() }
        configJobs.clear()
    }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MemoryViewModel() as T
        }
    }
}

internal fun memoryDraftValueMatchesSubmission(
    currentValue: String?,
    submittedValue: String,
): Boolean = currentValue.orEmpty() == submittedValue

internal fun isMemoryConfigLoadCurrent(
    currentGeneration: Long,
    requestedGeneration: Long,
): Boolean = currentGeneration == requestedGeneration

private fun memoryFieldValueStillSubmitted(
    field: MemoryProviderConfigField,
    latest: MemoryProviderConfigUiState,
    values: Map<String, String>,
): Boolean = memoryDraftValueMatchesSubmission(latest.values[field.key], values[field.key].orEmpty())
