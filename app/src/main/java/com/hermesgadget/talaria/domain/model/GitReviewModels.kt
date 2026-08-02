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

package com.hermesgadget.talaria.domain.model

import kotlinx.serialization.Serializable

/** Working-tree status returned by `/api/git/status`. */
@Serializable
data class GitStatus(
    val branch: String? = null,
    val defaultBranch: String? = null,
    val detached: Boolean = false,
    val ahead: Int = 0,
    val behind: Int = 0,
    val staged: Int = 0,
    val unstaged: Int = 0,
    val untracked: Int = 0,
    val conflicted: Int = 0,
    val changed: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val files: List<GitChangedFile> = emptyList(),
)

/** One changed path from the git status/review list endpoints. */
@Serializable
data class GitChangedFile(
    val path: String = "",
    val added: Int = 0,
    val removed: Int = 0,
    val status: String = "M",
    val staged: Boolean = false,
)

@Serializable
data class GitBranchesResponse(val branches: List<GitBranch> = emptyList())

/** A local branch and, when checked out, its worktree path. */
@Serializable
data class GitBranch(
    val name: String = "",
    val checkedOut: Boolean = false,
    val isDefault: Boolean = false,
    val worktreePath: String? = null,
)

@Serializable
data class GitBaseBranchesResponse(val branches: List<GitBaseBranch> = emptyList())

/** Branch refs that can be selected as a review base. */
@Serializable
data class GitBaseBranch(
    val name: String = "",
    val isRemote: Boolean = false,
    val isDefault: Boolean = false,
)

@Serializable
data class GitWorktreesResponse(val worktrees: List<GitWorktree> = emptyList())

@Serializable
data class GitWorktree(
    val path: String = "",
    val branch: String? = null,
    val isMain: Boolean = false,
    val detached: Boolean = false,
    val locked: Boolean = false,
)

@Serializable
data class GitReviewListResponse(
    val files: List<GitChangedFile> = emptyList(),
    val base: String? = null,
)

@Serializable
data class GitDiffResponse(val diff: String = "")

@Serializable
data class GitRevParseResponse(val sha: String? = null)

@Serializable
data class GitBranchSwitchRequest(
    val path: String,
    val branch: String,
)

@Serializable
data class GitBranchSwitchResponse(
    val branch: String? = null,
    val ok: Boolean? = null,
    val message: String? = null,
)

@Serializable
data class GitPathRequest(val path: String)

@Serializable
data class GitFileRequest(
    val path: String,
    val file: String? = null,
)

@Serializable
data class GitCommitRequest(
    val path: String,
    val message: String,
    val push: Boolean = false,
)

@Serializable
data class GitWorktreeAddRequest(
    val path: String,
    val name: String? = null,
    val branch: String? = null,
    val base: String? = null,
    val existingBranch: String? = null,
)

@Serializable
data class GitWorktreeRemoveRequest(
    val path: String,
    val worktreePath: String,
    val force: Boolean = false,
)

/**
 * Normalized branch information used by the Review screen. The status response
 * is authoritative for the current branch; branch-list fallbacks keep the UI
 * useful when an older dashboard omits that field.
 */
data class GitBranchState(
    val currentBranch: GitBranch?,
    val defaultBase: GitBaseBranch?,
    val branches: List<GitBranch>,
    val baseBranches: List<GitBaseBranch>,
) {
    companion object {
        fun from(
            status: GitStatus,
            branchResponse: GitBranchesResponse,
            baseResponse: GitBaseBranchesResponse,
        ): GitBranchState {
            val branches = branchResponse.branches
            val current = when {
                status.detached -> null
                status.branch != null -> branches.firstOrNull { it.name == status.branch }
                else -> branches.firstOrNull { it.checkedOut }
            }
            val defaultBase = baseResponse.branches.firstOrNull { it.isDefault }
                ?: status.defaultBranch?.let { wanted ->
                    baseResponse.branches.firstOrNull { it.name == wanted }
                }
            return GitBranchState(
                currentBranch = current,
                defaultBase = defaultBase,
                branches = branches,
                baseBranches = baseResponse.branches,
            )
        }
    }
}
