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

package com.hermesgadget.talaria.feature.manage.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.network.ConnectionScope
import com.hermesgadget.talaria.core.network.ConnectionScopeObserver
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.core.util.suspendResult
import com.hermesgadget.talaria.domain.model.FsTextFile
import com.hermesgadget.talaria.domain.model.GitBranch
import com.hermesgadget.talaria.domain.model.GitBranchState
import com.hermesgadget.talaria.domain.model.GitBranchSwitchRequest
import com.hermesgadget.talaria.domain.model.GitChangedFile
import com.hermesgadget.talaria.domain.model.GitStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewFile(
    val change: GitChangedFile,
    /** Absolute path passed to `/api/fs/read-text`. */
    val workingTreePath: String,
)

data class ReviewFileDetail(
    val file: ReviewFile,
    val current: FsTextFile? = null,
    val lines: List<DiffLine> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

sealed interface ReviewUiState {
    data object Loading : ReviewUiState

    data class Ready(
        val repoPath: String,
        val status: GitStatus,
        val branchState: GitBranchState,
        val files: List<ReviewFile>,
        val selected: ReviewFileDetail? = null,
        val pendingBranch: GitBranch? = null,
        val switching: Boolean = false,
        val error: String? = null,
    ) : ReviewUiState

    data class Failed(val message: String) : ReviewUiState
}

/** Loads the small-screen git review surface from the authenticated Hermes API. */
class ReviewViewModel(
    api: HermesApi? = null,
    private val apiProvider: (ConnectionScope?) -> HermesApi = { scope ->
        scope?.snapshot?.let { TalariaApp.instance.container.clientFactory.api(it) }
            ?: TalariaApp.instance.container.clientFactory.api()
    },
    private val scopeFlow: StateFlow<ConnectionScope?>? = null,
) : ViewModel() {
    private val fixedApi = api
    private var boundApi: HermesApi = api ?: apiProvider(scopeFlow?.value)
    private var boundScope: ConnectionScope? = scopeFlow?.value
    private var scopeObserver: ConnectionScopeObserver? = null
    private var refreshJob: Job? = null
    private var detailJob: Job? = null
    private var switchJob: Job? = null
    private val _ui = MutableStateFlow<ReviewUiState>(ReviewUiState.Loading)
    val ui: StateFlow<ReviewUiState> = _ui.asStateFlow()

    /** User-supplied repo path override (the default workspace cwd may not be a git repo). */
    private var repoOverride: String? = null

    init {
        scopeObserver = scopeFlow?.let { flow ->
            ConnectionScopeObserver(flow, viewModelScope) { next -> rebind(next) }
        }
        if (scopeFlow == null || boundScope != null) refresh()
    }

    private fun rebind(next: ConnectionScope?) {
        boundScope = next
        boundApi = fixedApi ?: apiProvider(next)
        repoOverride = null
        refreshJob?.cancel()
        detailJob?.cancel()
        switchJob?.cancel()
        _ui.value = ReviewUiState.Loading
        if (next != null) refresh()
    }

    private fun isCurrentScope(expected: ConnectionScope?): Boolean =
        scopeObserver?.isCurrent(expected) != false

    fun setRepoPath(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        repoOverride = trimmed
        refresh()
    }

    fun refresh() {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val requestApi = boundApi
        refreshJob?.cancel()
        detailJob?.cancel()
        _ui.value = ReviewUiState.Loading
        refreshJob = viewModelScope.launch {
            suspendResult { loadReadyState(requestApi) }.fold(
                onSuccess = { if (isCurrentScope(expectedScope)) _ui.value = it },
                onFailure = { error ->
                    if (isCurrentScope(expectedScope)) {
                        _ui.value = ReviewUiState.Failed(
                            error.message ?: "Could not load git review",
                        )
                    }
                },
            )
        }
    }

    fun openFile(file: ReviewFile) {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val requestApi = boundApi
        val state = _ui.value as? ReviewUiState.Ready ?: return
        _ui.value = state.copy(
            selected = ReviewFileDetail(file = file),
            error = null,
        )
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            val current = suspendResult { requestApi.fsReadText(file.workingTreePath) }
            val diff = suspendResult {
                requestApi.gitFileDiff(state.repoPath, file.change.path).diff
            }
            if (!isCurrentScope(expectedScope)) return@launch
            val detail = ReviewFileDetail(
                file = file,
                current = current.getOrNull(),
                lines = diff.getOrNull()?.let(::renderUnifiedDiff).orEmpty(),
                loading = false,
                error = diff.exceptionOrNull()?.message
                    ?: current.exceptionOrNull()?.message?.let {
                        "Working-tree text preview unavailable: $it"
                    },
            )
            _ui.update { currentState ->
                if (currentState is ReviewUiState.Ready &&
                    isCurrentScope(expectedScope) &&
                    currentState.selected?.file?.change?.path == file.change.path
                ) {
                    currentState.copy(selected = detail)
                } else {
                    currentState
                }
            }
        }
    }

    fun closeFile() {
        _ui.update { state ->
            if (state is ReviewUiState.Ready) state.copy(selected = null) else state
        }
    }

    fun requestBranchSwitch(branch: GitBranch) {
        _ui.update { state ->
            if (state !is ReviewUiState.Ready || state.switching) {
                state
            } else {
                state.copy(pendingBranch = branch, error = null)
            }
        }
    }

    fun cancelBranchSwitch() {
        _ui.update { state ->
            if (state is ReviewUiState.Ready) state.copy(pendingBranch = null) else state
        }
    }

    fun confirmBranchSwitch() {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val requestApi = boundApi
        val state = _ui.value as? ReviewUiState.Ready ?: return
        val branch = state.pendingBranch ?: return
        _ui.value = state.copy(pendingBranch = null, switching = true, error = null)
        switchJob?.cancel()
        switchJob = viewModelScope.launch {
            suspendResult {
                requestApi.gitBranchSwitch(
                    GitBranchSwitchRequest(
                        path = state.repoPath,
                        branch = branch.name,
                    ),
                )
            }.fold(
                onSuccess = { if (isCurrentScope(expectedScope)) refresh() },
                onFailure = { error ->
                    if (isCurrentScope(expectedScope)) {
                        _ui.update { currentState ->
                            if (currentState is ReviewUiState.Ready) {
                                currentState.copy(
                                    switching = false,
                                    error = error.message ?: "Could not switch branch",
                                )
                            } else {
                                currentState
                            }
                        }
                    }
                },
            )
        }
    }

    private suspend fun loadReadyState(requestApi: HermesApi): ReviewUiState.Ready {
        val repoPath = repoOverride ?: run {
            val cwd = requestApi.fsDefaultCwd().cwd.trim().ifBlank {
                error("Hermes did not provide a workspace path")
            }
            // Resolve the actual git repo root (the default cwd may not be a repo).
            val gitRoot = (requestApi.fsGitRoot(cwd) as? JsonObject)?.get("root")
                ?.let { r -> if (r is JsonNull) null else r.jsonPrimitive.content }
            gitRoot ?: error(
                "No git repository found at $cwd. Enter a repo path (e.g. ~/my-project).",
            )
        }
        val loaded = coroutineScope {
            val status = async { requestApi.gitStatus(repoPath) }
            val branches = async { requestApi.gitBranches(repoPath) }
            val baseBranches = async { requestApi.gitBaseBranches(repoPath) }
            val review = async { requestApi.gitReviewList(repoPath) }
            LoadedReview(
                status = status.await(),
                branches = branches.await(),
                baseBranches = baseBranches.await(),
                reviewFiles = review.await().files,
            )
        }
        val files = (loaded.reviewFiles.ifEmpty { loaded.status.files })
            .filter { it.path.isNotBlank() }
            .map { change ->
                ReviewFile(
                    change = change,
                    workingTreePath = absolutePath(repoPath, change.path),
                )
            }
        return ReviewUiState.Ready(
            repoPath = repoPath,
            status = loaded.status,
            branchState = GitBranchState.from(
                status = loaded.status,
                branchResponse = loaded.branches,
                baseResponse = loaded.baseBranches,
            ),
            files = files,
        )
    }

    private data class LoadedReview(
        val status: GitStatus,
        val branches: com.hermesgadget.talaria.domain.model.GitBranchesResponse,
        val baseBranches: com.hermesgadget.talaria.domain.model.GitBaseBranchesResponse,
        val reviewFiles: List<GitChangedFile>,
    )

    companion object {
        fun absolutePath(repoPath: String, relativePath: String): String =
            if (relativePath.startsWith('/')) {
                relativePath
            } else {
                "${repoPath.trimEnd('/')}/$relativePath"
            }

        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = TalariaApp.instance.container
                return ReviewViewModel(
                    apiProvider = { scope ->
                        scope?.snapshot?.let { container.clientFactory.api(it) }
                            ?: container.clientFactory.api()
                    },
                    scopeFlow = container.connectionStore.scope,
                ) as T
            }
        }
    }
}
