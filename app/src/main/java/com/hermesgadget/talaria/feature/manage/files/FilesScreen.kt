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
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.domain.model.ManagedFileEntry
import com.hermesgadget.talaria.ui.components.CollapsibleSection
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
    var actionsExpanded by remember { mutableStateOf(false) }
    var previewActionsExpanded by remember { mutableStateOf(false) }
    var createFolderDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ManagedFileEntry?>(null) }
    var folderName by remember { mutableStateOf("") }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { vm.prepareUpload(it, context.contentResolver) }
    }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        if (uri == null) vm.cancelDownload()
        else vm.saveDownload(uri, context.contentResolver)
    }

    val shareChooserTitle = stringResource(R.string.files_share_chooser)
    val shareFailureFallback = stringResource(R.string.files_error_share)

    // A Files destination can stay alive in the navigation back stack. Refresh when
    // it becomes visible again, but never keep a network loop running off-screen.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.refreshOnResume()
            awaitCancellation()
        }
    }

    val readyDownload = ui.downloadState as? FileDownloadState.Ready
    LaunchedEffect(readyDownload) {
        readyDownload?.let { downloadLauncher.launch(it.displayName) }
    }

    LaunchedEffect(ui.sharePayload) {
        val payload = ui.sharePayload ?: return@LaunchedEffect
        runCatching {
            val intent = buildFileShareIntent(context, payload)
            context.startActivity(Intent.createChooser(intent, shareChooserTitle))
        }.onFailure { error ->
            vm.shareFailed(error.message ?: shareFailureFallback)
        }
        vm.clearSharePayload()
    }

    ui.uploadCandidate?.let { candidate ->
        var overwrite by remember(candidate.uri) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = vm::cancelUploadSelection,
            title = { Text(stringResource(R.string.files_upload_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.files_upload_destination, candidate.targetPath))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = overwrite, onCheckedChange = { overwrite = it })
                        Text(stringResource(R.string.files_overwrite_existing))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmUpload(overwrite) }) {
                    Text(stringResource(R.string.files_upload_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelUploadSelection) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    if (createFolderDialog) {
        AlertDialog(
            onDismissRequest = { createFolderDialog = false },
            title = { Text(stringResource(R.string.files_create_folder_title)) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.files_folder_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = folderName.isNotBlank() && !ui.actionLoading,
                    onClick = {
                        createFolderDialog = false
                        vm.createDirectory(folderName)
                        folderName = ""
                    },
                ) { Text(stringResource(R.string.files_create)) }
            },
            dismissButton = {
                TextButton(onClick = { createFolderDialog = false }) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.files_delete_title)) },
            text = { Text(stringResource(R.string.files_delete_message, entry.name)) },
            confirmButton = {
                TextButton(
                    enabled = !ui.actionLoading,
                    onClick = {
                        deleteTarget = null
                        vm.delete(entry)
                    },
                ) { Text(stringResource(R.string.files_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    ui.preview?.let { file ->
        ModalBottomSheet(
            onDismissRequest = vm::closePreview,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            val type = ui.previewType ?: FilePreviewType.BINARY
            val mimeType = ui.previewMimeType
                ?.takeIf { it.isNotBlank() }
                ?: file.mimeType.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.files_binary_mime)
            val byteSize = file.size.takeIf { it > 0L } ?: ui.previewBytes?.size?.toLong() ?: 0L
            val displayName = file.name.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.files_file)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            R.string.files_file_metadata,
                            formattedBytes(byteSize),
                            mimeType,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { previewActionsExpanded = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.files_more),
                        )
                    }
                    DropdownMenu(
                        expanded = previewActionsExpanded,
                        onDismissRequest = { previewActionsExpanded = false },
                    ) {
                        if (type == FilePreviewType.TEXT && !ui.editing && !file.binary) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_edit)) },
                                onClick = {
                                    previewActionsExpanded = false
                                    vm.beginEdit()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_download)) },
                            onClick = {
                                previewActionsExpanded = false
                                vm.downloadPreview()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (ui.shareLoading) {
                                        stringResource(R.string.files_preparing)
                                    } else {
                                        stringResource(R.string.files_share)
                                    },
                                )
                            },
                            onClick = {
                                previewActionsExpanded = false
                                vm.sharePreview()
                            },
                            enabled = !ui.previewLoading && !ui.shareLoading,
                        )
                    }
                }
            }

            if (ui.previewLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.files_preview_loading),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            } else {
                when (type) {
                    FilePreviewType.IMAGE -> {
                        ui.previewBytes?.let { bytes ->
                            ZoomableImagePreview(
                                bytes = bytes,
                                contentDescription = file.name,
                            )
                        } ?: Text(
                            stringResource(R.string.files_image_preview_unavailable),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }

                    FilePreviewType.BINARY -> Text(
                        stringResource(R.string.files_binary_read_only),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )

                    FilePreviewType.TEXT -> {
                        if (ui.editing) {
                            OutlinedTextField(
                                value = ui.editDraft,
                                onValueChange = vm::updateDraft,
                                label = { Text(stringResource(R.string.files_file_contents)) },
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
                                    Text(stringResource(R.string.files_cancel))
                                }
                                Button(onClick = vm::saveEdit, enabled = !ui.saving) {
                                    Text(
                                        if (ui.saving) {
                                            stringResource(R.string.files_saving)
                                        } else {
                                            stringResource(R.string.files_save)
                                        },
                                    )
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
                        }
                    }
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
            Spacer(Modifier.height(24.dp))
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.files_title),
        subtitle = stringResource(R.string.files_subtitle),
        showProfileSwitcher = true,
        actions = {
            Box {
                IconButton(onClick = { actionsExpanded = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.files_more),
                    )
                }
                DropdownMenu(
                    expanded = actionsExpanded,
                    onDismissRequest = { actionsExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_refresh)) },
                        onClick = {
                            actionsExpanded = false
                            vm.refresh()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_upload)) },
                        onClick = {
                            actionsExpanded = false
                            uploadLauncher.launch(arrayOf("*/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_new_folder)) },
                        onClick = {
                            actionsExpanded = false
                            folderName = ""
                            createFolderDialog = true
                        },
                    )
                }
            }
        },
    ) {
        PullToRefreshBox(
            isRefreshing = ui.loading,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    ui.path.ifBlank { "/" },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )

                CollapsibleSection(
                    title = stringResource(R.string.files_location_section),
                    collapsible = true,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(
                            onClick = vm::up,
                            enabled = ui.canChangePath && ui.parent != null,
                        ) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = stringResource(R.string.files_parent_directory),
                            )
                        }
                        Text(
                            ui.path.ifBlank { "/" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (ui.root != null && ui.root != ui.path) {
                            TextButton(onClick = { vm.open(ui.root) }) {
                                Text(stringResource(R.string.files_root))
                            }
                        }
                    }
                }

                ui.actionError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                ui.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                if (ui.uploadState !is FileUploadState.Idle ||
                    ui.downloadState !is FileDownloadState.Idle
                ) {
                    CollapsibleSection(
                        title = stringResource(R.string.files_activity_section),
                        collapsible = false,
                    ) {
                        TransferStatus(ui)
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
                                    stringResource(R.string.files_empty_directory),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                        items(
                            items = ui.entries,
                            key = { entry -> "${entry.path}:${entry.name}" },
                        ) { entry ->
                            ManagedFileRow(
                                entry = entry,
                                onOpen = {
                                    if (entry.isDirectory) vm.open(entry.path) else vm.openFile(entry)
                                },
                                onDownload = { vm.download(entry) },
                                onDelete = { deleteTarget = entry },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferStatus(ui: FilesUiState) {
    when (val upload = ui.uploadState) {
        FileUploadState.Idle -> Unit
        is FileUploadState.Running -> {
            Text(
                stringResource(
                    if (upload.phase == FileTransferPhase.PREPARING) {
                        R.string.files_upload_preparing
                    } else {
                        R.string.files_uploading
                    },
                    upload.displayName,
                ),
            )
            TransferProgress(upload.bytesSent, upload.totalBytes)
        }
        is FileUploadState.Complete -> Text(
            stringResource(R.string.files_upload_complete, upload.displayName),
        )
        is FileUploadState.Failed -> Text(upload.message, color = MaterialTheme.colorScheme.error)
    }

    when (val download = ui.downloadState) {
        FileDownloadState.Idle -> Unit
        is FileDownloadState.Downloading -> {
            Text(stringResource(R.string.files_downloading, download.displayName))
            TransferProgress(download.bytesCopied, download.totalBytes)
        }
        is FileDownloadState.Ready -> Text(
            stringResource(R.string.files_download_ready, download.displayName),
        )
        is FileDownloadState.Saving -> {
            Text(stringResource(R.string.files_saving_download, download.displayName))
            TransferProgress(download.bytesCopied, download.totalBytes)
        }
        is FileDownloadState.Complete -> Text(
            stringResource(R.string.files_download_complete, download.displayName),
        )
        is FileDownloadState.Failed -> Text(download.message, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun TransferProgress(bytes: Long, total: Long) {
    val fraction = (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f).takeIf { total > 0L }
    if (fraction == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(
            progress = fraction,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(
                R.string.files_transfer_progress,
                formattedBytes(bytes),
                formattedBytes(total),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ZoomableImagePreview(bytes: ByteArray, contentDescription: String) {
    val bitmap = remember(bytes) {
        runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            .getOrNull()
    }
    if (bitmap == null) {
        Text(
            stringResource(R.string.files_image_decode_error),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp),
        )
        return
    }

    var scale by remember(bytes) { androidx.compose.runtime.mutableFloatStateOf(1f) }
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
private fun ManagedFileRow(
    entry: ManagedFileEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(entry.path) { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Icon(
            if (entry.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = stringResource(
                if (entry.isDirectory) R.string.files_folder else R.string.files_file,
            ),
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name.ifBlank { entry.path.substringAfterLast('/') },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = if (entry.isDirectory) {
                stringResource(R.string.files_folder)
            } else {
                val size = entry.size?.takeIf { it >= 0L }
                val formattedSize = if (size != null) formattedBytes(size) else null
                listOfNotNull(
                    formattedSize,
                    entry.mimeType?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
            }
            if (details.isNotBlank()) {
                Text(
                    details,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.files_more),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (entry.isDirectory) R.string.files_open else R.string.files_preview,
                            ),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onOpen()
                    },
                )
                if (!entry.isDirectory) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_download)) },
                        onClick = {
                            menuExpanded = false
                            onDownload()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_delete)) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun formattedBytes(bytes: Long): String = when {
    bytes < 1024L -> stringResource(R.string.files_size_bytes, bytes)
    bytes < 1024L * 1024L -> stringResource(R.string.files_size_kilobytes, bytes / 1024L)
    else -> stringResource(R.string.files_size_megabytes, bytes / (1024L * 1024L))
}
