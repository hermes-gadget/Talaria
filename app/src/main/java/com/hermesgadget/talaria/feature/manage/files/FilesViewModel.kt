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

import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.domain.model.ManagedFileEntry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

data class ManagedFilePreview(
    val path: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val binary: Boolean,
    val text: String = "",
)

data class ManagedUploadCandidate(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val targetPath: String,
)

enum class FileTransferPhase {
    PREPARING,
    SENDING,
}

sealed interface FileUploadState {
    data object Idle : FileUploadState

    data class Running(
        val displayName: String,
        val phase: FileTransferPhase,
        val bytesSent: Long = 0L,
        val totalBytes: Long = -1L,
    ) : FileUploadState

    data class Complete(val displayName: String) : FileUploadState

    data class Failed(val message: String) : FileUploadState
}

sealed interface FileDownloadState {
    data object Idle : FileDownloadState

    data class Downloading(
        val displayName: String,
        val bytesCopied: Long = 0L,
        val totalBytes: Long = -1L,
    ) : FileDownloadState

    data class Ready(
        val file: File,
        val displayName: String,
        val mimeType: String,
    ) : FileDownloadState

    data class Saving(
        val displayName: String,
        val bytesCopied: Long = 0L,
        val totalBytes: Long = -1L,
    ) : FileDownloadState

    data class Complete(val displayName: String) : FileDownloadState

    data class Failed(val message: String) : FileDownloadState
}

data class FilesUiState(
    val path: String = "",
    val parent: String? = null,
    val root: String? = null,
    val lockedRoot: String? = null,
    val canChangePath: Boolean = true,
    val entries: List<ManagedFileEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val preview: ManagedFilePreview? = null,
    val previewLoading: Boolean = false,
    val previewType: FilePreviewType? = null,
    val previewBytes: ByteArray? = null,
    val previewMimeType: String? = null,
    val editing: Boolean = false,
    val editDraft: String = "",
    val saving: Boolean = false,
    val previewError: String? = null,
    val confirmSave: Boolean = false,
    val shareLoading: Boolean = false,
    val sharePayload: FileSharePayload? = null,
    val shareError: String? = null,
    val actionLoading: Boolean = false,
    val actionError: String? = null,
    val uploadCandidate: ManagedUploadCandidate? = null,
    val uploadState: FileUploadState = FileUploadState.Idle,
    val downloadState: FileDownloadState = FileDownloadState.Idle,
)

/** Managed Hermes host file browser and transfer surface (ROADMAP items 1 and 19). */
class FilesViewModel(
    private val api: HermesApi = TalariaApp.instance.container.clientFactory.api(),
    private val cacheDirectory: File = TalariaApp.instance.cacheDir,
) : ViewModel() {
    private val _ui = MutableStateFlow(FilesUiState())
    val ui: StateFlow<FilesUiState> = _ui.asStateFlow()

    private var uploadResolver: ContentResolver? = null

    init {
        open(null)
    }

    /** Lists the managed root when [path] is null, or a managed directory otherwise. */
    fun open(path: String?) {
        val requestedPath = path?.takeIf { it.isNotBlank() }
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { api.listManagedFiles(path = requestedPath) }.fold(
                onSuccess = { response ->
                    _ui.update {
                        it.copy(
                            path = response.path.takeIf { value -> value.isNotBlank() }
                                ?: requestedPath
                                ?: "/",
                            parent = response.parent,
                            root = response.root,
                            lockedRoot = response.lockedRoot,
                            canChangePath = response.canChangePath,
                            entries = response.entries,
                            loading = false,
                            error = null,
                        )
                    }
                },
                onFailure = { error ->
                    _ui.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: appString(R.string.files_error_load_directory),
                        )
                    }
                },
            )
        }
    }

    fun refresh() {
        open(_ui.value.path.takeIf { it.isNotBlank() })
    }

    fun refreshOnResume() {
        if (_ui.value.path.isNotBlank()) refresh()
    }

    fun up() {
        val state = _ui.value
        if (state.canChangePath) state.parent?.let(::open)
    }

    fun openFile(entry: ManagedFileEntry) {
        val initialType = previewTypeFor(entry.name, entry.mimeType)
        _ui.update {
            it.copy(
                preview = ManagedFilePreview(
                    path = entry.path,
                    name = entry.name.ifBlank { entry.path.substringAfterLast('/') },
                    size = entry.size ?: 0L,
                    mimeType = entry.mimeType.orEmpty(),
                    binary = initialType != FilePreviewType.TEXT,
                ),
                previewLoading = true,
                previewType = initialType,
                previewBytes = null,
                previewMimeType = entry.mimeType,
                editing = false,
                editDraft = "",
                saving = false,
                previewError = null,
                confirmSave = false,
                shareLoading = false,
                sharePayload = null,
                shareError = null,
            )
        }

        viewModelScope.launch {
            runCatching {
                if (initialType == FilePreviewType.IMAGE) {
                    val response = api.getMediaDataUrl(entry.path)
                    val parsed = parseDataUrl(response.dataUrl)
                    PreviewPayload(
                        type = FilePreviewType.IMAGE,
                        mimeType = normalizedMime(entry.name, entry.mimeType, parsed.mimeType),
                        bytes = parsed.bytes,
                        text = "",
                        size = (entry.size ?: 0L).takeIf { it > 0L } ?: parsed.bytes.size.toLong(),
                        name = entry.name,
                    )
                } else {
                    val response = api.readManagedFile(entry.path)
                    val parsed = parseDataUrl(response.dataUrl)
                    val name = response.name.ifBlank { entry.name }
                    val mimeType = normalizedMime(name, response.mimeType, parsed.mimeType)
                    val type = managedPreviewType(name, mimeType)
                    PreviewPayload(
                        type = type,
                        mimeType = mimeType,
                        bytes = parsed.bytes,
                        text = if (type == FilePreviewType.TEXT) {
                            parsed.bytes.toString(Charsets.UTF_8)
                        } else {
                            ""
                        },
                        size = response.size.takeIf { it > 0L } ?: parsed.bytes.size.toLong(),
                        name = name,
                    )
                }
            }.fold(
                onSuccess = { payload ->
                    updateCurrentPreview(entry.path) {
                        it.copy(
                            previewLoading = false,
                            preview = ManagedFilePreview(
                                path = entry.path,
                                name = payload.name.ifBlank { entry.name },
                                size = payload.size,
                                mimeType = payload.mimeType,
                                binary = payload.type != FilePreviewType.TEXT,
                                text = payload.text,
                            ),
                            previewType = payload.type,
                            previewBytes = payload.bytes.takeIf { payload.type != FilePreviewType.TEXT },
                            previewMimeType = payload.mimeType,
                            editDraft = payload.text,
                            previewError = null,
                            shareError = null,
                        )
                    }
                },
                onFailure = { error ->
                    updateCurrentPreview(entry.path) {
                        it.copy(
                            previewLoading = false,
                            previewError = error.message ?: appString(R.string.files_error_read_file),
                        )
                    }
                },
            )
        }
    }

    fun beginEdit() = _ui.update { state ->
        val file = state.preview
        if (file == null || file.binary) state
        else state.copy(editing = true, editDraft = file.text, previewError = null)
    }

    fun updateDraft(value: String) = _ui.update { it.copy(editDraft = value) }

    fun cancelEdit() = _ui.update {
        it.copy(
            editing = false,
            editDraft = it.preview?.text.orEmpty(),
            confirmSave = false,
        )
    }

    fun saveEdit() {
        val state = _ui.value
        val file = state.preview ?: return
        if (!state.editing || file.binary || state.saving) return
        _ui.update { it.copy(confirmSave = true) }
    }

    fun cancelSave() = _ui.update { it.copy(confirmSave = false) }

    fun confirmSave() {
        val state = _ui.value
        val file = state.preview ?: return
        if (!state.confirmSave || !state.editing || file.binary || state.saving) return
        val draft = state.editDraft
        val path = file.path
        _ui.update { it.copy(confirmSave = false, saving = true, previewError = null) }
        viewModelScope.launch {
            runCatching {
                api.uploadManagedFile(
                    managedUploadBody(
                        path = path,
                        dataUrl = dataUrlFor(draft.toByteArray(Charsets.UTF_8), "text/plain"),
                        overwrite = true,
                    ),
                )
            }.fold(
                onSuccess = {
                    updateCurrentPreview(path) {
                        it.copy(
                            preview = file.copy(
                                size = draft.toByteArray(Charsets.UTF_8).size.toLong(),
                                mimeType = "text/plain",
                                text = draft,
                            ),
                            previewType = FilePreviewType.TEXT,
                            previewBytes = null,
                            previewMimeType = "text/plain",
                            editDraft = draft,
                            editing = false,
                            saving = false,
                            previewError = null,
                            shareError = null,
                        )
                    }
                },
                onFailure = { error ->
                    updateCurrentPreview(path) {
                        it.copy(
                            saving = false,
                            previewError = error.message ?: appString(R.string.files_error_save_file),
                        )
                    }
                },
            )
        }
    }

    fun sharePreview() {
        val state = _ui.value
        val file = state.preview ?: return
        val type = state.previewType ?: return
        if (state.previewLoading || state.shareLoading) return
        _ui.update { it.copy(shareLoading = true, sharePayload = null, shareError = null) }
        viewModelScope.launch {
            runCatching {
                val bytes = when (type) {
                    FilePreviewType.TEXT -> file.text.toByteArray(Charsets.UTF_8)
                    FilePreviewType.IMAGE,
                    FilePreviewType.BINARY,
                    -> state.previewBytes ?: readPreviewBytes(file.path, type)
                }
                FileSharePayload(
                    path = file.path,
                    mimeType = effectiveShareMimeType(file.name, file.mimeType, type),
                    bytes = bytes,
                )
            }.fold(
                onSuccess = { payload ->
                    updateCurrentPreview(file.path) {
                        it.copy(shareLoading = false, sharePayload = payload, shareError = null)
                    }
                },
                onFailure = { error ->
                    updateCurrentPreview(file.path) {
                        it.copy(
                            shareLoading = false,
                            shareError = error.message ?: appString(R.string.files_error_prepare_share),
                        )
                    }
                },
            )
        }
    }

    fun clearSharePayload() = _ui.update { it.copy(sharePayload = null) }

    fun shareFailed(message: String) = _ui.update {
        it.copy(sharePayload = null, shareLoading = false, shareError = message)
    }

    fun closePreview() = _ui.update {
        it.copy(
            preview = null,
            previewLoading = false,
            previewType = null,
            previewBytes = null,
            previewMimeType = null,
            editing = false,
            editDraft = "",
            saving = false,
            previewError = null,
            confirmSave = false,
            shareLoading = false,
            sharePayload = null,
            shareError = null,
        )
    }

    fun prepareUpload(uri: Uri, resolver: ContentResolver) {
        val displayName = contentDisplayName(resolver, uri)
            .ifBlank { appString(R.string.files_upload_default_name) }
        val targetPath = runCatching { joinManagedPath(_ui.value.path, displayName) }
            .getOrDefault(displayName)
        uploadResolver = resolver
        _ui.update {
            it.copy(
                uploadCandidate = ManagedUploadCandidate(
                    uri = uri,
                    displayName = displayName,
                    mimeType = resolver.getType(uri),
                    targetPath = targetPath,
                ),
                uploadState = FileUploadState.Idle,
                actionError = null,
            )
        }
    }

    fun cancelUploadSelection() {
        uploadResolver = null
        _ui.update { it.copy(uploadCandidate = null) }
    }

    fun confirmUpload(overwrite: Boolean) {
        val candidate = _ui.value.uploadCandidate ?: return
        val resolver = uploadResolver ?: return
        val totalBytes = contentLength(resolver, candidate.uri)
        _ui.update {
            it.copy(
                uploadCandidate = null,
                uploadState = FileUploadState.Running(
                    displayName = candidate.displayName,
                    phase = FileTransferPhase.PREPARING,
                    totalBytes = totalBytes,
                ),
                actionError = null,
            )
        }
        uploadResolver = null
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    uploadCandidate(candidate, resolver, overwrite, totalBytes)
                }
            }.fold(
                onSuccess = {
                    _ui.update {
                        it.copy(uploadState = FileUploadState.Complete(candidate.displayName))
                    }
                    refresh()
                },
                onFailure = { error ->
                    _ui.update {
                        it.copy(
                            uploadState = FileUploadState.Failed(
                                error.message ?: appString(R.string.files_error_upload),
                            ),
                        )
                    }
                },
            )
        }
    }

    fun download(entry: ManagedFileEntry) {
        download(
            path = entry.path,
            displayName = entry.name.ifBlank { entry.path.substringAfterLast('/') },
            size = entry.size ?: -1L,
            mimeType = entry.mimeType ?: "application/octet-stream",
        )
    }

    fun downloadPreview() {
        val file = _ui.value.preview ?: return
        download(file.path, file.name, file.size, file.mimeType)
    }

    private fun download(path: String, displayName: String, size: Long, mimeType: String) {
        if (_ui.value.downloadState is FileDownloadState.Downloading ||
            _ui.value.downloadState is FileDownloadState.Saving
        ) return
        deleteReadyDownload()
        _ui.update {
            it.copy(
                downloadState = FileDownloadState.Downloading(
                    displayName = displayName,
                    totalBytes = size.takeIf { bytes -> bytes > 0L } ?: -1L,
                ),
                actionError = null,
            )
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val directory = File(cacheDirectory, "managed-downloads").apply { mkdirs() }
                    val output = File.createTempFile("hermes-file-", ".download", directory)
                    try {
                        api.downloadManagedFile(path).use { response ->
                            val total = response.contentLength().takeIf { it > 0L } ?: size
                            _ui.update {
                                it.copy(
                                    downloadState = FileDownloadState.Downloading(
                                        displayName = displayName,
                                        totalBytes = total,
                                    ),
                                )
                            }
                            response.byteStream().use { source ->
                                output.outputStream().use { destination ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    var copied = 0L
                                    while (true) {
                                        val count = source.read(buffer)
                                        if (count < 0) break
                                        destination.write(buffer, 0, count)
                                        copied += count
                                        _ui.update {
                                            it.copy(
                                                downloadState = FileDownloadState.Downloading(
                                                    displayName = displayName,
                                                    bytesCopied = copied,
                                                    totalBytes = total,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        FileDownloadState.Ready(output, displayName, mimeType)
                    } catch (error: Throwable) {
                        output.delete()
                        throw error
                    }
                }
            }.fold(
                onSuccess = { ready -> _ui.update { it.copy(downloadState = ready) } },
                onFailure = { error ->
                    _ui.update {
                        it.copy(
                            downloadState = FileDownloadState.Failed(
                                error.message ?: appString(R.string.files_error_download),
                            ),
                        )
                    }
                },
            )
        }
    }

    fun saveDownload(uri: Uri, resolver: ContentResolver) {
        val ready = _ui.value.downloadState as? FileDownloadState.Ready ?: return
        _ui.update {
            it.copy(
                downloadState = FileDownloadState.Saving(
                    displayName = ready.displayName,
                    totalBytes = ready.file.length(),
                ),
            )
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val output = resolver.openOutputStream(uri) ?: error("")
                    output.use { destination ->
                        ready.file.inputStream().use { source ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var copied = 0L
                            while (true) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                destination.write(buffer, 0, count)
                                copied += count
                                _ui.update {
                                    it.copy(
                                        downloadState = FileDownloadState.Saving(
                                            displayName = ready.displayName,
                                            bytesCopied = copied,
                                            totalBytes = ready.file.length(),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }.fold(
                onSuccess = {
                    ready.file.delete()
                    _ui.update {
                        it.copy(downloadState = FileDownloadState.Complete(ready.displayName))
                    }
                },
                onFailure = { error ->
                    _ui.update {
                        it.copy(
                            downloadState = FileDownloadState.Failed(
                                error.message ?: appString(R.string.files_error_save_download),
                            ),
                        )
                    }
                },
            )
        }
    }

    fun cancelDownload() {
        deleteReadyDownload()
        _ui.update { it.copy(downloadState = FileDownloadState.Idle) }
    }

    fun createDirectory(name: String) {
        val target = runCatching { joinManagedPath(_ui.value.path, name) }.getOrElse {
            _ui.update { state -> state.copy(actionError = appString(R.string.files_error_create_folder)) }
            return
        }
        if (_ui.value.actionLoading) return
        _ui.update { it.copy(actionLoading = true, actionError = null) }
        viewModelScope.launch {
            runCatching {
                api.createManagedDir(buildJsonObject { put("path", target) })
            }.fold(
                onSuccess = {
                    _ui.update { it.copy(actionLoading = false, actionError = null) }
                    refresh()
                },
                onFailure = { error ->
                    _ui.update {
                        it.copy(
                            actionLoading = false,
                            actionError = error.message
                                ?: appString(R.string.files_error_create_folder),
                        )
                    }
                },
            )
        }
    }

    fun delete(entry: ManagedFileEntry) {
        if (_ui.value.actionLoading) return
        _ui.update { it.copy(actionLoading = true, actionError = null) }
        viewModelScope.launch {
            runCatching { api.deleteManagedFile(entry.path) }.fold(
                onSuccess = {
                    _ui.update { state ->
                        state.copy(
                            actionLoading = false,
                            actionError = null,
                            preview = state.preview?.takeUnless { it.path == entry.path },
                            previewLoading = state.previewLoading && state.preview?.path != entry.path,
                            previewType = state.previewType.takeUnless { state.preview?.path == entry.path },
                            previewBytes = state.previewBytes.takeUnless { state.preview?.path == entry.path },
                        )
                    }
                    refresh()
                },
                onFailure = { error ->
                    _ui.update {
                        it.copy(
                            actionLoading = false,
                            actionError = error.message ?: appString(R.string.files_error_delete),
                        )
                    }
                },
            )
        }
    }

    private suspend fun uploadCandidate(
        candidate: ManagedUploadCandidate,
        resolver: ContentResolver,
        overwrite: Boolean,
        totalBytes: Long,
    ) {
        val mimeType = candidate.mimeType
            ?.takeIf { it.isNotBlank() }
            ?.toMediaType()
            ?: "application/octet-stream".toMediaType()
        if (totalBytes in 0L..INLINE_UPLOAD_LIMIT_BYTES) {
            val bytes = readContent(resolver, candidate.uri, totalBytes) { copied, total ->
                updateUploadProgress(candidate.displayName, FileTransferPhase.PREPARING, copied, total)
            }
            api.uploadManagedFile(
                managedUploadBody(
                    path = candidate.targetPath,
                    dataUrl = dataUrlFor(bytes, mimeType.toString()),
                    overwrite = overwrite,
                ),
            )
        } else {
            val body = ContentResolverRequestBody(
                resolver = resolver,
                uri = candidate.uri,
                mediaType = mimeType,
                length = totalBytes,
            ) { copied, total ->
                updateUploadProgress(candidate.displayName, FileTransferPhase.SENDING, copied, total)
            }
            api.uploadManagedFileStream(
                path = candidate.targetPath.toRequestBody("text/plain".toMediaType()),
                overwrite = overwrite.toString().toRequestBody("text/plain".toMediaType()),
                file = MultipartBody.Part.createFormData("file", candidate.displayName, body),
            )
        }
    }

    private fun updateUploadProgress(
        displayName: String,
        phase: FileTransferPhase,
        copied: Long,
        total: Long,
    ) {
        _ui.update {
            it.copy(
                uploadState = FileUploadState.Running(
                    displayName = displayName,
                    phase = phase,
                    bytesSent = copied,
                    totalBytes = total,
                ),
            )
        }
    }

    private suspend fun readPreviewBytes(path: String, type: FilePreviewType): ByteArray {
        return if (type == FilePreviewType.IMAGE) {
            parseDataUrl(api.getMediaDataUrl(path).dataUrl).bytes
        } else {
            parseDataUrl(api.readManagedFile(path).dataUrl).bytes
        }
    }

    private fun updateCurrentPreview(
        path: String,
        transform: (FilesUiState) -> FilesUiState,
    ) = _ui.update { state ->
        if (state.preview?.path == path) transform(state) else state
    }

    private fun managedUploadBody(path: String, dataUrl: String, overwrite: Boolean) =
        buildJsonObject {
            put("path", path)
            put("data_url", dataUrl)
            put("overwrite", overwrite)
        }

    private fun deleteReadyDownload() {
        val ready = _ui.value.downloadState as? FileDownloadState.Ready
        ready?.file?.delete()
    }

    private fun appString(@StringRes resourceId: Int): String = TalariaApp.instance.getString(resourceId)

    override fun onCleared() {
        deleteReadyDownload()
        super.onCleared()
    }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = FilesViewModel() as T
        }
    }
}

private data class PreviewPayload(
    val type: FilePreviewType,
    val mimeType: String,
    val bytes: ByteArray,
    val text: String,
    val size: Long,
    val name: String,
)

private fun managedPreviewType(name: String, mimeType: String): FilePreviewType {
    val mime = mimeType.substringBefore(';').trim().lowercase()
    return when {
        mime.startsWith("image/") -> FilePreviewType.IMAGE
        mime.startsWith("text/") || mime in textMimeTypes -> FilePreviewType.TEXT
        mime == "application/octet-stream" && isLikelyTextFile(name) -> FilePreviewType.TEXT
        mime.isBlank() && isLikelyTextFile(name) -> FilePreviewType.TEXT
        else -> FilePreviewType.BINARY
    }
}

private fun normalizedMime(name: String, responseMime: String?, dataUrlMime: String?): String {
    val response = responseMime?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
    val parsed = dataUrlMime?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
    return when {
        response != null && response != "application/octet-stream" -> response
        parsed != null -> parsed
        response != null -> response
        else -> mimeTypeFor(name)
    }
}

private fun effectiveShareMimeType(name: String, mimeType: String, type: FilePreviewType): String =
    when {
        type == FilePreviewType.TEXT -> "text/plain"
        mimeType.isNotBlank() && mimeType != "application/octet-stream" -> mimeType
        else -> mimeTypeFor(name)
    }

private val textMimeTypes = setOf(
    "application/json",
    "application/ld+json",
    "application/javascript",
    "application/xml",
    "application/yaml",
    "application/x-yaml",
)

private fun isLikelyTextFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf(
        "c",
        "cfg",
        "conf",
        "css",
        "csv",
        "gradle",
        "h",
        "html",
        "ini",
        "java",
        "js",
        "json",
        "kt",
        "log",
        "md",
        "properties",
        "py",
        "rb",
        "rs",
        "sh",
        "sql",
        "toml",
        "ts",
        "txt",
        "xml",
        "yaml",
        "yml",
    )
