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

import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.domain.model.GitBaseBranchesResponse
import com.hermesgadget.talaria.domain.model.GitBranchesResponse
import com.hermesgadget.talaria.domain.model.GitBranchState
import com.hermesgadget.talaria.domain.model.GitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitReviewModelsTest {
    private val json = JsonConfig.json

    @Test
    fun parsesLiveBranchStateResponses() {
        val status = json.decodeFromString(
            GitStatus.serializer(),
            """{
                "branch":"feature/git-review",
                "defaultBranch":"main",
                "detached":false,
                "ahead":0,
                "behind":0,
                "staged":0,
                "unstaged":1,
                "untracked":1,
                "conflicted":0,
                "changed":1,
                "added":3,
                "removed":1,
                "files":[{"path":"notes.md","added":3,"removed":1,"status":"M","staged":false}]
            }""".trimIndent(),
        )
        val branches = json.decodeFromString(
            GitBranchesResponse.serializer(),
            """{"branches":[
                {"name":"feature/git-review","checkedOut":true,"isDefault":false,"worktreePath":"/repo"},
                {"name":"main","checkedOut":false,"isDefault":true,"worktreePath":null}
            ]}""",
        )
        val bases = json.decodeFromString(
            GitBaseBranchesResponse.serializer(),
            """{"branches":[
                {"name":"origin/main","isRemote":true,"isDefault":true},
                {"name":"main","isRemote":false,"isDefault":false}
            ]}""",
        )

        val state = GitBranchState.from(status, branches, bases)

        assertEquals("feature/git-review", state.currentBranch?.name)
        assertEquals("origin/main", state.defaultBase?.name)
        assertEquals(3, status.files.single().added)
        assertEquals(2, state.branches.size)
    }

    @Test
    fun detachedStatusDoesNotInventCurrentBranchFromAnotherWorktree() {
        val status = GitStatus(detached = true, defaultBranch = "main")
        val branches = GitBranchesResponse(
            branches = listOf(
                com.hermesgadget.talaria.domain.model.GitBranch(
                    name = "main",
                    checkedOut = true,
                    isDefault = true,
                ),
            ),
        )
        val bases = GitBaseBranchesResponse()

        val state = GitBranchState.from(status, branches, bases)

        assertNull(state.currentBranch)
        assertEquals("main", state.branches.single().name)
    }
}
