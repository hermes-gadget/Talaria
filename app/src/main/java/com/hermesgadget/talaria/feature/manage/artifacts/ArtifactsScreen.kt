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

package com.hermesgadget.talaria.feature.manage.artifacts

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.core.util.formatHermesTimestamp
import com.hermesgadget.talaria.core.util.BoundedImage
import com.hermesgadget.talaria.core.util.ImageHandle
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import kotlin.math.ceil
import com.hermesgadget.talaria.core.util.suspendResult

private const val ARTIFACTS_PAGE_SIZE = 24

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactsScreen(
    onOpenSession: (String) -> Unit,
    vm: ArtifactsViewModel = viewModel(factory = ArtifactsViewModel.factory()),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ready = ui.load as? ArtifactLoadState.Ready
    val visible = remember(ready?.artifacts, ui.filter) {
        val kind = when (ui.filter) {
            ArtifactFilter.ALL -> null
            ArtifactFilter.IMAGE -> ArtifactKind.IMAGE
            ArtifactFilter.TEXT -> ArtifactKind.TEXT
            ArtifactFilter.ARCHIVE -> ArtifactKind.ARCHIVE
        }
        filterArtifacts(ready?.artifacts.orEmpty(), kind)
    }
    val pageCount = maxOf(1, ceil(visible.size / ARTIFACTS_PAGE_SIZE.toDouble()).toInt())
    val page = ui.page.coerceIn(0, pageCount - 1)
    val pageItems = visible.drop(page * ARTIFACTS_PAGE_SIZE).take(ARTIFACTS_PAGE_SIZE)

    LaunchedEffect(ui.shareRequest) {
        val request = ui.shareRequest ?: return@LaunchedEffect
        suspendResult {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = request.mimeType
                putExtra(android.content.Intent.EXTRA_STREAM, request.uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, request.subject)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ContextCompat.startActivity(
                context,
                android.content.Intent.createChooser(intent, "Share artifact"),
                null,
            )
        }
        vm.consumeShareRequest()
    }

    val previewOpen = ui.preview != null || ui.previewLoading || ui.previewError != null
    if (previewOpen) {
        ModalBottomSheet(
            onDismissRequest = vm::closePreview,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ArtifactPreviewSheet(
                preview = ui.preview,
                previewArtifact = ui.previewArtifact,
                loading = ui.previewLoading,
                error = ui.previewError,
                sharing = ui.sharing,
                onShare = { ui.preview?.artifact?.let(vm::share) },
                onOpenSession = {
                    (ui.preview?.artifact ?: ui.previewArtifact)?.sessionId?.let(onOpenSession)
                },
                onClose = vm::closePreview,
            )
        }
    }

    ScreenScaffold(
        title = "Artifacts",
        subtitle = ready?.artifacts?.size?.let { "$it found" },
        showProfileSwitcher = true,
        actions = {
            if (ui.load is ArtifactLoadState.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = vm::refresh, enabled = ui.load !is ArtifactLoadState.Loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh artifacts")
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ArtifactFilter.values().forEach { filter ->
                    FilterChip(
                        selected = ui.filter == filter,
                        onClick = { vm.setFilter(filter) },
                        label = { Text(filter.label()) },
                    )
                }
            }
            ui.shareError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            when (val load = ui.load) {
                ArtifactLoadState.Loading -> LoadingBox()
                is ArtifactLoadState.Failed -> ErrorBox(load.message, onRetry = vm::refresh)
                is ArtifactLoadState.Ready -> {
                    if (visible.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("No artifacts found", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Recent assistant and tool messages did not contain supported file paths.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = true),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(pageItems, key = { it.id }) { artifact ->
                                ArtifactRow(
                                    artifact = artifact,
                                    sharing = ui.sharing,
                                    onOpenSession = { onOpenSession(artifact.sessionId) },
                                    onPreview = { vm.openPreview(artifact) },
                                    onShare = { vm.share(artifact) },
                                )
                            }
                        }
                        ArtifactPagination(
                            page = page,
                            pageCount = pageCount,
                            shown = pageItems.size,
                            total = visible.size,
                            onPage = vm::setPage,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtifactRow(
    artifact: ArtifactRecord,
    sharing: Boolean,
    onOpenSession: () -> Unit,
    onPreview: () -> Unit,
    onShare: () -> Unit,
) {
    Surface(
        onClick = onOpenSession,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                artifact.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(artifact.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    artifact.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        artifact.kind.label(),
                        artifact.sessionTitle,
                        formatHermesTimestamp(artifact.timestamp),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onPreview) {
                    Icon(Icons.Filled.Visibility, contentDescription = "Preview ${artifact.label}")
                }
                IconButton(onClick = onShare, enabled = !sharing) {
                    Icon(Icons.Filled.Share, contentDescription = "Share ${artifact.label}")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtifactPreviewSheet(
    preview: ArtifactPreview?,
    previewArtifact: ArtifactRecord?,
    loading: Boolean,
    error: String?,
    sharing: Boolean,
    onShare: () -> Unit,
    onOpenSession: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(min = 120.dp, max = 560.dp),
    ) {
        Text(
            (preview?.artifact ?: previewArtifact)?.label ?: "Artifact preview",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            (preview?.artifact ?: previewArtifact)?.path.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            error != null -> Text(error, color = MaterialTheme.colorScheme.error)
            preview is ArtifactPreview.Image -> {
                val bitmapState by produceState<ImageBitmap?>(null, preview.handle.path) {
                    value = withContext(Dispatchers.Default) { decodeBitmap(preview.handle) }
                }
                val bitmap = bitmapState
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = preview.artifact.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                    )
                } else {
                    Text("This image format cannot be decoded on this device.")
                }
                Text(
                    "${preview.mimeType} · ${formatBytes(preview.byteSize)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            preview is ArtifactPreview.Text -> {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        preview.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Text(
                    listOfNotNull(
                        preview.language ?: "text",
                        formatBytes(preview.byteSize),
                        if (preview.truncated) "truncated" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            preview is ArtifactPreview.Binary -> {
                Text("Archive preview is not rendered on the device.")
                Text(
                    "${preview.mimeType} · ${formatBytes(preview.byteSize)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (preview != null || previewArtifact != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenSession) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Open session")
                }
                TextButton(onClick = onShare, enabled = preview != null && !sharing) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (sharing) "Preparing…" else "Share")
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ArtifactPagination(
    page: Int,
    pageCount: Int,
    shown: Int,
    total: Int,
    onPage: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            "${page * ARTIFACTS_PAGE_SIZE + 1}–${page * ARTIFACTS_PAGE_SIZE + shown} of $total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Text("Previous") }
        Text("${page + 1} / $pageCount", style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { onPage(page + 1) }, enabled = page + 1 < pageCount) { Text("Next") }
    }
}

private fun ArtifactFilter.label(): String = when (this) {
    ArtifactFilter.ALL -> "All"
    ArtifactFilter.IMAGE -> "Images"
    ArtifactFilter.TEXT -> "Text"
    ArtifactFilter.ARCHIVE -> "Archives"
}

private fun ArtifactKind.label(): String = when (this) {
    ArtifactKind.IMAGE -> "image"
    ArtifactKind.TEXT -> "text"
    ArtifactKind.ARCHIVE -> "archive"
}

private fun ArtifactRecord.icon() = when (kind) {
    ArtifactKind.IMAGE -> Icons.Filled.Image
    ArtifactKind.TEXT -> Icons.Filled.Description
    ArtifactKind.ARCHIVE -> Icons.Filled.Archive
}

private fun decodeBitmap(handle: ImageHandle) = runCatching {
    val file = java.io.File(handle.path)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    BoundedImage.validateBounds(bounds.outWidth, bounds.outHeight)
    val sample = BoundedImage.sampleSizeFor(bounds.outWidth, bounds.outHeight)
    BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )?.takeIf {
        it.width.toLong() * it.height.toLong() <= BoundedImage.MAX_DISPLAY_PIXELS
    }?.asImageBitmap()
}.getOrNull()

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_024L * 1_024L -> "%.1f KiB".format(bytes / 1_024.0)
    bytes < 1_024L * 1_024L * 1_024L -> "%.1f MiB".format(bytes / (1_024.0 * 1_024.0))
    else -> "%.1f GiB".format(bytes / (1_024.0 * 1_024.0 * 1_024.0))
}
