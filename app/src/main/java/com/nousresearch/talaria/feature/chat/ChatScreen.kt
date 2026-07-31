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

package com.nousresearch.talaria.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.ToolCallUi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    resumeSessionId: String? = null,
    initialShare: String? = null,
    onOpenSessions: () -> Unit,
    onNeedConnection: () -> Unit,
    vm: ChatViewModel = viewModel(factory = ChatViewModel.factory()),
) {
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    val hasConnection = TalariaApp.instance.container.connectionStore.activeProfile() != null
    val density = LocalDensity.current
    var expandedTool by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(resumeSessionId, hasConnection) {
        if (!hasConnection) onNeedConnection() else vm.connect(resumeSessionId)
    }
    LaunchedEffect(initialShare) {
        if (!initialShare.isNullOrBlank()) vm.updateDraft(initialShare)
    }
    val displayLines = if (ui.transcriptMode == TranscriptMode.READING && ui.readingMessages.isNotEmpty()) {
        ui.readingMessages
    } else {
        ui.lines
    }
    LaunchedEffect(displayLines.size) {
        if (displayLines.isNotEmpty()) listState.animateScrollToItem(displayLines.lastIndex)
    }

    val status = when {
        ui.connecting -> "Connecting…"
        ui.connected -> "Live · ${ui.modelLabel ?: "Hermes"}"
        else -> "Disconnected"
    }

    if (ui.prompt != null) {
        AlertDialog(
            onDismissRequest = vm::dismissPrompt,
            title = { Text(ui.prompt!!.kind.name) },
            text = { Text(ui.prompt!!.message) },
            confirmButton = {
                TextButton(onClick = { vm.respondPrompt(true) }) { Text("Approve") }
            },
            dismissButton = {
                TextButton(onClick = { vm.respondPrompt(false) }) { Text("Deny") }
            },
        )
    }

    if (ui.showSessionRail) {
        ModalBottomSheet(
            onDismissRequest = { vm.toggleSessionRail(false) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                "Sessions",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            TextButton(onClick = { vm.newChat() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("New chat")
            }
            TextButton(onClick = { vm.refreshSessions() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Refresh")
            }
            LazyColumn {
                items(ui.sessions, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.title ?: s.preview ?: s.id.take(8)) },
                        supportingContent = {
                            Text("${s.source ?: "cli"} · ${s.model ?: "?"} · ${s.message_count ?: 0} msgs")
                        },
                        modifier = Modifier.clickable { vm.resumeSession(s.id) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (ui.showModelPicker) {
        ModalBottomSheet(onDismissRequest = { vm.toggleModelPicker(false) }) {
            Text(
                "Models",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
            LazyColumn {
                items(ui.modelOptions) { opt ->
                    val label = opt.label ?: opt.name ?: opt.id ?: "model"
                    ListItem(
                        headlineContent = { Text(label) },
                        supportingContent = { opt.provider?.let { Text(it) } },
                        modifier = Modifier.clickable { vm.selectModel(opt) },
                    )
                }
                if (ui.modelOptions.isEmpty()) {
                    item { Text("No options from API — try /model in chat", Modifier.padding(16.dp)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat", style = MaterialTheme.typography.titleLarge)
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    AssistChip(
                        onClick = { vm.toggleModelPicker() },
                        label = { Text(ui.modelLabel?.take(18) ?: "Model") },
                        leadingIcon = { Icon(Icons.Filled.SmartToy, contentDescription = null) },
                    )
                    IconButton(onClick = { vm.newChat() }) {
                        Icon(Icons.Filled.Add, contentDescription = "New chat")
                    }
                    IconButton(onClick = { vm.toggleSessionRail() }) {
                        Icon(Icons.Filled.History, contentDescription = "Session rail")
                    }
                    TextButton(onClick = onOpenSessions) { Text("All") }
                },
                windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (ui.showSlashPalette) {
                        ui.slashSuggestions.forEach { cmd ->
                            Text(
                                "${cmd.command} — ${cmd.description}",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.pickSlash(cmd) }
                                    .padding(vertical = 6.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (ui.partialDictation.isNotBlank()) {
                        Text(
                            "…${ui.partialDictation}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = ui.draft,
                            onValueChange = vm::updateDraft,
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message Hermes (/ for commands)") },
                            maxLines = 4,
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = vm::toggleListen) {
                            Icon(
                                if (ui.listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = "Dictate",
                            )
                        }
                        FilledIconButton(
                            onClick = { vm.send() },
                            enabled = ui.connected && ui.draft.isNotBlank(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp)
                .onSizeChanged { size ->
                    val cols = with(density) { (size.width.toDp().value / 8f).toInt() }
                    val rows = with(density) { (size.height.toDp().value / 18f).toInt() }
                    if (cols > 0 && rows > 0) vm.resizePty(cols, rows)
                },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ui.transcriptMode == TranscriptMode.TERMINAL,
                    onClick = { vm.setTranscriptMode(TranscriptMode.TERMINAL) },
                    label = { Text("Terminal") },
                )
                FilterChip(
                    selected = ui.transcriptMode == TranscriptMode.READING,
                    onClick = { vm.setTranscriptMode(TranscriptMode.READING) },
                    label = { Text("Reading") },
                )
            }
            ui.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            if (ui.tools.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    ui.tools.take(5).forEach { tool ->
                        ToolCallCard(tool, expanded = expandedTool == tool.id) {
                            expandedTool = if (expandedTool == tool.id) null else tool.id
                        }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(displayLines, key = { it.id }) { line ->
                    val mine = line.role == "user"
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = if (mine) 0.dp else 32.dp, start = if (mine) 32.dp else 0.dp),
                        color = when (line.role) {
                            "user" -> MaterialTheme.colorScheme.primaryContainer
                            "tool" -> MaterialTheme.colorScheme.tertiaryContainer
                            "system" -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCallCard(tool: ToolCallUi, expanded: Boolean, onToggle: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${tool.name} · ${tool.status}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
            if (expanded) {
                tool.argsPreview?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                tool.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
