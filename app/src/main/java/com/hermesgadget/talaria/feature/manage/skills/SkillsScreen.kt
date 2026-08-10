/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.manage.skills

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.domain.model.HubSkill
import com.hermesgadget.talaria.domain.model.SkillInfo
import com.hermesgadget.talaria.domain.model.ToolsetInfo
import com.hermesgadget.talaria.ui.components.CollapsibleSection
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold

@Composable
fun SkillsScreen(vm: SkillsViewModel = viewModel(factory = SkillsViewModel.factory())) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var hubQuery by remember { mutableStateOf("") }
    var uninstallTarget by remember { mutableStateOf<String?>(null) }
    var actionsExpanded by remember { mutableStateOf(false) }

    when (val state = ui) {
        SkillsUiState.Loading -> ScreenScaffold("Skills", "Skills & toolsets") { LoadingBox() }
        is SkillsUiState.Failure -> ScreenScaffold("Skills", "Skills & toolsets") {
            ErrorBox(state.message) { vm.refresh() }
        }
        is SkillsUiState.Content -> {
            val content = state.value
            val categories = remember(content.skills) {
                content.skills.mapNotNull { it.category?.ifBlank { null } }.distinct().sorted()
            }
            val filteredSkills = remember(content.skills, query, category) {
                content.skills.filter { skill ->
                    val q = query.trim().lowercase()
                    val matchesQuery = q.isEmpty() ||
                        skill.name.lowercase().contains(q) ||
                        skill.description.orEmpty().lowercase().contains(q)
                    val matchesCat = category == null || skill.category == category
                    matchesQuery && matchesCat
                }
            }

            ScreenScaffold(
                "Skills",
                "Skills & toolsets",
                actions = {
                    Box {
                        IconButton(onClick = { actionsExpanded = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.toolset_more_actions),
                            )
                        }
                        DropdownMenu(
                            expanded = actionsExpanded,
                            onDismissRequest = { actionsExpanded = false },
                        ) {
                            if (tab == 2 || content.skills.any { it.provenance == "hub" }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.toolset_update)) },
                                    onClick = {
                                        actionsExpanded = false
                                        vm.updateHub()
                                    },
                                    enabled = !content.busy,
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.toolset_refresh)) },
                                onClick = {
                                    actionsExpanded = false
                                    vm.refresh()
                                },
                                enabled = !content.busy,
                            )
                        }
                    }
                },
            ) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Skills") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Toolsets") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Hub") })
                }
                content.message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                when (tab) {
                    0 -> SkillsList(
                        skills = filteredSkills,
                        categories = categories,
                        category = category,
                        query = query,
                        busy = content.busy,
                        onQueryChange = { query = it },
                        onCategoryChange = { category = it },
                        onToggle = vm::toggleSkill,
                        onEdit = vm::openEditor,
                        onUninstall = { uninstallTarget = it },
                    )
                    1 -> ToolsetsList(
                        toolsets = content.toolsets,
                        busy = content.busy,
                        onToggle = vm::setToolset,
                        onConfigure = vm::getToolsetConfig,
                    )
                    else -> HubList(
                        query = hubQuery,
                        results = content.hubResults,
                        detail = content.hubDetail,
                        busy = content.busy,
                        onQueryChange = { hubQuery = it },
                        onSearch = { vm.searchHub(hubQuery) },
                        onPreview = vm::previewHub,
                        onScan = vm::scanHub,
                        onInstall = vm::installHub,
                    )
                }
            }
            when (val editor = content.editor) {
                is SkillEditorState.Ready -> SkillContentEditor(
                    editor = editor,
                    busy = content.busy,
                    onDismiss = vm::closeEditor,
                    onSave = { name, fields, onResult -> vm.saveContent(name, fields, onResult) },
                )
                is SkillEditorState.Loading -> AlertDialog(
                    onDismissRequest = vm::closeEditor,
                    title = { Text("Edit skill") },
                    text = { Text("Loading ${editor.name}…") },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = vm::closeEditor) { Text("Cancel") } },
                )
                is SkillEditorState.Error -> AlertDialog(
                    onDismissRequest = vm::closeEditor,
                    title = { Text("Could not load skill") },
                    text = { Text(editor.message) },
                    confirmButton = { TextButton(onClick = vm::closeEditor) { Text("Close") } },
                )
                SkillEditorState.Closed -> Unit
            }
            uninstallTarget?.let { target ->
                AlertDialog(
                    onDismissRequest = { uninstallTarget = null },
                    title = { Text("Uninstall skill?") },
                    text = { Text("Remove '$target' from the active Hermes profile?") },
                    confirmButton = {
                        TextButton(onClick = {
                            uninstallTarget = null
                            vm.uninstallHub(target)
                        }, enabled = !content.busy) { Text("Uninstall") }
                    },
                    dismissButton = { TextButton(onClick = { uninstallTarget = null }) { Text("Cancel") } },
                )
            }
            when (val toolsetConfig = content.toolsetConfig) {
                ToolsetConfigState.Closed -> Unit
                is ToolsetConfigState.Loading -> ToolsetConfigLoadingDialog(
                    name = toolsetConfig.name,
                    onDismiss = vm::closeToolsetConfig,
                )
                is ToolsetConfigState.Error -> ToolsetConfigErrorDialog(
                    state = toolsetConfig,
                    onDismiss = vm::closeToolsetConfig,
                    onRetry = { vm.getToolsetConfig(toolsetConfig.name) },
                )
                is ToolsetConfigState.Ready -> ToolsetConfigDialog(
                    state = toolsetConfig,
                    busy = content.busy,
                    onDismiss = vm::closeToolsetConfig,
                    onProviderSelect = vm::putToolsetProvider,
                    onSaveEnv = { name, values, onResult -> vm.putToolsetEnv(name, values, onResult) },
                    onModelSelect = vm::putToolsetModel,
                    onPostSetup = vm::runToolsetPostSetup,
                )
            }
        }
    }
}

@Composable
private fun SkillsList(
    skills: List<SkillInfo>,
    categories: List<String>,
    category: String?,
    query: String,
    busy: Boolean,
    onQueryChange: (String) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onEdit: (String) -> Unit,
    onUninstall: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        CollapsibleSection(
            title = stringResource(R.string.toolset_skill_filters),
            collapsible = true,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Search skills") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                singleLine = true,
            )
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(
                    selected = category == null,
                    onClick = { onCategoryChange(null) },
                    label = { Text("All") },
                    modifier = Modifier.padding(end = 4.dp),
                )
                categories.forEach { value ->
                    FilterChip(
                        selected = category == value,
                        onClick = { onCategoryChange(value) },
                        label = { Text(value) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        }
        LazyColumn {
            items(skills, key = { it.name }) { skill ->
                Surface(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, style = MaterialTheme.typography.titleLarge)
                            Text(skill.description ?: "")
                            skill.category?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                        }
                        Switch(
                            checked = skill.enabled == true,
                            enabled = !busy,
                            onCheckedChange = { onToggle(skill.name, it) },
                        )
                        Box {
                            var menuExpanded by remember(skill.name) { mutableStateOf(false) }
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.toolset_more_actions),
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.toolset_edit_content)) },
                                    onClick = {
                                        menuExpanded = false
                                        onEdit(skill.name)
                                    },
                                    enabled = !busy,
                                )
                                if (skill.provenance == "hub") {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.toolset_uninstall)) },
                                        onClick = {
                                            menuExpanded = false
                                            onUninstall(skill.name)
                                        },
                                        enabled = !busy,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsetsList(
    toolsets: List<ToolsetInfo>,
    busy: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onConfigure: (String) -> Unit,
) {
    val groups = toolsets.groupBy { it.platform?.takeIf(String::isNotBlank) ?: "" }
    LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
        groups.toSortedMap().forEach { (platform, entries) ->
            item(key = "toolset-group:$platform") {
                CollapsibleSection(
                    title = platform.ifBlank { stringResource(R.string.toolset_group_other) },
                    collapsible = true,
                ) {
                    Column {
                        entries.forEach { toolset ->
                            ToolsetRow(
                                toolset = toolset,
                                busy = busy,
                                onToggle = onToggle,
                                onConfigure = onConfigure,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsetRow(
    toolset: ToolsetInfo,
    busy: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onConfigure: (String) -> Unit,
) {
    var menuExpanded by remember(toolset.name) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(toolset.label ?: toolset.name, style = MaterialTheme.typography.titleMedium)
                toolset.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (toolset.tools.isNotEmpty()) {
                    Text(toolset.tools.joinToString(), style = MaterialTheme.typography.labelSmall)
                }
            }
            val enabled = toolset.enabled ?: toolset.active ?: false
            Switch(
                checked = enabled,
                enabled = toolset.available != false && !busy,
                onCheckedChange = { onToggle(toolset.name, it) },
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.toolset_more_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.toolset_configure)) },
                        onClick = {
                            menuExpanded = false
                            onConfigure(toolset.name)
                        },
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolsetConfigLoadingDialog(
    name: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.toolset_config_loading_title)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.toolset_config_loading, name))
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.toolset_cancel)) }
        },
    )
}

@Composable
private fun ToolsetConfigErrorDialog(
    state: ToolsetConfigState.Error,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.toolset_config_error_title)) },
        text = { Text(state.message) },
        confirmButton = {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.toolset_retry)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.toolset_cancel)) }
        },
    )
}

@Composable
private fun ToolsetConfigDialog(
    state: ToolsetConfigState.Ready,
    busy: Boolean,
    onDismiss: () -> Unit,
    onProviderSelect: (String, String) -> Unit,
    onSaveEnv: (String, Map<String, String>, (Boolean) -> Unit) -> Unit,
    onModelSelect: (String, String, String?) -> Unit,
    onPostSetup: (String, String) -> Unit,
) {
    val config = state.config
    var drafts by remember(config.name) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var setupKey by remember(config.name) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.toolset_config_title, config.name)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!config.hasCategory) {
                    Text(stringResource(R.string.toolset_config_no_category))
                } else if (config.providers.isEmpty()) {
                    Text(stringResource(R.string.toolset_config_no_providers))
                } else {
                    config.providers.forEach { provider ->
                        val active = provider.isActive || provider.name == config.activeProvider
                        val providerTitle = if (active) {
                            stringResource(R.string.toolset_provider_selected_title, provider.name)
                        } else {
                            provider.name
                        }
                        CollapsibleSection(title = providerTitle, collapsible = true) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOfNotNull(provider.badge, provider.tag, provider.status)
                                    .takeIf { it.isNotEmpty() }
                                    ?.let {
                                        Text(
                                            it.joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                if (provider.requiresNousAuth) {
                                    Text(
                                        stringResource(R.string.toolset_provider_auth_required),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (provider.envFields.isNotEmpty()) {
                                    Text(
                                        stringResource(R.string.toolset_environment_fields),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    provider.envFields.forEach { field ->
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    field.prompt?.takeIf { it.isNotBlank() } ?: field.key,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                if (field.isSet) {
                                                    Text(
                                                        stringResource(R.string.toolset_saved),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            }
                                            OutlinedTextField(
                                                value = drafts[field.key].orEmpty(),
                                                onValueChange = { value ->
                                                    drafts = drafts + (field.key to value)
                                                },
                                                label = { Text(field.key) },
                                                placeholder = {
                                                    field.defaultValue?.takeIf { it.isNotBlank() }?.let { Text(it) }
                                                },
                                                visualTransformation = PasswordVisualTransformation(),
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(),
                                                enabled = !busy,
                                            )
                                            field.url?.takeIf { it.isNotBlank() }?.let {
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                    val values = provider.envFields.mapNotNull { field ->
                                        drafts[field.key]?.trim()?.takeIf { it.isNotBlank() }?.let {
                                            field.key to it
                                        }
                                    }.toMap()
                                    TextButton(
                                        onClick = {
                                            onSaveEnv(config.name, values) { succeeded ->
                                                drafts = clearSubmittedToolsetDrafts(
                                                    currentDrafts = drafts,
                                                    submitted = values,
                                                    succeeded = succeeded,
                                                )
                                            }
                                        },
                                        enabled = values.isNotEmpty() && !busy,
                                    ) {
                                        Text(stringResource(R.string.toolset_save_values))
                                    }
                                }
                                if (!active) {
                                    TextButton(
                                        onClick = { onProviderSelect(config.name, provider.name) },
                                        enabled = !busy,
                                    ) {
                                        Text(stringResource(R.string.toolset_select_provider))
                                    }
                                }
                                provider.postSetup?.takeIf { it.isNotBlank() }?.let { key ->
                                    OutlinedButton(
                                        onClick = { setupKey = key },
                                        enabled = !busy,
                                    ) {
                                        Text(stringResource(R.string.toolset_run_setup))
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.models.hasModels && state.models.options.isNotEmpty()) {
                    ToolsetModelSection(
                        models = state.models,
                        busy = busy,
                        onSelect = { model ->
                            onModelSelect(config.name, model, state.models.provider ?: config.activeProvider)
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.toolset_done)) }
        },
        dismissButton = {},
    )

    setupKey?.let { key ->
        AlertDialog(
            onDismissRequest = { setupKey = null },
            title = { Text(stringResource(R.string.toolset_setup_confirm_title)) },
            text = { Text(stringResource(R.string.toolset_setup_confirm_message, key)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        setupKey = null
                        onPostSetup(config.name, key)
                    },
                    enabled = !busy,
                ) { Text(stringResource(R.string.toolset_run_setup)) }
            },
            dismissButton = {
                TextButton(onClick = { setupKey = null }) {
                    Text(stringResource(R.string.toolset_cancel))
                }
            },
        )
    }
}

@Composable
private fun ToolsetModelSection(
    models: ToolsetModels,
    busy: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember(models.name) { mutableStateOf(false) }
    val current = models.options.firstOrNull { it.id == models.current }
        ?: models.options.firstOrNull { it.id == models.default }
        ?: models.options.first()
    CollapsibleSection(
        title = stringResource(R.string.toolset_model_section),
        collapsible = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = !busy) {
                    Text(current.display)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    models.options.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(option.display)
                                    listOfNotNull(option.speed, option.strengths, option.price)
                                        .takeIf { it.isNotEmpty() }
                                        ?.let {
                                            Text(
                                                it.joinToString(" · "),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                }
                            },
                            onClick = {
                                expanded = false
                                onSelect(option.id)
                            },
                        )
                    }
                }
            }
            models.default?.let { defaultModel ->
                Text(
                    stringResource(R.string.toolset_model_default, defaultModel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HubList(
    query: String,
    results: List<HubSkill>,
    detail: String?,
    busy: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPreview: (String) -> Unit,
    onScan: (String) -> Unit,
    onInstall: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("Search Hermes Skills Hub") },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        singleLine = true,
    )
    Button(onClick = onSearch, enabled = !busy && query.isNotBlank()) { Text(if (busy) "Working…" else "Search") }
    detail?.let {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)) }
    }
    LazyColumn {
        items(results, key = { it.identifier }) { skill ->
            Surface(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(skill.name, style = MaterialTheme.typography.titleMedium)
                    skill.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(listOfNotNull(skill.source, skill.trust_level).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                    Row {
                        TextButton(enabled = !busy, onClick = { onPreview(skill.identifier) }) { Text("Preview") }
                        TextButton(enabled = !busy, onClick = { onScan(skill.identifier) }) { Text("Scan") }
                        TextButton(enabled = !busy, onClick = { onInstall(skill.identifier) }) { Text("Install") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillContentEditor(
    editor: SkillEditorState.Ready,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, SkillContentFields, (Boolean) -> Unit) -> Unit,
) {
    var name by remember(editor.targetName) { mutableStateOf(editor.fields.name) }
    var description by remember(editor.targetName) { mutableStateOf(editor.fields.description) }
    var body by remember(editor.targetName) { mutableStateOf(editor.fields.body) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${editor.targetName}") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Body") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 12,
                )
                editor.validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                editor.path?.let {
                    Text("Path: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val submitted = SkillContentFields(name, description, body)
                    onSave(editor.targetName, submitted) { succeeded ->
                        val current = SkillContentFields(name, description, body)
                        if (shouldCloseSkillEditorAfterSave(submitted, current, succeeded)) {
                            onDismiss()
                        }
                    }
                },
                enabled = !busy,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
