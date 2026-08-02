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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.domain.model.MemoryProvider
import com.hermesgadget.talaria.domain.model.MemoryState
import com.hermesgadget.talaria.ui.components.CollapsibleSection
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import com.hermesgadget.talaria.ui.theme.LocalSpacing

@Composable
fun MemoryScreen(vm: MemoryViewModel = viewModel(factory = MemoryViewModel.factory())) {
    val spacing = LocalSpacing.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state = ui.state

    ui.resetTarget?.let { target ->
        AlertDialog(
            onDismissRequest = vm::cancelReset,
            title = {
                Text(
                    if (target == "all") {
                        stringResource(R.string.memory_reset_all_title)
                    } else {
                        stringResource(R.string.memory_reset_title, target)
                    },
                )
            },
            text = { Text(stringResource(R.string.memory_reset_message)) },
            confirmButton = {
                TextButton(onClick = vm::confirmReset) {
                    Text(stringResource(R.string.memory_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelReset) {
                    Text(stringResource(R.string.memory_cancel))
                }
            },
        )
    }

    ScreenScaffold(
        title = stringResource(R.string.memory_title),
        actions = {
            TextButton(onClick = vm::refresh) {
                Text(stringResource(R.string.memory_refresh))
            }
        },
    ) {
        when {
            ui.loading && state == null -> LoadingBox()
            ui.error != null && state == null -> {
                ErrorBox(memoryErrorText(ui.error!!), onRetry = vm::refresh)
            }
            state == null -> Text(stringResource(R.string.memory_no_providers))
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.itemGap),
            ) {
                item {
                    val activeProvider = state.active?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.memory_none)
                    Text(
                        stringResource(R.string.memory_active_provider, activeProvider),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = spacing.xs),
                    )
                }
                ui.error?.let { message ->
                    item {
                        Text(memoryErrorText(message), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (state.providers.isEmpty()) {
                    item { Text(stringResource(R.string.memory_no_providers)) }
                }
                items(state.providers, key = { it.name }) { provider ->
                    MemoryProviderSection(
                        provider = provider,
                        active = provider.name == state.active,
                        ui = ui,
                        vm = vm,
                    )
                }
                item {
                    BuiltinMemoryFiles(
                        state = state,
                        busy = ui.busy,
                        onReset = vm::requestReset,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryProviderSection(
    provider: MemoryProvider,
    active: Boolean,
    ui: MemoryUiState,
    vm: MemoryViewModel,
) {
    val configState = ui.configs[provider.name]
    val oauthState = ui.oauth[provider.name]
    val setupBusy = provider.name in ui.setupBusy

    CollapsibleSection(title = provider.name, collapsible = true) {
        ProviderSummary(
            provider = provider,
            active = active,
            busy = ui.busy,
            configLoading = configState?.loading == true,
            onActivate = { vm.activate(provider.name) },
            onConfigure = { vm.loadProvider(provider.name) },
        )

        if (provider.setup?.dependencies_installed == false) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.memory_setup_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { vm.setupProvider(provider.name) },
                enabled = !setupBusy,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(
                    stringResource(
                        if (setupBusy) R.string.memory_setup_running else R.string.memory_setup,
                    ),
                )
            }
        }

        configState?.let { configUi ->
            if (configUi.loading) {
                LinearProgressIndicator(modifier = Modifier.padding(top = 10.dp).fillMaxWidth())
            }
            configUi.error?.let { error ->
                Text(
                    memoryErrorText(error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            configUi.config?.let { config ->
                if (config.fields.isEmpty()) {
                    Text(
                        stringResource(R.string.memory_no_configuration),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    MemoryProviderConfigForm(
                        config = config,
                        values = configUi.values,
                        savedValues = configUi.savedValues,
                        saving = configUi.saving,
                        onValueChange = { key, value ->
                            vm.updateConfigDraft(provider.name, key, value)
                        },
                        onSave = { vm.saveProviderConfig(provider.name) },
                    )
                }
            }
        }

        if (oauthState?.supported == true) {
            MemoryProviderOAuth(
                state = oauthState,
                onStart = { vm.startOAuth(provider.name) },
                onCancel = { vm.cancelOAuth(provider.name) },
            )
        }
    }
}

@Composable
private fun ProviderSummary(
    provider: MemoryProvider,
    active: Boolean,
    busy: Boolean,
    configLoading: Boolean,
    onActivate: () -> Unit,
    onConfigure: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(memoryProviderStatusLabel(provider)),
                style = MaterialTheme.typography.labelMedium,
                color = if (provider.available && provider.configured) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        stringResource(memoryProviderStatusLabel(provider)),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    disabledContainerColor = if (provider.available && provider.configured) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    disabledLabelColor = if (provider.available && provider.configured) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
            )
        }
        provider.description?.takeIf(String::isNotBlank)?.let { description ->
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        provider.setup?.let { setup ->
            val dependencies = setup.pip_dependencies + setup.required_env
            if (dependencies.isNotEmpty()) {
                Text(
                    stringResource(
                        if (setup.dependencies_installed) {
                            R.string.memory_dependencies_installed
                        } else {
                            R.string.memory_dependencies_needed
                        },
                        dependencies.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            if (!active) {
                OutlinedButton(
                    onClick = onActivate,
                    enabled = !busy && provider.available && provider.configured,
                ) { Text(stringResource(R.string.memory_activate)) }
            }
            OutlinedButton(onClick = onConfigure, enabled = !configLoading) {
                Text(
                    stringResource(
                        if (configLoading) R.string.memory_loading else R.string.memory_configure,
                    ),
                )
            }
        }
    }
}

@Composable
private fun MemoryProviderConfigForm(
    config: MemoryProviderConfig,
    values: Map<String, String>,
    savedValues: Map<String, String>,
    saving: Boolean,
    onValueChange: (String, String) -> Unit,
    onSave: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val hasChanges = config.fields.any { field ->
        val value = values[field.key].orEmpty()
        value != savedValues[field.key].orEmpty() &&
            (field.kind != MemoryProviderFieldKind.SECRET || value.isNotBlank())
    }
    Column(
        modifier = Modifier.padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(config.label, style = MaterialTheme.typography.titleSmall)
            if (config.docsUrl.isNotBlank()) {
                TextButton(onClick = { uriHandler.openUri(config.docsUrl) }) {
                    Text(stringResource(R.string.memory_documentation))
                }
            }
        }
        config.fields
            .groupBy { field -> field.group.ifBlank { "__general__" } }
            .forEach { (group, fields) ->
                CollapsibleSection(
                    title = if (group == "__general__") {
                        stringResource(R.string.memory_group_general)
                    } else {
                        group
                    },
                    collapsible = true,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        fields.forEach { field ->
                            MemoryProviderFieldEditor(
                                field = field,
                                value = values[field.key].orEmpty(),
                                onValueChange = { value -> onValueChange(field.key, value) },
                            )
                        }
                    }
                }
            }
        Button(onClick = onSave, enabled = hasChanges && !saving) {
            Text(stringResource(if (saving) R.string.memory_saving else R.string.memory_save))
        }
    }
}

@Composable
private fun MemoryProviderFieldEditor(
    field: MemoryProviderConfigField,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val label = if (field.required) {
        stringResource(R.string.memory_required_label, field.label)
    } else {
        field.label
    }
    val supporting = field.description.ifBlank { field.info }
    when (field.kind) {
        MemoryProviderFieldKind.BOOL -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    if (supporting.isNotBlank()) {
                        Text(
                            supporting,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = value.equals("true", ignoreCase = true),
                    onCheckedChange = { checked -> onValueChange(checked.toString()) },
                )
            }
        }

        MemoryProviderFieldKind.SELECT -> {
            var expanded by rememberSaveable(field.key) { mutableStateOf(false) }
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            field.options.firstOrNull { it.value == value }?.label
                                ?: value.ifBlank { stringResource(R.string.memory_choose) },
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        field.options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    expanded = false
                                    onValueChange(option.value)
                                },
                            )
                        }
                    }
                }
                if (supporting.isNotBlank()) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        else -> {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                placeholder = if (field.placeholder.isNotBlank()) {
                    { Text(field.placeholder) }
                } else {
                    null
                },
                supportingText = if (supporting.isNotBlank()) {
                    { Text(supporting) }
                } else {
                    null
                },
                visualTransformation = if (field.kind == MemoryProviderFieldKind.SECRET) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (field.kind == MemoryProviderFieldKind.NUMBER) {
                        KeyboardType.Number
                    } else {
                        KeyboardType.Text
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = field.kind != MemoryProviderFieldKind.JSON,
                minLines = if (field.kind == MemoryProviderFieldKind.JSON) 3 else 1,
            )
            if (field.kind == MemoryProviderFieldKind.SECRET && field.isSet) {
                Text(
                    stringResource(R.string.memory_secret_set),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun MemoryProviderOAuth(
    state: MemoryProviderOAuthUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val status = state.status
    CollapsibleSection(title = stringResource(R.string.memory_oauth_title)) {
        val authLabel = when (status?.auth?.lowercase()) {
            "oauth" -> stringResource(R.string.memory_oauth_method)
            "apikey", "api_key" -> stringResource(R.string.memory_api_key_method)
            else -> null
        }
        Text(
            when {
                status?.state == "pending" || state.starting ->
                    stringResource(R.string.memory_oauth_waiting)
                status?.connected == true && authLabel != null ->
                    stringResource(R.string.memory_oauth_connected, authLabel)
                status?.connected == true -> stringResource(R.string.memory_oauth_connected_unknown)
                else -> stringResource(R.string.memory_oauth_not_connected)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        status?.detail?.takeIf(String::isNotBlank)?.let { detail ->
            Text(
                memoryErrorText(detail),
                style = MaterialTheme.typography.bodySmall,
                color = if (status.state == "error") {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        state.error?.takeIf { it != status?.detail }?.let { error ->
            Text(memoryErrorText(error), color = MaterialTheme.colorScheme.error)
        }
        if (state.starting) {
            LinearProgressIndicator(modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.memory_cancel))
            }
        } else {
            OutlinedButton(onClick = onStart, modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    stringResource(
                        if (status?.connected == true) {
                            R.string.memory_oauth_reconnect
                        } else {
                            R.string.memory_oauth_connect
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun BuiltinMemoryFiles(
    state: MemoryState,
    busy: Boolean,
    onReset: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    CollapsibleSection(
        title = stringResource(R.string.memory_builtin_files),
        collapsible = true,
    ) {
        Text(
            stringResource(
                R.string.memory_builtin_sizes,
                formatMemoryBytes(state.builtin_files.memory),
                formatMemoryBytes(state.builtin_files.user),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            OutlinedButton(
                onClick = { onReset("memory") },
                enabled = !busy && state.builtin_files.memory > 0,
            ) { Text(stringResource(R.string.memory_reset_memory)) }
            OutlinedButton(
                onClick = { onReset("user") },
                enabled = !busy && state.builtin_files.user > 0,
            ) { Text(stringResource(R.string.memory_reset_user)) }
        }
    }
}

private fun memoryProviderStatusLabel(provider: MemoryProvider): Int = when {
    provider.status == "missing" -> R.string.memory_status_missing
    provider.status == "needs_config" || !provider.configured -> R.string.memory_status_needs_config
    provider.status == "unavailable" || !provider.available -> R.string.memory_status_unavailable
    provider.status == "ready" -> R.string.memory_status_ready
    else -> R.string.memory_status_available
}

@Composable
private fun memoryErrorText(error: String): String = when (error) {
    MEMORY_ERROR_CONFIG_INVALID -> stringResource(R.string.memory_error_config_invalid)
    MEMORY_ERROR_OAUTH_STATUS_INVALID -> stringResource(R.string.memory_error_oauth_status_invalid)
    MEMORY_ERROR_OAUTH_TIMEOUT -> stringResource(R.string.memory_error_oauth_timeout)
    MEMORY_ERROR_SETUP_FAILED -> stringResource(R.string.memory_error_setup_failed)
    else -> error
}

private fun formatMemoryBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    else -> "${bytes / 1024} KB"
}
