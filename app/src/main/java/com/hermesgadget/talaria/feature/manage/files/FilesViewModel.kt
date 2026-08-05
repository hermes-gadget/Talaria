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
import com.hermesgadget.talaria.core.util.suspendResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
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
        val file: File? = null,
    ) : FileDownloadState

    data class Complete(val displayName: String) : FileDownloadState

    data class Failed(
        val message: String,
        val file: File? = null,
        val displayName: String = "",
        val mimeType: String = "application/octet-stream",
    ) : FileDownloadState
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
    private val shareFileManager: ShareFileManager? = null,
) : ViewModel() {
    private val _ui = MutableStateFlow(FilesUiState())
    val ui: StateFlow<FilesUiState> = _ui.asStateFlow()

    private var uploadResolver: ContentResolver? = null
    private var directoryJob: Job? = null
    private var directoryGeneration = 0L
    private var requestedDirectoryPath: String? = null
    private var downloadJob: Job? = null
    private var saveJob: Job? = null
    private var downloadGeneration = 0L
    private val defaultShareFileManager by lazy { ShareFileManager(cacheDirectory) }

    init {
        open(null)
    }

    /** Lists the managed root when [path] is null, or a managed directory otherwise. */
    fun open(path: String?) {
        val requestedPath = path?.takeIf { it.isNotBlank() }
        val generation = ++directoryGeneration
        requestedDirectoryPath = requestedPath
        directoryJob?.cancel()
        _ui.update { it.copy(loading = true, error = null) }
        directoryJob = viewModelScope.launch {
            try {
                val response = api.listManagedFiles(path = requestedPath)
                if (!isCurrentDirectoryRequest(generation, requestedPath)) return@launch
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrentDirectoryRequest(generation, requestedPath)) {
                    _ui.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: appString(R.string.files_error_load_directory),
                        )
                    }
                }
            }
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
            suspendResult {
                if (initialType == FilePreviewType.IMAGE) {
                    val response = api.getMediaDataUrl(entry.path)
                    val parsed = parseManagedPreviewDataUrl(response.dataUrl)
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
                    val parsed = parseManagedPreviewDataUrl(response.dataUrl, response.size)
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
            try {
                val draftBytes = draft.toByteArray(Charsets.UTF_8)
                require(draftBytes.size.toLong() <= INLINE_UPLOAD_LIMIT_BYTES) {
                    "Edited file exceeds the ${INLINE_UPLOAD_LIMIT_BYTES / (1024 * 1024)} MiB limit"
                }
                api.uploadManagedFile(
                    managedUploadBody(
                        path = path,
                        dataUrl = dataUrlFor(draftBytes, "text/plain"),
                        overwrite = true,
                    ),
                )
                updateCurrentPreview(path) {
                    it.copy(
                        preview = file.copy(
                            size = draftBytes.size.toLong(),
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateCurrentPreview(path) {
                    it.copy(
                        saving = false,
                        previewError = error.message ?: appString(R.string.files_error_save_file),
                    )
                }
            }
        }
    }

    fun sharePreview() {
        val state = _ui.value
        val file = state.preview ?: return
        val type = state.previewType ?: return
        if (state.previewLoading || state.shareLoading) return
        _ui.update { it.copy(shareLoading = true, sharePayload = null, shareError = null) }
        viewModelScope.launch {
            suspendResult {
                withContext(Dispatchers.IO) {
                    val coroutineContext = currentCoroutineContext()
                    val source = when (type) {
                        FilePreviewType.TEXT -> ByteArrayInputStream(file.text.toByteArray(Charsets.UTF_8))
                        FilePreviewType.IMAGE,
                        FilePreviewType.BINARY,
                        -> ByteArrayInputStream(state.previewBytes ?: readPreviewBytes(file.path, type))
                    }
                    val output = (shareFileManager ?: defaultShareFileManager).createShareFile(
                        prefix = "hermes-file-",
                        suffix = "-${file.name}",
                        source = source,
                        beforeRead = { coroutineContext.ensureActive() },
                    )
                    FileSharePayload(
                        path = file.path,
                        mimeType = effectiveShareMimeType(file.name, file.mimeType, type),
                        file = output,
                    )
                }
            }.fold(
                onSuccess = { payload ->
                    if (_ui.value.preview?.path == file.path) {
                        _ui.update {
                            it.copy(shareLoading = false, sharePayload = payload, shareError = null)
                        }
                    } else {
                        (shareFileManager ?: defaultShareFileManager).deleteOwnedFile(payload.file)
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

    fun shareFailed(message: String) {
        _ui.value.sharePayload?.file?.let { file ->
            (shareFileManager ?: defaultShareFileManager).deleteOwnedFile(file)
        }
        _ui.update { it.copy(sharePayload = null, shareLoading = false, shareError = message) }
    }

    fun closePreview() {
        _ui.value.sharePayload?.file?.let { file ->
            (shareFileManager ?: defaultShareFileManager).deleteOwnedFile(file)
        }
        _ui.update {
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
            suspendResult {
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
        downloadGeneration += 1
        val generation = downloadGeneration
        downloadJob?.cancel()
        deleteOwnedDownload()
        _ui.update {
            it.copy(
                downloadState = FileDownloadState.Downloading(
                    displayName = displayName,
                    totalBytes = size.takeIf { bytes -> bytes > 0L } ?: -1L,
                ),
                actionError = null,
            )
        }
        downloadJob = viewModelScope.launch {
            var output: File? = null
            try {
                val ready = withContext(Dispatchers.IO) {
                    val coroutineContext = currentCoroutineContext()
                    api.downloadManagedFile(path).use { response ->
                        val total = response.contentLength().takeIf { it > 0L }
                            ?: size.takeIf { it > 0L }
                            ?: -1L
                        if (generation == downloadGeneration) {
                            _ui.update {
                                it.copy(
                                    downloadState = FileDownloadState.Downloading(
                                        displayName = displayName,
                                        totalBytes = total,
                                    ),
                                )
                            }
                        }
                        output = (shareFileManager ?: defaultShareFileManager).createManagedDownload(
                            prefix = "hermes-file-",
                            suffix = ".download",
                            source = response.byteStream(),
                            declaredBytes = total,
                            onProgress = { copied, reportedTotal ->
                                if (generation == downloadGeneration) {
                                    _ui.update {
                                        it.copy(
                                            downloadState = FileDownloadState.Downloading(
                                                displayName = displayName,
                                                bytesCopied = copied,
                                                totalBytes = reportedTotal,
                                            ),
                                        )
                                    }
                                }
                            },
                            beforeRead = { coroutineContext.ensureActive() },
                        )
                    }
                    FileDownloadState.Ready(output ?: error("Download did not produce a file"), displayName, mimeType)
                }
                if (generation == downloadGeneration) {
                    _ui.update { it.copy(downloadState = ready) }
                } else {
                    (shareFileManager ?: defaultShareFileManager).deleteOwnedFile(ready.file)
                }
            } catch (cancelled: CancellationException) {
                output?.let { (shareFileManager ?: defaultShareFileManager).deleteOwnedFile(it) }
                if (generation == downloadGeneration) {
                    _ui.update { it.copy(downloadState = FileDownloadState.Idle) }
                }
                throw cancelled
            } catch (error: Throwable) {
                output?.let { (shareFileManager ?: defaultShareFileManager).deleteOwnedFile(it) }
                if (generation == downloadGeneration) {
                    _ui.update {
                        it.copy(
                            downloadState = FileDownloadState.Failed(
                                error.message ?: appString(R.string.files_error_download),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun saveDownload(uri: Uri, resolver: ContentResolver) {
        val payload = currentDownloadPayload() ?: return
        downloadGeneration += 1
        val generation = downloadGeneration
        saveJob?.cancel()
        _ui.update {
            it.copy(
                downloadState = FileDownloadState.Saving(
                    displayName = payload.displayName,
                    totalBytes = payload.file.length(),
                    file = payload.file,
                ),
            )
        }
        saveJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = resolver.openOutputStream(uri) ?: error("")
                    output.use { destination ->
                        payload.file.inputStream().use { source ->
                            val total = payload.file.length()
                            val progress = ProgressThrottler(onProgress = { copied, reportedTotal ->
                                if (generation == downloadGeneration) {
                                    _ui.update {
                                        it.copy(
                                            downloadState = FileDownloadState.Saving(
                                                displayName = payload.displayName,
                                                bytesCopied = copied,
                                                totalBytes = reportedTotal,
                                                file = payload.file,
                                            ),
                                        )
                                    }
                                }
                            })
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var copied = 0L
                            while (true) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                destination.write(buffer, 0, count)
                                copied += count
                                progress.report(copied, total)
                            }
                            progress.complete(copied, total)
                        }
                    }
                }
                if (generation != downloadGeneration) return@launch
                (shareFileManager ?: defaultShareFileManager).deleteOwnedFile(payload.file)
                _ui.update {
                    it.copy(downloadState = FileDownloadState.Complete(payload.displayName))
                }
            } catch (cancelled: CancellationException) {
                (shareFileManager ?: defaultShareFileManager).deleteOwnedFile(payload.file)
                if (generation == downloadGeneration) {
                    _ui.update { it.copy(downloadState = FileDownloadState.Idle) }
                }
                throw cancelled
            } catch (error: Throwable) {
                (shareFileManager ?: defaultShareFileManager).deleteOwnedFile(payload.file)
                if (generation == downloadGeneration) {
                    _ui.update {
                        it.copy(
                            downloadState = FileDownloadState.Failed(
                                message = error.message ?: appString(R.string.files_error_save_download),
                                displayName = payload.displayName,
                                mimeType = payload.mimeType,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Alias for callers that expose a retry action after a failed SAF save. */
    fun retrySaveDownload(uri: Uri, resolver: ContentResolver) = saveDownload(uri, resolver)

    fun cancelDownload() {
        downloadGeneration += 1
        downloadJob?.cancel()
        saveJob?.cancel()
        deleteOwnedDownload()
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
            suspendResult {
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
            suspendResult { api.deleteManagedFile(entry.path) }.fold(
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
            parseManagedPreviewDataUrl(api.getMediaDataUrl(path).dataUrl).bytes
        } else {
            parseManagedPreviewDataUrl(api.readManagedFile(path).dataUrl).bytes
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

    private fun isCurrentDirectoryRequest(generation: Long, requestedPath: String?): Boolean =
        generation == directoryGeneration && requestedPath == requestedDirectoryPath

    private fun currentDownloadPayload(): DownloadPayload? = when (val state = _ui.value.downloadState) {
        is FileDownloadState.Ready -> DownloadPayload(state.file, state.displayName, state.mimeType)
        is FileDownloadState.Failed -> state.file?.let {
            DownloadPayload(
                file = it,
                displayName = state.displayName,
                mimeType = state.mimeType,
            )
        }
        else -> null
    }

    private fun deleteOwnedDownload() {
        val manager = shareFileManager ?: defaultShareFileManager
        manager.deleteOwnedFile(currentDownloadPayload()?.file)
        manager.deleteOwnedFile((_ui.value.downloadState as? FileDownloadState.Saving)?.file)
        manager.deleteOwnedFile(_ui.value.sharePayload?.file)
    }

    private fun appString(@StringRes resourceId: Int): String = TalariaApp.instance.getString(resourceId)

    override fun onCleared() {
        downloadGeneration += 1
        directoryJob?.cancel()
        downloadJob?.cancel()
        saveJob?.cancel()
        deleteOwnedDownload()
            }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = FilesViewModel() as T
        }
    }
}

private data class DownloadPayload(
    val file: File,
    val displayName: String,
    val mimeType: String,
)

private const val MAX_MANAGED_PREVIEW_DATA_URL_CHARS =
    ((MAX_MANAGED_PREVIEW_BYTES + 2L) / 3L) * 4L + 4_096L

private fun parseManagedPreviewDataUrl(dataUrl: String, declaredBytes: Long = 0L): ParsedDataUrl {
    require(dataUrl.length.toLong() <= MAX_MANAGED_PREVIEW_DATA_URL_CHARS) {
        "Managed file preview is too large"
    }
    require(declaredBytes <= 0L || declaredBytes <= MAX_MANAGED_PREVIEW_BYTES) {
        "Managed file preview is too large"
    }
    return parseDataUrl(dataUrl).also { parsed ->
        require(parsed.bytes.size.toLong() <= MAX_MANAGED_PREVIEW_BYTES) {
            "Managed file preview is too large"
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
