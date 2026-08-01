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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.domain.model.HubSkill
import com.hermesgadget.talaria.domain.model.SkillInfo
import com.hermesgadget.talaria.domain.model.ToolsetInfo
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
                    if (tab == 2 || content.skills.any { it.provenance == "hub" }) {
                        TextButton(onClick = vm::updateHub, enabled = !content.busy) { Text("Update") }
                    }
                    TextButton(onClick = vm::refresh, enabled = !content.busy) { Text("Refresh") }
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
                    onSave = vm::saveContent,
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
                            Row {
                                TextButton(onClick = { onEdit(skill.name) }, enabled = !busy) { Text("Edit content") }
                                if (skill.provenance == "hub") {
                                    TextButton(onClick = { onUninstall(skill.name) }, enabled = !busy) { Text("Uninstall") }
                                }
                            }
                        }
                        Switch(
                            checked = skill.enabled == true,
                            enabled = !busy,
                            onCheckedChange = { onToggle(skill.name, it) },
                        )
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
) {
    LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
        items(toolsets, key = { it.name }) { toolset ->
            Surface(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(toolset.label ?: toolset.name, style = MaterialTheme.typography.titleMedium)
                        toolset.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }
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
    onSave: (String, SkillContentFields) -> Unit,
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
                onClick = { onSave(editor.targetName, SkillContentFields(name, description, body)) },
                enabled = !busy,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
