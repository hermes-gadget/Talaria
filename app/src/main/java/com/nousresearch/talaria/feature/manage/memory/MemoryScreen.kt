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
package com.nousresearch.talaria.feature.manage.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nousresearch.talaria.domain.model.MemoryProvider
import com.nousresearch.talaria.domain.model.MemoryState
import com.nousresearch.talaria.feature.manage.SimpleManageViewModel
import com.nousresearch.talaria.ui.components.ErrorBox
import com.nousresearch.talaria.ui.components.LoadingBox
import com.nousresearch.talaria.ui.components.ScreenScaffold
import com.nousresearch.talaria.ui.theme.LocalSpacing

@Composable
fun MemoryScreen() {
    val spacing = LocalSpacing.current
    val vm: SimpleManageViewModel = viewModel(
        factory = SimpleManageViewModel.factory { getMemoryState() },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state = ui.data as? MemoryState

    ScreenScaffold("Memory", actions = {
        TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
    }) {
        when {
            ui.loading && state == null -> LoadingBox()
            ui.error != null && state == null -> ErrorBox(ui.error!!, onRetry = { vm.refresh() })
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
                items(state.providers, key = { it.name }) { p -> ProviderCard(p) }
            }
        }
    }
}

@Composable
private fun ProviderCard(p: MemoryProvider) {
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
        }
    }
}
