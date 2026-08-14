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

