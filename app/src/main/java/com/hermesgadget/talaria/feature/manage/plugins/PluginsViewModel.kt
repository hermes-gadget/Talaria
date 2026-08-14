/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.manage.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.ui.components.CollapsibleSection
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.hermesgadget.talaria.core.util.suspendResult

private const val BUILTIN_MEMORY_PROVIDER = "__hermes_memory_builtin__"

internal data class DashboardPluginRow(
    val name: String,
    val label: String,
    val description: String,
    val version: String,
    val source: String,
    val tabPath: String,
    val visible: Boolean,
)

internal data class AgentPluginRow(
    val name: String,
    val version: String,
    val description: String,
    val source: String,
    val runtimeStatus: String,
    val dashboardLabel: String,
    val dashboardPath: String,
    val hasDashboardManifest: Boolean,
    val canRemove: Boolean,
    val canUpdate: Boolean,
    val authRequired: Boolean,
    val authCommand: String,
    val userHidden: Boolean,
)

internal data class PluginProviderOption(
    val name: String,
    val description: String,
    val status: String,
)

internal data class PluginProviders(
    val memoryProvider: String,
    val memoryOptions: List<PluginProviderOption>,
    val contextEngine: String,
    val contextOptions: List<PluginProviderOption>,
)

internal data class PluginsContent(
    val dashboardPlugins: List<DashboardPluginRow> = emptyList(),
    val agentPlugins: List<AgentPluginRow> = emptyList(),
    val providers: PluginProviders = PluginProviders("", emptyList(), "", emptyList()),
    val refreshing: Boolean = false,
    val busyAction: String? = null,
)

internal sealed interface PluginsUiState {
    data object Loading : PluginsUiState
    data class Content(val value: PluginsContent) : PluginsUiState
    data class Failure(val message: String?, val previous: PluginsContent? = null) : PluginsUiState
}

internal class PluginsViewModel(
    private val api: HermesApi = TalariaApp.instance.container.clientFactory.apiForActive(),
) : ViewModel() {
    private val _ui = MutableStateFlow<PluginsUiState>(PluginsUiState.Loading)
    val ui: StateFlow<PluginsUiState> = _ui.asStateFlow()

    private var loadGeneration = 0L
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        val previous = currentContent()
        _ui.value = if (previous == null) {
            PluginsUiState.Loading
        } else {
            PluginsUiState.Content(previous.copy(refreshing = true, busyAction = null))
        }
        refreshJob?.cancel()
        val generation = ++loadGeneration
        refreshJob = viewModelScope.launch {
            suspendResult { loadContent() }
                .onSuccess {
                    if (generation != loadGeneration) return@launch
                    _ui.value = PluginsUiState.Content(it)
                }
                .onFailure { error ->
                    if (generation != loadGeneration) return@launch
                    _ui.value = PluginsUiState.Failure(error.message, previous)
                }
        }
    }

    fun setDashboardPluginVisibility(name: String, visible: Boolean) = runAction {
        api.setDashboardPluginVisibility(
            name = name,
            body = buildJsonObject { put("hidden", !visible) },
        )
    }

    fun rescanDashboardPlugins() = runAction { api.rescanDashboardPlugins() }

    fun installAgentPlugin(identifier: String, force: Boolean, enable: Boolean) = runAction {
        api.installAgentPlugin(
            buildJsonObject {
                put("identifier", identifier)
                put("force", force)
                put("enable", enable)
            },
        )
    }

    fun enableAgentPlugin(name: String) = runAction { api.enableAgentPlugin(name) }

    fun disableAgentPlugin(name: String) = runAction { api.disableAgentPlugin(name) }

    fun updateAgentPlugin(name: String) = runAction { api.updateAgentPlugin(name) }

    fun deleteAgentPlugin(name: String) = runAction { api.deleteAgentPlugin(name) }

    fun putPluginProviders(memoryProvider: String, contextEngine: String) = runAction {
        api.putPluginProviders(
            buildJsonObject {
                put("memory_provider", if (memoryProvider == BUILTIN_MEMORY_PROVIDER) "" else memoryProvider)
                put("context_engine", contextEngine)
            },
        )
    }

    private fun runAction(block: suspend () -> JsonElement) {
        val previous = currentContent() ?: return
        // Re-entry guard: a double-tap must not install/delete twice (H1).
        if (previous.busyAction != null) return
        _ui.value = PluginsUiState.Content(previous.copy(busyAction = ACTION_BUSY, refreshing = false))
        viewModelScope.launch {
            suspendResult { block() }
                .onSuccess { refresh() }
                .onFailure { error ->
                    _ui.value = PluginsUiState.Failure(
                        error.message,
                        currentContent()?.copy(busyAction = null, refreshing = false),
                    )
                }
        }
    }

    private suspend fun loadContent(): PluginsContent = coroutineScope {
        val manifests = async { api.getDashboardPlugins() }.await()
        val hub = async { api.getDashboardPluginsHub() }.await()
        parseContent(manifests, hub)
    }

    private fun currentContent(): PluginsContent? = when (val state = _ui.value) {
        is PluginsUiState.Content -> state.value
        is PluginsUiState.Failure -> state.previous
        PluginsUiState.Loading -> null
    }

    companion object {
        private const val ACTION_BUSY = "busy"

        fun factory(
            api: HermesApi = TalariaApp.instance.container.clientFactory.apiForActive(),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PluginsViewModel(api) as T
        }
    }
}


private fun parseContent(manifests: JsonElement, hub: JsonElement): PluginsContent {
    val dashboard = linkedMapOf<String, DashboardPluginRow>()
    manifestObjects(manifests).forEach { manifest ->
        parseDashboardPlugin(manifest, visible = true)?.let { dashboard[it.name] = it }
    }

    val root = hub as? JsonObject ?: JsonObject(emptyMap())
    val agents = root.arrayObjects("plugins").mapNotNull { row ->
        parseAgentPlugin(row).also { plugin ->
            val manifest = row["dashboard_manifest"]
                ?.takeUnless { it is JsonNull }
                ?.let { it as? JsonObject }
            if (manifest != null) {
                parseDashboardPlugin(
                    manifest,
                    visible = !row.boolean("user_hidden"),
                )?.let { dashboard[it.name] = it }
            }
        }
    }
    root.arrayObjects("orphan_dashboard_plugins").forEach { manifest ->
        parseDashboardPlugin(manifest, visible = true)?.let { dashboard[it.name] = it }
    }

    val providerRoot = root["providers"] as? JsonObject
    return PluginsContent(
        dashboardPlugins = dashboard.values.sortedBy { it.label.ifBlank { it.name }.lowercase() },
        agentPlugins = agents.sortedBy { it.name.lowercase() },
        providers = PluginProviders(
            memoryProvider = providerRoot?.string("memory_provider").orEmpty(),
            memoryOptions = providerRoot?.arrayObjects("memory_options")?.map(::parseProviderOption).orEmpty(),
            contextEngine = providerRoot?.string("context_engine").orEmpty(),
            contextOptions = providerRoot?.arrayObjects("context_options")?.map(::parseProviderOption).orEmpty(),
        ),
    )
}

private fun parseDashboardPlugin(obj: JsonObject, visible: Boolean): DashboardPluginRow? {
    val name = obj.string("name").takeIf { it.isNotBlank() } ?: return null
    val tab = obj["tab"] as? JsonObject
    return DashboardPluginRow(
        name = name,
        label = obj.string("label").ifBlank { name },
        description = obj.string("description"),
        version = obj.string("version"),
        source = obj.string("source"),
        tabPath = tab?.string("override").takeUnless { it.isNullOrBlank() }
            ?: tab?.string("path").orEmpty(),
        visible = visible && !(tab?.boolean("hidden") ?: false),
    )
}

private fun parseAgentPlugin(obj: JsonObject): AgentPluginRow? {
    val name = obj.string("name").takeIf { it.isNotBlank() } ?: return null
    val manifest = obj["dashboard_manifest"] as? JsonObject
    val tab = manifest?.get("tab") as? JsonObject
    return AgentPluginRow(
        name = name,
        version = obj.string("version"),
        description = obj.string("description"),
        source = obj.string("source"),
        runtimeStatus = obj.string("runtime_status"),
        dashboardLabel = manifest?.string("label").orEmpty(),
        dashboardPath = tab?.string("path").orEmpty(),
        hasDashboardManifest = obj.boolean("has_dashboard_manifest"),
        canRemove = obj.boolean("can_remove"),
        canUpdate = obj.boolean("can_update_git"),
        authRequired = obj.boolean("auth_required"),
        authCommand = obj.string("auth_command"),
        userHidden = obj.boolean("user_hidden"),
    )
}

private fun parseProviderOption(obj: JsonObject): PluginProviderOption = PluginProviderOption(
    name = obj.string("name"),
    description = obj.string("description"),
    status = obj.string("status"),
)

private fun manifestObjects(element: JsonElement): List<JsonObject> = when (element) {
    is JsonArray -> element.mapNotNull { it as? JsonObject }
    is JsonObject -> element.arrayObjects("plugins")
    else -> emptyList()
}

private fun JsonObject.arrayObjects(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.boolean(key: String, default: Boolean = false): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: default
