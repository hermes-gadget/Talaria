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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.domain.model.FsDataUrl
import com.hermesgadget.talaria.domain.model.FsEntry
import com.hermesgadget.talaria.domain.model.FsTextFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FilesUiState(
    val cwd: String = "",
    val branch: String = "",
    val path: String = "",
    val entries: List<FsEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val preview: FsTextFile? = null,
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
)

/**
 * Host filesystem browser (Desktop Files pane parity, roadmap 15.1). Lazily lists
 * `/api/fs/list`, reads text metadata via `/api/fs/read-text`, and loads media through
 * `/api/fs/read-data-url`.
 */
class FilesViewModel(
    private val repo: HermesRepository = TalariaApp.instance.container.hermesRepository,
    private val dataUrlReader: suspend (String) -> FsDataUrl = { path ->
        TalariaApp.instance.container.clientFactory.api().fsReadDataUrl(path)
    },
) : ViewModel() {
    private val _ui = MutableStateFlow(FilesUiState())
    val ui: StateFlow<FilesUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repo.fsDefaultCwd().fold(
                onSuccess = { cwd ->
                    _ui.update { it.copy(cwd = cwd.cwd, branch = cwd.branch) }
                    open(cwd.cwd)
                },
                onFailure = { e -> _ui.update { it.copy(loading = false, error = e.message) } },
            )
        }
    }

    /** List a directory. Blank path falls back to the default cwd. */
    fun open(path: String) {
        val target = path.ifBlank { _ui.value.cwd }
        _ui.update { it.copy(loading = true, error = null, path = target) }
        viewModelScope.launch {
            repo.fsList(target).fold(
                onSuccess = { entries -> _ui.update { it.copy(loading = false, entries = entries) } },
                onFailure = { e -> _ui.update { it.copy(loading = false, error = e.message) } },
            )
        }
    }

    fun refresh() {
        repo.clearCache()
        open(_ui.value.path)
    }

    /** Refresh the current directory each time the Files destination resumes. */
    fun refreshOnResume() {
        if (_ui.value.path.isNotBlank()) refresh()
    }

    /** Navigate to the parent directory, stopping at the filesystem root. */
    fun up() {
        val current = _ui.value.path.trimEnd('/')
        val parent = current.substringBeforeLast('/', "")
        open(parent.ifBlank { "/" })
    }

    fun openFile(entry: FsEntry) {
        _ui.update {
            it.copy(
                preview = FsTextFile(path = entry.path),
                previewLoading = true,
                previewType = previewTypeFor(entry.name),
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
        viewModelScope.launch {
            val result = repo.fsReadText(entry.path)
            result.getOrNull()?.let { file ->
                loadPreview(entry, file)
            } ?: run {
                val extensionType = previewTypeFor(entry.name)
                if (extensionType == FilePreviewType.IMAGE) {
                    loadImagePreview(
                        entry,
                        FsTextFile(path = entry.path, binary = true),
                    )
                } else {
                    updateCurrentPreview(entry.path) {
                        it.copy(
                            previewLoading = false,
                            previewType = FilePreviewType.BINARY,
                            preview = FsTextFile(path = entry.path, binary = true),
                            previewMimeType = "application/octet-stream",
                            previewError = result.exceptionOrNull()?.message ?: "Could not read file",
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadPreview(entry: FsEntry, file: FsTextFile) {
        val normalized = file.copy(path = file.path.ifBlank { entry.path })
        val type = previewTypeFor(entry.name, normalized.mimeType, normalized.binary)
        if (type == FilePreviewType.IMAGE) {
            loadImagePreview(entry, normalized)
            return
        }

        updateCurrentPreview(entry.path) {
            it.copy(
                previewLoading = false,
                preview = normalized,
                previewType = type,
                previewBytes = null,
                previewMimeType = effectiveMimeType(entry.name, normalized, type),
                editing = false,
                editDraft = normalized.text,
                previewError = null,
                shareError = null,
            )
        }
    }

    private suspend fun loadImagePreview(entry: FsEntry, file: FsTextFile) {
        val normalized = file.copy(
            path = file.path.ifBlank { entry.path },
            binary = true,
        )
        runCatching {
            val response = dataUrlReader(entry.path)
            val parsed = parseDataUrl(response.dataUrl)
            response to parsed
        }.fold(
            onSuccess = { (response, parsed) ->
                val mimeType = response.mimeType
                    ?.takeIf { it.isNotBlank() }
                    ?: parsed.mimeType
                    ?: normalized.mimeType
                    ?: mimeTypeFor(entry.name)
                val byteSize = normalized.byteSize.takeIf { it > 0 }
                    ?: response.byteSize.takeIf { it > 0 }
                    ?: parsed.bytes.size.toLong()
                updateCurrentPreview(entry.path) {
                    it.copy(
                        previewLoading = false,
                        preview = normalized.copy(
                            byteSize = byteSize,
                            mimeType = mimeType,
                        ),
                        previewType = FilePreviewType.IMAGE,
                        previewBytes = parsed.bytes,
                        previewMimeType = mimeType,
                        editing = false,
                        editDraft = "",
                        previewError = null,
                        shareError = null,
                    )
                }
            },
            onFailure = { error ->
                updateCurrentPreview(entry.path) {
                    it.copy(
                        previewLoading = false,
                        preview = normalized,
                        previewType = FilePreviewType.IMAGE,
                        previewBytes = null,
                        previewMimeType = effectiveMimeType(entry.name, normalized, FilePreviewType.IMAGE),
                        previewError = error.message ?: "Could not load image preview",
                    )
                }
            },
        )
    }

    fun beginEdit() = _ui.update { state ->
        val file = state.preview
        if (file == null || file.binary || file.truncated) state
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

    /** Requests confirmation before overwriting the remote text file. */
    fun saveEdit() {
        val state = _ui.value
        val file = state.preview ?: return
        if (!state.editing || file.binary || file.truncated || state.saving) return
        _ui.update { it.copy(confirmSave = true) }
    }

    fun cancelSave() = _ui.update { it.copy(confirmSave = false) }

    fun confirmSave() {
        val state = _ui.value
        val file = state.preview ?: return
        if (!state.confirmSave || !state.editing || file.binary || file.truncated || state.saving) return
        val draft = state.editDraft
        val expectedOriginal = file.text
        _ui.update { it.copy(confirmSave = false, saving = true, previewError = null) }
        viewModelScope.launch {
            repo.fsWriteText(file.path, draft, expectedOriginal).fold(
                onSuccess = { saved ->
                    val normalized = saved.copy(path = saved.path.ifBlank { file.path })
                    updateCurrentPreview(file.path) {
                        it.copy(
                            preview = normalized,
                            previewType = FilePreviewType.TEXT,
                            previewBytes = null,
                            previewMimeType = effectiveMimeType(
                                file.path,
                                normalized,
                                FilePreviewType.TEXT,
                            ),
                            editDraft = normalized.text,
                            editing = false,
                            saving = false,
                            previewError = null,
                            shareError = null,
                        )
                    }
                },
                onFailure = { error ->
                    updateCurrentPreview(file.path) {
                        it.copy(
                            saving = false,
                            previewError = error.message ?: "Save failed",
                        )
                    }
                },
            )
        }
    }

    fun sharePreview() {
        val state = _ui.value
        val file = state.preview ?: return
        if (state.previewLoading || state.shareLoading) return
        val type = state.previewType ?: previewTypeFor(file.path, file.mimeType, file.binary)
        _ui.update { it.copy(shareLoading = true, sharePayload = null, shareError = null) }
        viewModelScope.launch {
            runCatching {
                when (type) {
                    FilePreviewType.TEXT -> FileSharePayload(
                        path = file.path,
                        mimeType = effectiveMimeType(file.path, file, type),
                        bytes = file.text.toByteArray(Charsets.UTF_8),
                    )

                    FilePreviewType.IMAGE,
                    FilePreviewType.BINARY,
                    -> {
                        val parsed = state.previewBytes?.let {
                            ParsedDataUrl(state.previewMimeType, it)
                        } ?: run {
                            val response = dataUrlReader(file.path)
                            val decoded = parseDataUrl(response.dataUrl)
                            ParsedDataUrl(
                                mimeType = response.mimeType
                                    ?: decoded.mimeType
                                    ?: state.previewMimeType,
                                bytes = decoded.bytes,
                            )
                        }
                        FileSharePayload(
                            path = file.path,
                            mimeType = parsed.mimeType
                                ?: effectiveMimeType(file.path, file, type),
                            bytes = parsed.bytes,
                        )
                    }
                }
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
                            shareError = error.message ?: "Could not prepare file for sharing",
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

    private fun effectiveMimeType(
        fileName: String,
        file: FsTextFile,
        type: FilePreviewType,
    ): String {
        val serverMime = file.mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return when {
            type == FilePreviewType.TEXT && (serverMime == null || serverMime == "application/octet-stream") -> {
                "text/plain"
            }
            type == FilePreviewType.IMAGE && (serverMime == null || serverMime == "application/octet-stream") -> {
                mimeTypeFor(fileName)
            }
            serverMime != null -> serverMime
            else -> mimeTypeFor(fileName)
        }
    }

    private fun updateCurrentPreview(
        path: String,
        transform: (FilesUiState) -> FilesUiState,
    ) = _ui.update { state ->
        if (state.preview?.path == path) transform(state) else state
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

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = FilesViewModel() as T
        }
    }
}
