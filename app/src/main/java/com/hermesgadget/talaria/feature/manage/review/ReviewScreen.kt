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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.domain.model.GitBranch
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold

@Composable
fun ReviewScreen(
    onOpenFile: (String) -> Unit,
    vm: ReviewViewModel = viewModel(factory = ReviewViewModel.factory()),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var branchSheetOpen by remember { mutableStateOf(false) }
    val ready = ui as? ReviewUiState.Ready
    val context = LocalContext.current

    ready?.let { state ->
        state.pendingBranch?.let { branch ->
            BranchSwitchConfirmation(
                current = state.status.branch ?: state.branchState.currentBranch?.name,
                target = branch,
                busy = state.switching,
                onConfirm = vm::confirmBranchSwitch,
                onDismiss = vm::cancelBranchSwitch,
            )
        }
    }

    ready?.selected?.let { detail ->
        ReviewDetailSheet(
            detail = detail,
            onDismiss = vm::closeFile,
            onCopyPath = { copyPath(context, detail.file.workingTreePath) },
            onOpenFile = {
                vm.closeFile()
                onOpenFile(detail.file.workingTreePath)
            },
        )
    }

    when (ui) {
        ReviewUiState.Loading -> ScreenScaffold(
            title = "Review",
            subtitle = "loading",
            showProfileSwitcher = true,
        ) { LoadingBox() }

        is ReviewUiState.Failed -> {
            val failure = ui as ReviewUiState.Failed
            var repoPath by remember { mutableStateOf("") }
            ScreenScaffold(
                title = "Review",
                showProfileSwitcher = true,
                actions = { TextButton(onClick = vm::refresh) { Text("Refresh") } },
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(failure.message, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = repoPath,
                        onValueChange = { repoPath = it },
                        label = { Text("Repository path") },
                        placeholder = { Text("/home/ben/Talaria") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { vm.setRepoPath(repoPath) },
                        enabled = repoPath.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open repository")
                    }
                }
            }
        }

        is ReviewUiState.Ready -> {
            val state = ui as ReviewUiState.Ready
            if (branchSheetOpen) {
                BranchSheet(
                    state = state,
                    onDismiss = { branchSheetOpen = false },
                    onSwitch = { branch ->
                        branchSheetOpen = false
                        vm.requestBranchSwitch(branch)
                    },
                )
            }
            ScreenScaffold(
                title = "Review",
                subtitle = state.status.branch ?: if (state.status.detached) "detached" else "workspace",
                showProfileSwitcher = true,
                actions = {
                    TextButton(onClick = vm::refresh, enabled = !state.switching) {
                        Text("Refresh")
                    }
                },
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    WorkspaceCard(
                        state = state,
                        onBranches = { branchSheetOpen = true },
                    )
                    state.error?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "Changed files",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (state.files.isEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Text(
                                "Working tree clean",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = true),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(state.files, key = { it.change.path }) { file ->
                                ChangedFileRow(file, onClick = { vm.openFile(file) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceCard(
    state: ReviewUiState.Ready,
    onBranches: () -> Unit,
) {
    val current = state.status.branch
        ?: state.branchState.currentBranch?.name
        ?: if (state.status.detached) "Detached HEAD" else "Unknown"
    val base = state.branchState.defaultBase?.name ?: "none"
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Workspace", style = MaterialTheme.typography.labelLarge)
            Text(
                state.repoPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Branch", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Text(current, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onBranches, enabled = !state.switching) {
                    Text("Branches")
                }
            }
            Text(
                "Base: $base  ·  ${state.status.changed} changed  ·  +${state.status.added} / -${state.status.removed}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChangedFileRow(file: ReviewFile, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.change.path,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    file.change.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "+${file.change.added}  -${file.change.removed}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BranchSheet(
    state: ReviewUiState.Ready,
    onDismiss: () -> Unit,
    onSwitch: (GitBranch) -> Unit,
) {
    val currentName = state.status.branch ?: state.branchState.currentBranch?.name
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp),
        ) {
            Text(
                "Branches",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            LazyColumn {
                items(state.branchState.branches, key = { it.name }) { branch ->
                    val current = !state.status.detached && branch.name == currentName
                    val elsewhere = branch.checkedOut && !current
                    ListItem(
                        headlineContent = { Text(branch.name) },
                        supportingContent = {
                            Text(
                                when {
                                    current -> "Current workspace branch"
                                    elsewhere -> "Checked out in another worktree"
                                    branch.isDefault -> "Default branch"
                                    else -> "Available"
                                },
                            )
                        },
                        trailingContent = {
                            when {
                                current -> Text("Current", color = MaterialTheme.colorScheme.primary)
                                elsewhere -> Text("Busy", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else -> TextButton(onClick = { onSwitch(branch) }) { Text("Switch") }
                            }
                        },
                    )
                }
                if (state.branchState.baseBranches.isNotEmpty()) {
                    item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
                    item {
                        Text(
                            "Review bases",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(
                        state.branchState.baseBranches,
                        key = { "base-${it.name}" },
                    ) { base ->
                        ListItem(
                            headlineContent = { Text(base.name) },
                            supportingContent = {
                                Text(if (base.isRemote) "Remote tracking branch" else "Local branch")
                            },
                            trailingContent = {
                                if (base.isDefault) {
                                    Text("Default", color = MaterialTheme.colorScheme.primary)
                                }
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun BranchSwitchConfirmation(
    current: String?,
    target: GitBranch,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Switch branch?") },
        text = {
            Text(
                buildString {
                    append("Switch this workspace from ")
                    append(current ?: "detached HEAD")
                    append(" to ")
                    append(target.name)
                    append("? Uncommitted changes may prevent the switch.")
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Switch")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReviewDetailSheet(
    detail: ReviewFileDetail,
    onDismiss: () -> Unit,
    onCopyPath: () -> Unit,
    onOpenFile: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp),
        ) {
            Text(
                detail.file.change.path,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                "${detail.file.change.status}  ·  +${detail.file.change.added} / -${detail.file.change.removed}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCopyPath) { Text("Copy path") }
                TextButton(onClick = onOpenFile) { Text("Open in Files") }
            }
            when {
                detail.loading -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("Reading working tree…", modifier = Modifier.padding(top = 12.dp))
                }

                detail.lines.isNotEmpty() -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    items(detail.lines) { line -> DiffRow(line) }
                }

                detail.current?.binary == true -> Text(
                    "Binary file — no text preview.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )

                else -> Text(
                    "No textual diff returned for this path.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            detail.error?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DiffRow(line: DiffLine) {
    val (prefix, color) = when (line.kind) {
        DiffLineKind.ADDED -> "+" to MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        DiffLineKind.REMOVED -> "-" to MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        DiffLineKind.CONTEXT -> " " to Color.Transparent
        DiffLineKind.HEADER -> "" to MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Text(
            "$prefix${if (prefix.isNotEmpty()) " " else ""}${line.text}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            softWrap = false,
        )
    }
}

private fun copyPath(context: Context, path: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("File path", path))
}
