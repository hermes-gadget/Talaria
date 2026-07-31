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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.ui.theme.HermesPanel
import androidx.compose.ui.Modifier

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

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Chat", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    when {
                        ui.connecting -> "Connecting to PTY…"
                        ui.connected -> "Live · Hermes TUI bridge"
                        else -> "Disconnected"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onOpenSessions) { Text("Sessions") }
        }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ui.lines, key = { it.id }) { line ->
                val mine = line.role == "user"
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = if (mine) 0.dp else 32.dp, start = if (mine) 32.dp else 0.dp)
                        .background(
                            if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else HermesPanel,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(12.dp),
                ) {
                    Text(line.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (ui.partialDictation.isNotBlank()) {
            Text("…${ui.partialDictation}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ui.draft,
                onValueChange = vm::updateDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Hermes") },
            )
            IconButton(onClick = vm::toggleListen) {
                Icon(
                    if (ui.listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = "Dictate",
                )
            }
            IconButton(onClick = { vm.send() }, enabled = ui.connected) {
                Icon(Icons.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
