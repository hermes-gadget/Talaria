/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.manage.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.core.data.db.LocalSessionCollectionKind
import com.hermesgadget.talaria.core.data.repo.LocalSessionCollection
import com.hermesgadget.talaria.core.data.repo.SavedSessionFilter
import com.hermesgadget.talaria.core.data.repo.SessionOrganizationSnapshot
import com.hermesgadget.talaria.ui.components.ScreenScaffold

@Composable
fun SavedFiltersScreen(
    vm: SessionOrganizationViewModel = viewModel(factory = SessionOrganizationViewModel.factory()),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val organization = ui.organization
    var editorFilter by remember { mutableStateOf<SavedSessionFilter?>(null) }
    var deleteFilter by remember { mutableStateOf<SavedSessionFilter?>(null) }
    var deleteCollection by remember { mutableStateOf<LocalSessionCollection?>(null) }
    var addKind by remember { mutableStateOf<LocalSessionCollectionKind?>(null) }
    var newCollectionName by remember { mutableStateOf("") }

    editorFilter?.let { filter ->
        SavedFilterEditorDialog(
            filter = filter,
            organization = organization,
            enabled = !ui.busy,
            onDismiss = { editorFilter = null },
            onSave = {
                vm.saveFilter(it)
                editorFilter = null
            },
        )
    }

    deleteFilter?.let { filter ->
        AlertDialog(
            onDismissRequest = { deleteFilter = null },
            title = { Text(stringResource(R.string.sessions_delete_filter)) },
            text = { Text(stringResource(R.string.sessions_delete_filter_body, filter.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteFilter(filter.id)
                        deleteFilter = null
                    },
                ) {
                    Text(stringResource(R.string.sessions_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteFilter = null }) {
                    Text(stringResource(R.string.sessions_cancel))
                }
            },
        )
    }

    deleteCollection?.let { collection ->
        AlertDialog(
            onDismissRequest = { deleteCollection = null },
            title = { Text(stringResource(R.string.sessions_delete_local_collection)) },
            text = {
                Text(stringResource(R.string.sessions_delete_local_collection_body, collection.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteCollection(collection.id)
                        deleteCollection = null
                    },
                ) {
                    Text(stringResource(R.string.sessions_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCollection = null }) {
                    Text(stringResource(R.string.sessions_cancel))
                }
            },
        )
    }

    addKind?.let { kind ->
        AlertDialog(
            onDismissRequest = { addKind = null },
            title = {
                Text(
                    stringResource(
                        if (kind == LocalSessionCollectionKind.LABEL) {
                            R.string.sessions_add_label_local
                        } else {
                            R.string.sessions_add_group_local
                        },
                    ),
                )
            },
            text = {
                OutlinedTextField(
                    value = newCollectionName,
                    onValueChange = { newCollectionName = it },
                    label = { Text(stringResource(R.string.sessions_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.createCollection(newCollectionName, kind)
                        addKind = null
                    },
                    enabled = !ui.busy && newCollectionName.trim().isNotEmpty(),
                ) {
                    Text(stringResource(R.string.sessions_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { addKind = null }) {
                    Text(stringResource(R.string.sessions_cancel))
                }
            },
        )
    }

    ScreenScaffold(
        title = stringResource(R.string.sessions_saved_filters_title),
        subtitle = stringResource(R.string.sessions_local_badge),
        showProfileSwitcher = true,
        actions = {
            TextButton(
                onClick = {
                    editorFilter = SavedSessionFilter(connectionId = "", name = "")
                },
                enabled = !ui.busy,
            ) {
                Text(stringResource(R.string.sessions_add_filter))
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LocalBadge()
                Text(
                    stringResource(R.string.sessions_saved_filters_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    CollectionHeading(
                        title = stringResource(R.string.sessions_labels_local),
                        onAdd = {
                            newCollectionName = ""
                            addKind = LocalSessionCollectionKind.LABEL
                        },
                        enabled = !ui.busy,
                    )
                }
                items(organization.labels, key = { it.id }) { collection ->
                    CollectionRow(
                        collection = collection,
                        enabled = !ui.busy,
                        onDelete = { deleteCollection = collection },
                    )
                }
                item {
                    CollectionHeading(
                        title = stringResource(R.string.sessions_groups_local),
                        onAdd = {
                            newCollectionName = ""
                            addKind = LocalSessionCollectionKind.GROUP
                        },
                        enabled = !ui.busy,
                    )
                }
                items(organization.groups, key = { it.id }) { collection ->
                    CollectionRow(
                        collection = collection,
                        enabled = !ui.busy,
                        onDelete = { deleteCollection = collection },
                    )
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.sessions_saved_filters_heading),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        LocalBadge()
                    }
                }
                if (organization.savedFilters.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.sessions_no_saved_filters),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(organization.savedFilters, key = { it.id }) { filter ->
                        SavedFilterRow(
                            filter = filter,
                            organization = organization,
                            enabled = !ui.busy,
                            onEdit = { editorFilter = filter },
                            onDelete = { deleteFilter = filter },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionHeading(
    title: String,
    onAdd: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            LocalBadge()
        }
        TextButton(onClick = onAdd, enabled = enabled) {
            Text(stringResource(R.string.sessions_add))
        }
    }
}

@Composable
private fun CollectionRow(
    collection: LocalSessionCollection,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(collection.name, modifier = Modifier.weight(1f))
            TextButton(onClick = onDelete, enabled = enabled) {
                Text(stringResource(R.string.sessions_delete))
            }
        }
    }
}

@Composable
private fun SavedFilterRow(
    filter: SavedSessionFilter,
    organization: SessionOrganizationSnapshot,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val labelName = organization.labels.firstOrNull { it.id == filter.labelId }?.name
    val groupName = organization.groups.firstOrNull { it.id == filter.groupId }?.name
    val criteria = listOfNotNull(
        filter.source,
        filter.platform,
        filter.endReason,
        labelName,
        groupName,
    ).joinToString(" · ")
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(filter.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    criteria.ifBlank { stringResource(R.string.sessions_filter_anything) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit, enabled = enabled) {
                Text(stringResource(R.string.sessions_edit))
            }
            TextButton(onClick = onDelete, enabled = enabled) {
                Text(stringResource(R.string.sessions_delete))
            }
        }
    }
}

@Composable
private fun SavedFilterEditorDialog(
    filter: SavedSessionFilter?,
    organization: SessionOrganizationSnapshot,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (SavedSessionFilter) -> Unit,
) {
    val initial = filter ?: SavedSessionFilter(connectionId = "", name = "")
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var source by remember(initial.id) { mutableStateOf(initial.source.orEmpty()) }
    var platform by remember(initial.id) { mutableStateOf(initial.platform.orEmpty()) }
    var endReason by remember(initial.id) { mutableStateOf(initial.endReason.orEmpty()) }
    var labelId by remember(initial.id) { mutableStateOf(initial.labelId) }
    var groupId by remember(initial.id) { mutableStateOf(initial.groupId) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    var labelMenu by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    val nameRequiredMessage = stringResource(R.string.sessions_filter_name_required)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessions_filter_editor_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LocalBadge()
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.sessions_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text(stringResource(R.string.sessions_filter_source)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = platform,
                    onValueChange = { platform = it },
                    label = { Text(stringResource(R.string.sessions_filter_platform)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = endReason,
                    onValueChange = { endReason = it },
                    label = { Text(stringResource(R.string.sessions_filter_end_reason)) },
                    singleLine = true,
                )
                CollectionPicker(
                    label = stringResource(R.string.sessions_filter_label),
                    selected = organization.labels.firstOrNull { it.id == labelId }?.name,
                    options = organization.labels,
                    expanded = labelMenu,
                    onExpand = { labelMenu = true },
                    onDismiss = { labelMenu = false },
                    onClear = { labelId = null; labelMenu = false },
                    onSelect = { labelId = it.id; labelMenu = false },
                )
                CollectionPicker(
                    label = stringResource(R.string.sessions_filter_group),
                    selected = organization.groups.firstOrNull { it.id == groupId }?.name,
                    options = organization.groups,
                    expanded = groupMenu,
                    onExpand = { groupMenu = true },
                    onDismiss = { groupMenu = false },
                    onClear = { groupId = null; groupMenu = false },
                    onSelect = { groupId = it.id; groupMenu = false },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.trim().isBlank()) {
                        error = nameRequiredMessage
                    } else {
                        onSave(
                            initial.copy(
                                name = name,
                                source = source,
                                platform = platform,
                                endReason = endReason,
                                labelId = labelId,
                                groupId = groupId,
                            ),
                        )
                    }
                },
                enabled = enabled,
            ) {
                Text(stringResource(R.string.sessions_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sessions_cancel))
            }
        },
    )
}

@Composable
private fun CollectionPicker(
    label: String,
    selected: String?,
    options: List<LocalSessionCollection>,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onSelect: (LocalSessionCollection) -> Unit,
) {
    Box {
        OutlinedButton(onClick = onExpand, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (selected == null) {
                    label + ": " + stringResource(R.string.sessions_any)
                } else {
                    label + ": " + selected
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessions_any)) },
                onClick = onClear,
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}
