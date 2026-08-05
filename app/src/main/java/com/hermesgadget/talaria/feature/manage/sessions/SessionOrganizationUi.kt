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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.core.data.db.LocalSessionCollectionKind
import com.hermesgadget.talaria.core.data.repo.LocalSessionCollection
import com.hermesgadget.talaria.core.data.repo.SessionOrganizationSnapshot

@Composable
internal fun LocalBadge() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            stringResource(R.string.sessions_local_badge),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
internal fun SessionLocalBadges(
    sessionId: String,
    organization: SessionOrganizationSnapshot,
) {
    val assigned = organization.collectionIdsFor(sessionId)
    val collections = organization.collections.filter { it.id in assigned }
    if (collections.isEmpty() && sessionId !in organization.favoriteSessionIds) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sessionId in organization.favoriteSessionIds) {
            LocalNamedBadge(stringResource(R.string.sessions_local_favorite))
        }
        collections.forEach { collection ->
            LocalNamedBadge(collection.name)
        }
    }
}

@Composable
private fun LocalNamedBadge(name: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            stringResource(R.string.sessions_local_named, name),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@Composable
internal fun SessionOrganizationDialog(
    sessionId: String,
    organization: SessionOrganizationSnapshot,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleCollection: (collectionId: Long, assigned: Boolean) -> Unit,
    onCreateCollection: (name: String, kind: LocalSessionCollectionKind) -> Unit,
) {
    var addKind by remember(sessionId) { mutableStateOf<LocalSessionCollectionKind?>(null) }
    var newCollectionName by remember(sessionId) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessions_organize_local)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LocalBadge()
                OrganizationCheckboxRow(
                    label = stringResource(R.string.sessions_favorite),
                    checked = sessionId in organization.favoriteSessionIds,
                    enabled = enabled,
                    onCheckedChange = { onToggleFavorite() },
                )
                CollectionSection(
                    title = stringResource(R.string.sessions_labels_local),
                    collections = organization.labels,
                    sessionId = sessionId,
                    organization = organization,
                    enabled = enabled,
                    onToggleCollection = onToggleCollection,
                    onAdd = {
                        newCollectionName = ""
                        addKind = LocalSessionCollectionKind.LABEL
                    },
                )
                CollectionSection(
                    title = stringResource(R.string.sessions_groups_local),
                    collections = organization.groups,
                    sessionId = sessionId,
                    organization = organization,
                    enabled = enabled,
                    onToggleCollection = onToggleCollection,
                    onAdd = {
                        newCollectionName = ""
                        addKind = LocalSessionCollectionKind.GROUP
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sessions_done))
            }
        },
    )

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
                        onCreateCollection(newCollectionName, kind)
                        addKind = null
                    },
                    enabled = enabled && newCollectionName.trim().isNotEmpty(),
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
}

@Composable
private fun CollectionSection(
    title: String,
    collections: List<LocalSessionCollection>,
    sessionId: String,
    organization: SessionOrganizationSnapshot,
    enabled: Boolean,
    onToggleCollection: (collectionId: Long, assigned: Boolean) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = onAdd, enabled = enabled) {
            Text(stringResource(R.string.sessions_add))
        }
    }
    if (collections.isEmpty()) {
        Text(
            stringResource(R.string.sessions_none_yet),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        collections.forEach { collection ->
            OrganizationCheckboxRow(
                label = collection.name,
                checked = collection.id in organization.collectionIdsFor(sessionId),
                enabled = enabled,
                onCheckedChange = { checked ->
                    onToggleCollection(collection.id, checked)
                },
            )
        }
    }
}

@Composable
private fun OrganizationCheckboxRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
