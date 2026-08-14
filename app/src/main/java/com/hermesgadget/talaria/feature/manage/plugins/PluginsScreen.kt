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

@Composable
internal fun PluginsScreen(vm: PluginsViewModel = viewModel(factory = PluginsViewModel.factory())) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var installIdentifier by rememberSaveable { mutableStateOf("") }
    var forceInstall by rememberSaveable { mutableStateOf(false) }
    var enableAfterInstall by rememberSaveable { mutableStateOf(true) }
    var memorySelection by rememberSaveable { mutableStateOf(BUILTIN_MEMORY_PROVIDER) }
    var contextSelection by rememberSaveable { mutableStateOf("") }
    var deleteCandidate by rememberSaveable { mutableStateOf<String?>(null) }

    val content = when (state) {
        is PluginsUiState.Content -> (state as PluginsUiState.Content).value
        is PluginsUiState.Failure -> (state as PluginsUiState.Failure).previous
        PluginsUiState.Loading -> null
    }
    val stateError = (state as? PluginsUiState.Failure)?.message
    val busy = content?.busyAction != null

    LaunchedEffect(content?.providers) {
        content?.providers?.let { providers ->
            memorySelection = providers.memoryProvider.ifBlank { BUILTIN_MEMORY_PROVIDER }
            contextSelection = providers.contextEngine
        }
    }

    deleteCandidate?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.plugins_delete_title)) },
            text = { Text(stringResource(R.string.plugins_delete_message, name)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        deleteCandidate = null
                        vm.deleteAgentPlugin(name)
                    },
                ) { Text(stringResource(R.string.plugins_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    ScreenScaffold(
        title = stringResource(R.string.plugins_title),
        subtitle = stringResource(R.string.plugins_subtitle),
        actions = {
            Row {
                TextButton(enabled = !busy && content?.refreshing != true, onClick = vm::refresh) {
                    Text(stringResource(R.string.common_refresh))
                }
                TextButton(enabled = !busy, onClick = vm::rescanDashboardPlugins) {
                    Text(stringResource(R.string.plugins_rescan))
                }
            }
        },
    ) {
        when {
            content == null && state is PluginsUiState.Loading -> LoadingBox()
            content == null -> ErrorBox(
                stateError.orEmpty().ifBlank { stringResource(R.string.plugins_error_generic) },
                onRetry = vm::refresh,
            )
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    stateError?.takeIf { it.isNotBlank() }?.let { message ->
                        item { Text(message, color = MaterialTheme.colorScheme.error) }
                    }
                    item {
                        CollapsibleSection(
                            title = stringResource(R.string.plugins_dashboard_section),
                            collapsible = true,
                        ) {
                            DashboardPluginsSection(
                                plugins = content!!.dashboardPlugins,
                                busy = busy,
                                onVisibilityChanged = vm::setDashboardPluginVisibility,
                            )
                        }
                    }
                    item {
                        CollapsibleSection(
                            title = stringResource(R.string.plugins_agents_section),
                            collapsible = true,
                        ) {
                            AgentPluginsSection(
                                plugins = content!!.agentPlugins,
                                busy = busy,
                                onEnable = vm::enableAgentPlugin,
                                onDisable = vm::disableAgentPlugin,
                                onUpdate = vm::updateAgentPlugin,
                                onDelete = { deleteCandidate = it },
                            )
                        }
                    }
                    item {
                        CollapsibleSection(
                            title = stringResource(R.string.plugins_install_section),
                            collapsible = true,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    stringResource(R.string.plugins_install_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                OutlinedTextField(
                                    value = installIdentifier,
                                    onValueChange = { installIdentifier = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.plugins_install_identifier)) },
                                    singleLine = true,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(stringResource(R.string.plugins_install_force))
                                    Switch(
                                        checked = forceInstall,
                                        enabled = !busy,
                                        onCheckedChange = { forceInstall = it },
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(stringResource(R.string.plugins_install_enable))
                                    Switch(
                                        checked = enableAfterInstall,
                                        enabled = !busy,
                                        onCheckedChange = { enableAfterInstall = it },
                                    )
                                }
                                Button(
                                    enabled = installIdentifier.isNotBlank() && !busy,
                                    onClick = {
                                        vm.installAgentPlugin(
                                            installIdentifier.trim(),
                                            forceInstall,
                                            enableAfterInstall,
                                        )
                                    },
                                ) { Text(stringResource(R.string.plugins_install_button)) }
                            }
                        }
                    }
                    item {
                        CollapsibleSection(
                            title = stringResource(R.string.plugins_providers_section),
                            collapsible = true,
                        ) {
                            ProvidersSection(
                                providers = content!!.providers,
                                memorySelection = memorySelection,
                                contextSelection = contextSelection,
                                busy = busy,
                                onMemorySelected = { memorySelection = it },
                                onContextSelected = { contextSelection = it },
                                onSave = {
                                    vm.putPluginProviders(memorySelection, contextSelection)
                                },
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DashboardPluginsSection(
    plugins: List<DashboardPluginRow>,
    busy: Boolean,
    onVisibilityChanged: (String, Boolean) -> Unit,
) {
    if (plugins.isEmpty()) {
        Text(stringResource(R.string.plugins_no_dashboard_plugins))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plugins.forEach { plugin ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                plugin.label.ifBlank { plugin.name },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                listOfNotNull(
                                    plugin.version.takeIf { it.isNotBlank() }?.let {
                                        stringResource(R.string.plugins_version, it)
                                    },
                                    plugin.source.takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = plugin.visible,
                            enabled = !busy,
                            onCheckedChange = { onVisibilityChanged(plugin.name, it) },
                        )
                    }
                    if (plugin.description.isNotBlank()) {
                        Text(plugin.description, style = MaterialTheme.typography.bodySmall)
                    }
                    if (plugin.tabPath.isNotBlank()) {
                        Text(
                            plugin.tabPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentPluginsSection(
    plugins: List<AgentPluginRow>,
    busy: Boolean,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (plugins.isEmpty()) {
        Text(stringResource(R.string.plugins_no_agent_plugins))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plugins.forEach { plugin ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(plugin.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(
                                    plugin.version.takeIf { it.isNotBlank() }?.let {
                                        stringResource(R.string.plugins_version, it)
                                    },
                                    plugin.source.takeIf { it.isNotBlank() },
                                    plugin.runtimeStatus.takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = plugin.runtimeStatus == "enabled",
                            enabled = !busy,
                            onCheckedChange = { enabled ->
                                if (enabled) onEnable(plugin.name) else onDisable(plugin.name)
                            },
                        )
                    }
                    if (plugin.description.isNotBlank()) Text(plugin.description)
                    if (plugin.authRequired) {
                        Text(
                            stringResource(
                                R.string.plugins_auth_required,
                                plugin.authCommand.ifBlank { plugin.name },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (plugin.canUpdate) {
                            OutlinedButton(enabled = !busy, onClick = { onUpdate(plugin.name) }) {
                                Text(stringResource(R.string.plugins_update))
                            }
                        }
                        if (plugin.canRemove) {
                            TextButton(enabled = !busy, onClick = { onDelete(plugin.name) }) {
                                Text(stringResource(R.string.plugins_delete))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProvidersSection(
    providers: PluginProviders,
    memorySelection: String,
    contextSelection: String,
    busy: Boolean,
    onMemorySelected: (String) -> Unit,
    onContextSelected: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ProviderPicker(
            label = stringResource(R.string.plugins_memory_provider),
            selected = memorySelection,
            options = providers.memoryOptions,
            defaultName = BUILTIN_MEMORY_PROVIDER,
            defaultLabel = stringResource(R.string.plugins_provider_default),
            onSelected = onMemorySelected,
        )
        ProviderPicker(
            label = stringResource(R.string.plugins_context_engine),
            selected = contextSelection,
            options = providers.contextOptions,
            defaultName = "",
            defaultLabel = stringResource(R.string.plugins_provider_default),
            onSelected = onContextSelected,
        )
        Button(enabled = !busy, onClick = onSave) {
            Text(stringResource(R.string.plugins_save_providers))
        }
    }
}

@Composable
private fun ProviderPicker(
    label: String,
    selected: String,
    options: List<PluginProviderOption>,
    defaultName: String,
    defaultLabel: String,
    onSelected: (String) -> Unit,
) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    val selectedLabel = if (selected.isBlank() || selected == defaultName) {
        defaultLabel
    } else {
        selected
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selectedLabel)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                // Empty is the server's explicit clear/default sentinel for
                // context_engine, so it must remain selectable after a custom
                // provider has been chosen.
                DropdownMenuItem(
                    text = { Text(defaultLabel) },
                    onClick = {
                        expanded = false
                        onSelected(defaultName)
                    },
                )
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.name)
                                if (option.description.isNotBlank()) {
                                    Text(
                                        option.description,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelected(option.name)
                        },
                    )
                }
            }
        }
        if (options.isEmpty() && defaultName.isBlank()) {
            Text(
                stringResource(R.string.plugins_no_provider_options),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
