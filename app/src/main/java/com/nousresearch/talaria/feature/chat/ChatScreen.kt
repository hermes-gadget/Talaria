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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nousresearch.talaria.TalariaApp

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

    LaunchedEffect(resumeSessionId, hasConnection) {
        if (!hasConnection) {
            onNeedConnection()
        } else {
            vm.connect(resumeSessionId)
        }
    }
    LaunchedEffect(initialShare) {
        if (!initialShare.isNullOrBlank()) vm.updateDraft(initialShare)
    }
    LaunchedEffect(ui.lines.size) {
        if (ui.lines.isNotEmpty()) listState.animateScrollToItem(ui.lines.lastIndex)
    }

    val status = when {
        ui.connecting -> "Connecting to PTY…"
        ui.connected -> "Live · Hermes TUI bridge"
        else -> "Disconnected"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Nav suite owns bottom system-bar inset; still pad IME above the composer.
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
                    TextButton(onClick = onOpenSessions) { Text("Sessions") }
                },
                windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (ui.partialDictation.isNotBlank()) {
                        Text(
                            "…${ui.partialDictation}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = ui.draft,
                            onValueChange = vm::updateDraft,
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message Hermes") },
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
                .padding(horizontal = 12.dp),
        ) {
            ui.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.lines, key = { it.id }) { line ->
                    val mine = line.role == "user"
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                end = if (mine) 0.dp else 32.dp,
                                start = if (mine) 32.dp else 0.dp,
                            ),
                        color = if (mine) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (mine) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}
