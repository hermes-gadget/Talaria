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

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.domain.model.FsEntry
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: FilesViewModel = viewModel(factory = FilesViewModel.factory())) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // A Files destination can stay alive in the navigation back stack. Refresh when
    // it becomes visible again, but never keep a network loop running off-screen.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.refreshOnResume()
            awaitCancellation()
        }
    }

    LaunchedEffect(ui.sharePayload) {
        val payload = ui.sharePayload ?: return@LaunchedEffect
        runCatching {
            val intent = buildFileShareIntent(context, payload)
            context.startActivity(Intent.createChooser(intent, "Share file"))
        }.onFailure { error ->
            vm.shareFailed(error.message ?: "Could not share file")
        }
        vm.clearSharePayload()
    }

    if (ui.confirmSave) {
        AlertDialog(
            onDismissRequest = vm::cancelSave,
            title = { Text("Overwrite file?") },
            text = {
                Text("This writes the edited text to the Hermes host. The current remote contents are checked before saving.")
            },
            confirmButton = {
                TextButton(onClick = vm::confirmSave) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelSave) { Text("Cancel") }
            },
        )
    }

    ui.preview?.let { file ->
        ModalBottomSheet(
            onDismissRequest = vm::closePreview,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            val type = ui.previewType
            val mimeType = ui.previewMimeType
                ?: file.mimeType
                ?: if (type == FilePreviewType.TEXT) "text/plain" else "application/octet-stream"
            val byteSize = file.byteSize.takeIf { it > 0 }
                ?: ui.previewBytes?.size?.toLong()
                ?: 0L

            Text(
                file.path.substringAfterLast('/').ifBlank { "File" },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                buildString {
                    append(formatBytes(byteSize))
                    append(" · ")
                    append(mimeType)
                    if (file.truncated) append(" · truncated")
                    if (type == FilePreviewType.BINARY || file.binary || type == FilePreviewType.IMAGE) {
                        append(" · read-only")
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (ui.previewLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("Loading preview…", modifier = Modifier.padding(top = 12.dp))
                }
            } else {
                when (type) {
                    FilePreviewType.IMAGE -> {
                        ui.previewBytes?.let { bytes ->
                            ZoomableImagePreview(
                                bytes = bytes,
                                contentDescription = file.path.substringAfterLast('/'),
                            )
                        } ?: Text(
                            "Image preview unavailable.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }

                    FilePreviewType.BINARY -> {
                        Text(
                            "Binary file — read-only.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }

                    FilePreviewType.TEXT -> {
                        if (ui.editing) {
                            OutlinedTextField(
                                value = ui.editDraft,
                                onValueChange = vm::updateDraft,
                                label = { Text("File contents") },
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                minLines = 12,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = vm::cancelEdit, enabled = !ui.saving) {
                                    Text("Cancel")
                                }
                                Button(onClick = vm::saveEdit, enabled = !ui.saving) {
                                    Text(if (ui.saving) "Saving…" else "Save")
                                }
                            }
                        } else {
                            Text(
                                file.text,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp)
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                            if (file.truncated) {
                                Text(
                                    "Editing is disabled because only a preview was downloaded.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                    }

                    null -> Unit
                }
            }

            ui.previewError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ui.shareError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (type == FilePreviewType.TEXT && !ui.editing && !file.binary && !file.truncated) {
                    TextButton(onClick = vm::beginEdit) { Text("Edit") }
                }
                TextButton(
                    onClick = vm::sharePreview,
                    enabled = !ui.previewLoading && !ui.shareLoading,
                ) {
                    Text(if (ui.shareLoading) "Preparing…" else "Share")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    ScreenScaffold(
        title = "Files",
        subtitle = ui.branch.takeIf { it.isNotBlank() }?.let { "branch $it" } ?: "host filesystem",
        showProfileSwitcher = true,
        actions = { TextButton(onClick = vm::refresh) { Text("Refresh") } },
    ) {
        PullToRefreshBox(
            isRefreshing = ui.loading,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
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
                    ui.loading && ui.entries.isEmpty() -> Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) { LoadingBox() }

                    ui.error != null && ui.entries.isEmpty() -> Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) { ErrorBox(ui.error!!, vm::refresh) }

                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
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
}

@Composable
private fun ZoomableImagePreview(bytes: ByteArray, contentDescription: String) {
    val bitmap = remember(bytes) {
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            .getOrNull()
    }
    if (bitmap == null) {
        Text(
            "The image could not be decoded on this device.",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp),
        )
        return
    }

    var scale by remember(bytes) { mutableFloatStateOf(1f) }
    var offset by remember(bytes) { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .pointerInput(bytes) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset += pan
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
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
