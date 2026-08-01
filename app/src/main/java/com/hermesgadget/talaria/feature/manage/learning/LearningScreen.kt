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
package com.hermesgadget.talaria.feature.manage.learning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold

/**
 * Learning graph / Starmap (roadmap 15.4). A structured view of what Hermes has
 * learned: rollup stats, category clusters, and the skill/memory node list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningScreen(
    vm: LearningViewModel = viewModel(factory = LearningViewModel.factory()),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val graph = ui.graph
    var reveal by remember { mutableFloatStateOf(1f) }
    val timeline = remember(graph?.nodes) { graph?.let { buildLearningTimeline(it.nodes) } }
    val visibleNodes = remember(graph?.nodes, reveal) {
        graph?.let { visibleLearningNodes(it.nodes, reveal) }.orEmpty()
    }

    LaunchedEffect(graph?.nodes) {
        reveal = 1f
    }

    if (ui.selected != null && !ui.confirmDelete) {
        ModalBottomSheet(onDismissRequest = vm::close) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(ui.detail?.label ?: ui.selected?.label.orEmpty(), style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        ui.detail?.kind?.takeIf { it.isNotBlank() } ?: ui.selected?.kind,
                        ui.selected?.category,
                        formatLearningTimestamp(ui.selected?.timestamp),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ui.detail == null && ui.busy) {
                    LoadingBox()
                } else {
                    OutlinedTextField(
                        value = ui.draft,
                        onValueChange = vm::updateDraft,
                        label = { Text("Description") },
                        minLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = vm::requestDelete, enabled = !ui.busy) {
                            Text(if (ui.selected?.kind == "skill") "Archive" else "Delete")
                        }
                        Button(onClick = vm::save, enabled = !ui.busy && ui.detail != null) {
                            Text(if (ui.busy) "Saving…" else "Save")
                        }
                    }
                }
            }
        }
    }

    if (ui.confirmDelete) {
        AlertDialog(
            onDismissRequest = vm::cancelDelete,
            title = { Text(if (ui.selected?.kind == "skill") "Archive skill?" else "Delete memory?") },
            text = {
                Text(
                    if (ui.selected?.kind == "skill") {
                        "Hermes will archive this learned skill so it can be restored later."
                    } else {
                        "This permanently removes the selected learned memory."
                    },
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmDelete) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = vm::cancelDelete) { Text("Cancel") } },
        )
    }

    ScreenScaffold(
        title = "Learning",
        subtitle = "what Hermes has learned",
        showProfileSwitcher = true,
        actions = { TextButton(onClick = { vm.refresh() }) { Text("Refresh") } },
    ) {
        when {
            ui.loading && graph == null -> LoadingBox()
            ui.error != null && graph == null -> ErrorBox(ui.error!!) { vm.refresh() }
            graph == null -> Text("No learning data", modifier = Modifier.padding(16.dp))
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { StatsCard(graph) }
                item {
                    LearningStarmapCard(
                        graph = graph,
                        visibleNodes = visibleNodes,
                        timeline = timeline!!,
                        reveal = reveal,
                        onRevealChange = { reveal = it },
                        onNodeClick = vm::open,
                    )
                }
                if (graph.clusters.isNotEmpty()) {
                    item {
                        Text(
                            "Clusters",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            graph.clusters.sortedByDescending { it.count }.forEach { c ->
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("${c.category} · ${c.count}") },
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        if (visibleNodes.size == graph.nodes.size) {
                            "Nodes (${graph.nodes.size})"
                        } else {
                            "Nodes (${visibleNodes.size}/${graph.nodes.size})"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (visibleNodes.isEmpty()) {
                    item {
                        Text(
                            if (graph.nodes.isEmpty()) "No learned skills yet." else "No nodes match this timeline.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(visibleNodes, key = { it.id }) { node -> NodeCard(node) { vm.open(node) } }
            }
        }
    }
}

@Composable
private fun StatsCard(graph: LearningGraphSnapshot) {
    val s = graph.stats
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Overview", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Text("${s.learned_skills} learned skills · ${s.categories} categories", style = MaterialTheme.typography.titleMedium)
            Text(
                "${s.linked_nodes} linked · ${"%.0f".format(s.isolated_pct)}% isolated · ${s.used} used · ${s.agent_created} agent-created",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeCard(node: LearningMapNode, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    node.label.ifBlank { node.id },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                node.state?.let {
                    AssistChip(onClick = {}, enabled = false, label = { Text(it) })
                }
            }
            Text(
                listOfNotNull(
                    node.kind,
                    node.category,
                    "used ${node.useCount}×",
                    node.createdBy?.let { "by $it" },
                    if (node.pinned) "pinned" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
