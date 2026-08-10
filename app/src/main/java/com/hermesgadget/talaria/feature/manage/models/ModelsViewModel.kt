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
package com.hermesgadget.talaria.feature.manage.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.core.network.ConnectionScope
import com.hermesgadget.talaria.core.network.ConnectionScopeObserver
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.core.util.suspendResult
import com.hermesgadget.talaria.domain.model.ModelProvider
import com.hermesgadget.talaria.domain.model.effectiveManagementProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class AuxiliaryTaskAssignmentUi(
    val task: String,
    val provider: String,
    val model: String,
    val baseUrl: String,
)

data class AuxiliaryModelsUi(
    val tasks: List<AuxiliaryTaskAssignmentUi> = emptyList(),
    val mainProvider: String = "",
    val mainModel: String = "",
)

data class RecommendedDefaultModelUi(
    val provider: String,
    val model: String,
    val freeTier: Boolean? = null,
)

/** A MoA slot keeps its raw object so newer server fields survive a mobile edit. */
data class MoaSlotDraft(
    val provider: String = "",
    val model: String = "",
    val enabled: Boolean = true,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    fun toJson(): JsonObject = raw.withValues(
        "provider" to JsonPrimitive(provider.trim()),
        "model" to JsonPrimitive(model.trim()),
        "enabled" to JsonPrimitive(enabled),
    )
}

data class MoaPresetDraft(
    val referenceModels: List<MoaSlotDraft> = emptyList(),
    val aggregator: MoaSlotDraft = MoaSlotDraft(),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    fun toJson(): JsonObject = raw.withValues(
        "reference_models" to JsonArray(referenceModels.map { it.toJson() }),
        "aggregator" to aggregator.toJson(),
    )
}

/**
 * Editable MoA configuration. The raw root and preset objects deliberately
 * retain fields that this small-screen editor does not expose yet.
 */
data class MoaConfigDraft(
    val defaultPreset: String,
    val activePreset: String,
    val presets: Map<String, MoaPresetDraft>,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    fun updatePreset(name: String, update: (MoaPresetDraft) -> MoaPresetDraft): MoaConfigDraft {
        val current = presets[name] ?: return this
        return copy(presets = presets + (name to update(current)))
    }

    fun removePreset(name: String): MoaConfigDraft {
        if (presets.size <= 1 || name !in presets) return this
        val remaining = presets.filterKeys { it != name }
        val nextName = remaining.keys.firstOrNull() ?: return this
        return copy(
            defaultPreset = if (defaultPreset == name) nextName else defaultPreset,
            activePreset = if (activePreset == name) "" else activePreset,
            presets = remaining,
        )
    }

    fun toJson(): JsonObject {
        val serializedPresets = JsonObject(
            presets.mapValues { (_, preset) -> preset.toJson() },
        )
        var result = raw.withValues(
            "default_preset" to JsonPrimitive(defaultPreset),
            "active_preset" to JsonPrimitive(activePreset),
            "presets" to serializedPresets,
        )

        // Keep the compatibility/flattened view in sync with the selected
        // default preset. Older Hermes consumers still read these fields.
        val default = presets[defaultPreset] ?: presets.values.firstOrNull()
        if (default != null) {
            val flattened = default.toJson()
            val keys = listOf(
                "reference_models",
                "aggregator",
                "reference_temperature",
                "aggregator_temperature",
                "reference_timeout",
                "degraded_reference_policy",
                "max_tokens",
                "reference_max_tokens",
                "fanout",
                "enabled",
            )
            result = result.withValues(
                *keys.map { key -> key to flattened[key] }.toTypedArray(),
            )
        }
        return result
    }
}

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
    val auxiliary: AuxiliaryModelsUi? = null,
    val auxiliaryLoading: Boolean = true,
    val auxiliaryError: String? = null,
    val moa: MoaConfigDraft? = null,
    val moaLoading: Boolean = true,
    val moaError: String? = null,
    val moaSaving: Boolean = false,
    val moaSaved: Boolean = false,
    val recommendedDefaults: Map<String, RecommendedDefaultModelUi> = emptyMap(),
    val recommendedLoading: Set<String> = emptySet(),
    val recommendedErrors: Map<String, String> = emptyMap(),
)

/** Model management: provider catalog, MoA settings, auxiliary assignments, and onboarding defaults. */
class ModelsViewModel(
    private val repo: HermesRepository = TalariaApp.instance.container.hermesRepository,
    api: HermesApi? = null,
    private val profileProvider: () -> String? = {
        TalariaApp.instance.container.connectionStore.activeProfile()?.effectiveManagementProfile()
    },
    private val apiProvider: (ConnectionScope?) -> HermesApi = { scope ->
        scope?.snapshot?.let { TalariaApp.instance.container.clientFactory.api(it) }
            ?: TalariaApp.instance.container.clientFactory.api()
    },
    private val scopeFlow: StateFlow<ConnectionScope?>? = null,
) : ViewModel() {
    private val fixedApi = api
    private var boundApi: HermesApi = api ?: apiProvider(scopeFlow?.value)
    private var boundScope: ConnectionScope? = scopeFlow?.value
    private var scopeObserver: ConnectionScopeObserver? = null
    private val workJobs = mutableSetOf<Job>()
    private val _ui = MutableStateFlow(ModelsUiState())
    val ui: StateFlow<ModelsUiState> = _ui.asStateFlow()

    init {
        scopeObserver = scopeFlow?.let { flow ->
            ConnectionScopeObserver(flow, viewModelScope) { next -> rebind(next) }
        }
        if (scopeFlow == null || boundScope != null) refresh()
    }

    private fun rebind(next: ConnectionScope?) {
        boundScope = next
        boundApi = fixedApi ?: apiProvider(next)
        cancelWork()
        _ui.value = ModelsUiState(loading = next != null)
        if (next != null) refresh()
    }

    private fun isCurrentScope(expected: ConnectionScope?): Boolean =
        scopeObserver?.isCurrent(expected) != false

    private fun cancelWork() {
        workJobs.toList().forEach { it.cancel() }
        workJobs.clear()
    }

    private fun launchWork(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(block = block).also { job ->
            workJobs += job
            job.invokeOnCompletion { workJobs -= job }
        }

    fun refresh() {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val requestApi = boundApi
        val profile = profile()
        cancelWork()
        _ui.update {
            it.copy(
                loading = true,
                error = null,
                auxiliaryLoading = true,
                auxiliaryError = null,
                moaLoading = true,
                moaError = null,
                moaSaved = false,
                recommendedDefaults = emptyMap(),
                recommendedLoading = emptySet(),
                recommendedErrors = emptyMap(),
            )
        }
        launchWork {
            launch {
                if (!isCurrentScope(expectedScope)) return@launch
                repo.getModelInfo(expectedScope?.snapshot).onSuccess { info ->
                    if (isCurrentScope(expectedScope)) {
                        _ui.update { it.copy(currentModel = info.model, currentProvider = info.provider) }
                    }
                }
            }
            launch {
                if (!isCurrentScope(expectedScope)) return@launch
                repo.getModelProviders(expectedScope?.snapshot).fold(
                    onSuccess = { list ->
                        if (isCurrentScope(expectedScope)) {
                            _ui.update { it.copy(loading = false, providers = list) }
                        }
                    },
                    onFailure = { e ->
                        if (isCurrentScope(expectedScope)) {
                            _ui.update { it.copy(loading = false, error = e.message) }
                        }
                    },
                )
            }
            launch { loadAuxiliaryModels(requestApi, profile, expectedScope) }
            launch { loadMoaConfig(requestApi, profile, expectedScope) }
        }
    }

    fun getAuxiliaryModels() {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val requestApi = boundApi
        val requestProfile = profile()
        _ui.update { it.copy(auxiliaryLoading = true, auxiliaryError = null) }
        launchWork { loadAuxiliaryModels(requestApi, requestProfile, expectedScope) }
    }

    fun getMoaConfig() {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val requestApi = boundApi
        val requestProfile = profile()
        _ui.update { it.copy(moaLoading = true, moaError = null, moaSaved = false) }
        launchWork { loadMoaConfig(requestApi, requestProfile, expectedScope) }
    }

    fun putMoaConfig(config: MoaConfigDraft) {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val requestApi = boundApi
        val profile = profile()
        _ui.update { it.copy(moaSaving = true, moaError = null, moaSaved = false) }
        launchWork {
            suspendResult { requestApi.putMoaConfig(config.toJson(), profile) }.fold(
                onSuccess = { response ->
                    if (isCurrentScope(expectedScope)) {
                        _ui.update {
                            it.copy(
                                moa = parseMoaConfig(response) ?: config,
                                moaSaving = false,
                                moaSaved = true,
                                moaError = null,
                            )
                        }
                    }
                },
                onFailure = { e ->
                    if (isCurrentScope(expectedScope)) {
                        _ui.update {
                            it.copy(
                                moaSaving = false,
                                moaSaved = false,
                                moaError = e.message,
                            )
                        }
                    }
                },
            )
        }
    }

    /** Load the server-curated default used by provider onboarding. */
    fun getRecommendedDefaultModel(provider: String) {
        val slug = provider.trim()
        if (slug.isBlank()) return
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val requestApi = boundApi
        _ui.update {
            it.copy(
                recommendedLoading = it.recommendedLoading + slug,
                recommendedErrors = it.recommendedErrors - slug,
            )
        }
        launchWork {
            suspendResult { requestApi.getRecommendedDefaultModel(slug) }.fold(
                onSuccess = { response ->
                    if (!isCurrentScope(expectedScope)) return@fold
                    val recommendation = parseRecommendedDefault(response)
                    _ui.update {
                        it.copy(
                            recommendedLoading = it.recommendedLoading - slug,
                            recommendedDefaults = recommendation?.let { value ->
                                it.recommendedDefaults + (slug to value)
                            } ?: it.recommendedDefaults,
                            recommendedErrors = it.recommendedErrors - slug,
                        )
                    }
                },
                onFailure = { e ->
                    if (!isCurrentScope(expectedScope)) return@fold
                    _ui.update {
                        it.copy(
                            recommendedLoading = it.recommendedLoading - slug,
                            recommendedErrors = e.message?.let { message ->
                                it.recommendedErrors + (slug to message)
                            } ?: it.recommendedErrors,
                        )
                    }
                },
            )
        }
    }

    fun setModel(provider: String, model: String, confirmExpensive: Boolean = false) {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        _ui.update { it.copy(setting = model, message = null) }
        launchWork {
            if (!isCurrentScope(expectedScope)) return@launchWork
            repo.setModel(
                provider = provider,
                modelId = model,
                confirmExpensive = confirmExpensive,
                snapshot = expectedScope?.snapshot,
            ).fold(
                onSuccess = { result ->
                    if (!isCurrentScope(expectedScope)) return@fold
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
                onFailure = { e ->
                    if (isCurrentScope(expectedScope)) {
                        _ui.update { it.copy(setting = null, message = "Failed: ${e.message}") }
                    }
                },
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

    private fun profile(): String? =
        (boundScope?.managementProfile ?: profileProvider())?.takeIf { it.isNotBlank() }

    private suspend fun loadAuxiliaryModels(
        requestApi: HermesApi,
        profile: String?,
        expectedScope: ConnectionScope?,
    ) {
        suspendResult { requestApi.getAuxiliaryModels(profile) }.fold(
            onSuccess = { response ->
                if (isCurrentScope(expectedScope)) {
                    _ui.update {
                        it.copy(
                            auxiliary = parseAuxiliaryModels(response),
                            auxiliaryLoading = false,
                            auxiliaryError = null,
                        )
                    }
                }
            },
            onFailure = { e ->
                if (isCurrentScope(expectedScope)) {
                    _ui.update {
                        it.copy(auxiliaryLoading = false, auxiliaryError = e.message)
                    }
                }
            },
        )
    }

    private suspend fun loadMoaConfig(
        requestApi: HermesApi,
        profile: String?,
        expectedScope: ConnectionScope?,
    ) {
        suspendResult { requestApi.getMoaConfig(profile) }.fold(
            onSuccess = { response ->
                if (isCurrentScope(expectedScope)) {
                    _ui.update {
                        it.copy(
                            moa = parseMoaConfig(response),
                            moaLoading = false,
                            moaError = null,
                        )
                    }
                }
            },
            onFailure = { e ->
                if (isCurrentScope(expectedScope)) {
                    _ui.update { it.copy(moaLoading = false, moaError = e.message) }
                }
            },
        )
    }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = TalariaApp.instance.container
                return ModelsViewModel(
                    apiProvider = { scope ->
                        scope?.snapshot?.let { container.clientFactory.api(it) }
                            ?: container.clientFactory.api()
                    },
                    scopeFlow = container.connectionStore.scope,
                ) as T
            }
        }
    }
}

private fun parseAuxiliaryModels(element: JsonElement): AuxiliaryModelsUi? {
    val root = element as? JsonObject ?: return null
    val tasks = (root["tasks"] as? JsonArray).orEmpty().mapNotNull { item ->
        val task = item as? JsonObject ?: return@mapNotNull null
        AuxiliaryTaskAssignmentUi(
            task = task.stringValue("task"),
            provider = task.stringValue("provider"),
            model = task.stringValue("model"),
            baseUrl = task.stringValue("base_url"),
        )
    }
    val main = root["main"] as? JsonObject
    return AuxiliaryModelsUi(
        tasks = tasks,
        mainProvider = main?.stringValue("provider").orEmpty(),
        mainModel = main?.stringValue("model").orEmpty(),
    )
}

private fun parseRecommendedDefault(element: JsonElement): RecommendedDefaultModelUi? {
    val root = element as? JsonObject ?: return null
    return RecommendedDefaultModelUi(
        provider = root.stringValue("provider"),
        model = root.stringValue("model"),
        freeTier = (root["free_tier"] as? JsonPrimitive)?.booleanOrNull,
    )
}

private fun parseMoaConfig(element: JsonElement): MoaConfigDraft? {
    val root = element as? JsonObject ?: return null
    val cleanRoot = root.withValues("ok" to null)
    val presets = linkedMapOf<String, MoaPresetDraft>()
    (root["presets"] as? JsonObject)?.forEach { (name, value) ->
        val preset = value as? JsonObject ?: return@forEach
        if (name.isNotBlank()) presets[name] = parseMoaPreset(preset)
    }
    if (presets.isEmpty()) presets["default"] = parseMoaPreset(root)
    val defaultPreset = root.stringValue("default_preset").takeIf { it in presets }
        ?: presets.keys.first()
    val activePreset = root.stringValue("active_preset").takeIf { it in presets }.orEmpty()
    return MoaConfigDraft(
        defaultPreset = defaultPreset,
        activePreset = activePreset,
        presets = presets,
        raw = cleanRoot,
    )
}

private fun parseMoaPreset(root: JsonObject): MoaPresetDraft {
    val references = when (val rawReferences = root["reference_models"]) {
        is JsonArray -> rawReferences.mapNotNull { (it as? JsonObject)?.let(::parseMoaSlot) }
        is JsonObject -> listOf(parseMoaSlot(rawReferences))
        else -> emptyList()
    }
    return MoaPresetDraft(
        referenceModels = references,
        aggregator = parseMoaSlot(root["aggregator"] as? JsonObject),
        raw = root,
    )
}

private fun parseMoaSlot(root: JsonObject?): MoaSlotDraft {
    val enabled = (root?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: true
    return MoaSlotDraft(
        provider = root?.stringValue("provider").orEmpty(),
        model = root?.stringValue("model").orEmpty(),
        enabled = enabled,
        raw = root ?: JsonObject(emptyMap()),
    )
}

private fun JsonObject.stringValue(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.withValues(vararg values: Pair<String, JsonElement?>): JsonObject =
    JsonObject(toMutableMap().apply {
        values.forEach { (key, value) ->
            if (value == null) remove(key) else put(key, value)
        }
    })
