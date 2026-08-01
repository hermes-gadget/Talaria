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
package com.hermesgadget.talaria.feature.manage.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.domain.model.MemoryProvider
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import com.hermesgadget.talaria.ui.theme.LocalSpacing

@Composable
fun MemoryScreen(vm: MemoryViewModel = viewModel(factory = MemoryViewModel.factory())) {
    val spacing = LocalSpacing.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state = ui.state

    ui.resetTarget?.let { target ->
        AlertDialog(
            onDismissRequest = vm::cancelReset,
            title = { Text("Reset ${if (target == "all") "all memory" else target}") },
            text = { Text("This permanently deletes the selected built-in memory file data on the Hermes host.") },
            confirmButton = {
                TextButton(onClick = vm::confirmReset) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = vm::cancelReset) { Text("Cancel") } },
        )
    }

    ScreenScaffold("Memory", actions = {
        TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
    }) {
        when {
            ui.loading && state == null -> LoadingBox()
            ui.error != null && state == null -> ErrorBox(ui.error!!, onRetry = vm::refresh)
            state == null -> Text("No memory providers.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.itemGap)) {
                item {
                    Text(
                        "Active: ${state.active?.ifBlank { "none" } ?: "none"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = spacing.xs),
                    )
                }
                ui.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                items(state.providers, key = { it.name }) { p ->
                    ProviderCard(
                        p = p,
                        active = p.name == state.active,
                        busy = ui.busy,
                        onActivate = { vm.activate(p.name) },
                    )
                }
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(spacing.cardPad),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs),
                        ) {
                            Text("Built-in memory files", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "MEMORY.md · ${formatMemoryBytes(state.builtin_files.memory)}   USER.md · ${formatMemoryBytes(state.builtin_files.user)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                                OutlinedButton(
                                    onClick = { vm.requestReset("memory") },
                                    enabled = !ui.busy && state.builtin_files.memory > 0,
                                ) { Text("Reset memory") }
                                OutlinedButton(
                                    onClick = { vm.requestReset("user") },
                                    enabled = !ui.busy && state.builtin_files.user > 0,
                                ) { Text("Reset user") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    p: MemoryProvider,
    active: Boolean,
    busy: Boolean,
    onActivate: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.cardPad)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(p.name, style = MaterialTheme.typography.titleSmall)
                val (label, container, content) = when {
                    p.available -> Triple(
                        "available",
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    p.configured -> Triple(
                        p.status ?: "configured",
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    else -> Triple(
                        p.status ?: "unavailable",
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = container,
                        disabledLabelColor = content,
                    ),
                )
            }
            p.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
            p.setup?.let { setup ->
                val deps = (setup.pip_dependencies + setup.required_env)
                if (deps.isNotEmpty()) {
                    Text(
                        (if (setup.dependencies_installed) "Installed · " else "Needs: ") + deps.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.xs),
                    )
                }
            }
            if (!active) {
                OutlinedButton(
                    onClick = onActivate,
                    enabled = !busy && p.available && p.configured,
                    modifier = Modifier.padding(top = spacing.xs),
                ) { Text("Activate") }
            }
        }
    }
}

private fun formatMemoryBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    else -> "${bytes / 1024} KB"
}
