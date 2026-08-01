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
package com.hermesgadget.talaria.feature.manage.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.domain.model.FsEntry
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: FilesViewModel = viewModel(factory = FilesViewModel.factory())) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    ui.preview?.let { file ->
        ModalBottomSheet(
            onDismissRequest = { vm.closePreview() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                file.path.substringAfterLast('/'),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                buildString {
                    append(file.language ?: "text")
                    append(" · ")
                    append(formatBytes(file.byteSize))
                    if (file.binary) append(" · binary")
                    if (file.truncated) append(" · truncated")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (ui.editing) {
                OutlinedTextField(
                    value = ui.editDraft,
                    onValueChange = vm::updateDraft,
                    label = { Text("File contents") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    minLines = 12,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                ui.previewError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = vm::cancelEdit, enabled = !ui.saving) { Text("Cancel") }
                    Button(onClick = vm::saveEdit, enabled = !ui.saving) {
                        Text(if (ui.saving) "Saving…" else "Save")
                    }
                }
            } else {
                Text(
                    if (file.binary) "Binary file — preview unavailable." else file.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                )
                if (!file.binary && !file.truncated) {
                    TextButton(onClick = vm::beginEdit, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Edit")
                    }
                } else if (file.truncated) {
                    Text(
                        "Editing is disabled because only a preview was downloaded.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    ScreenScaffold(
        title = "Files",
        subtitle = ui.branch.takeIf { it.isNotBlank() }?.let { "branch $it" } ?: "host filesystem",
        showProfileSwitcher = true,
        actions = { TextButton(onClick = { vm.refresh() }) { Text("Refresh") } },
    ) {
        Column {
            // Path bar with cwd shortcut + up affordance.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = "Parent directory",
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { vm.up() }
                        .padding(4.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    ui.path.ifBlank { "/" },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                )
                if (ui.path != ui.cwd && ui.cwd.isNotBlank()) {
                    TextButton(onClick = { vm.open(ui.cwd) }) { Text("cwd") }
                }
            }

            when {
                ui.loading && ui.entries.isEmpty() -> LoadingBox()
                ui.error != null && ui.entries.isEmpty() -> ErrorBox(ui.error!!) { vm.refresh() }
                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (ui.entries.isEmpty()) {
                        item {
                            Text(
                                "Empty directory",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    items(ui.entries, key = { it.path }) { entry ->
                        FileRow(entry) {
                            if (entry.isDirectory) vm.open(entry.path) else vm.openFile(entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(entry: FsEntry, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Icon(
            if (entry.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
