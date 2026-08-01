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
    val editing: Boolean = false,
    val editDraft: String = "",
    val saving: Boolean = false,
    val previewError: String? = null,
)

/**
 * Host filesystem browser (Desktop Files pane parity, roadmap 15.1). Lazily lists
 * `/api/fs/list` per directory and reads text files via `/api/fs/read-text`.
 */
class FilesViewModel(
    private val repo: HermesRepository = TalariaApp.instance.container.hermesRepository,
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

    fun refresh() = open(_ui.value.path)

    /** Navigate to the parent directory, stopping at the filesystem root. */
    fun up() {
        val current = _ui.value.path.trimEnd('/')
        val parent = current.substringBeforeLast('/', "")
        open(parent.ifBlank { "/" })
    }

    fun openFile(entry: FsEntry) {
        _ui.update { it.copy(previewLoading = true, preview = null) }
        viewModelScope.launch {
            repo.fsReadText(entry.path).fold(
                onSuccess = { file ->
                    _ui.update {
                        it.copy(
                            previewLoading = false,
                            preview = file,
                            editing = false,
                            editDraft = file.text,
                            previewError = null,
                        )
                    }
                },
                onFailure = { e ->
                    _ui.update {
                        it.copy(
                            previewLoading = false,
                            preview = FsTextFile(path = entry.path, text = "Could not read file: ${e.message}"),
                        )
                    }
                },
            )
        }
    }

    fun beginEdit() = _ui.update { state ->
        val file = state.preview
        if (file == null || file.binary || file.truncated) state
        else state.copy(editing = true, editDraft = file.text, previewError = null)
    }

    fun updateDraft(value: String) = _ui.update { it.copy(editDraft = value) }

    fun cancelEdit() = _ui.update { it.copy(editing = false, editDraft = it.preview?.text.orEmpty()) }

    fun saveEdit() {
        val state = _ui.value
        val file = state.preview ?: return
        _ui.update { it.copy(saving = true, previewError = null) }
        viewModelScope.launch {
            repo.fsWriteText(file.path, state.editDraft, file.text).fold(
                onSuccess = { saved ->
                    _ui.update {
                        it.copy(
                            preview = saved,
                            editDraft = saved.text,
                            editing = false,
                            saving = false,
                            previewError = null,
                        )
                    }
                },
                onFailure = { error ->
                    _ui.update { it.copy(saving = false, previewError = error.message ?: "Save failed") }
                },
            )
        }
    }

    fun closePreview() = _ui.update {
        it.copy(preview = null, editing = false, editDraft = "", previewError = null)
    }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = FilesViewModel() as T
        }
    }
}
