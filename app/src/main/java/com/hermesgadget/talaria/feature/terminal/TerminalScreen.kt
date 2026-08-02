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

package com.hermesgadget.talaria.feature.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.ui.components.ScreenScaffold

@Composable
fun TerminalScreen(
    onNeedConnection: () -> Unit = {},
    vm: TerminalViewModel = viewModel(factory = TerminalViewModel.factory()),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val connectionStore = TalariaApp.instance.container.connectionStore
    val profiles by connectionStore.profiles.collectAsStateWithLifecycle()
    val activeId by connectionStore.activeId.collectAsStateWithLifecycle()
    val hasConnection = profiles.any { it.id == activeId } || profiles.isNotEmpty()
    val lifecycleOwner = LocalLifecycleOwner.current
    val outputScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    LaunchedEffect(hasConnection) {
        if (hasConnection) vm.ensureStarted() else onNeedConnection()
    }
    LaunchedEffect(lifecycleOwner, hasConnection) {
        if (!hasConnection) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.reconnectOnResume()
        }
    }
    LaunchedEffect(ui.output) {
        outputScroll.scrollTo(outputScroll.maxValue)
    }

    val status = when (val connection = ui.connection) {
        is TerminalConnectionState.Connected -> "Connected"
        is TerminalConnectionState.Connecting -> "Connecting…"
        is TerminalConnectionState.Disconnected -> when {
            connection.explicit -> "Disconnected"
            connection.reason.isNullOrBlank() -> "Disconnected"
            else -> "Disconnected · ${connection.reason}"
        }
        is TerminalConnectionState.Failed -> "Connection failed"
    }
    val connected = ui.connection is TerminalConnectionState.Connected

    ScreenScaffold(
        title = "Terminal",
        subtitle = status,
        showProfileSwitcher = true,
        actions = {
            IconButton(onClick = vm::clearOutput) {
                Icon(Icons.Filled.ClearAll, contentDescription = "Clear terminal")
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = when (ui.connection) {
                        TerminalConnectionState.Connected -> MaterialTheme.colorScheme.primary
                        is TerminalConnectionState.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                if (connected) {
                    TextButton(onClick = vm::disconnect) {
                        Icon(Icons.Filled.LinkOff, contentDescription = null)
                        Text("Disconnect")
                    }
                } else {
                    TextButton(
                        onClick = vm::reconnect,
                        enabled = ui.connection !is TerminalConnectionState.Connecting,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text("Reconnect")
                    }
                }
            }
            if (ui.sidecarError != null) {
                Text(
                    "Sidecar: ${ui.sidecarError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (ui.connection is TerminalConnectionState.Failed) {
                Text(
                    (ui.connection as TerminalConnectionState.Failed).message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.medium,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(outputScroll)
                        .horizontalScroll(horizontalScroll),
                ) {
                    SelectionContainer {
                        Text(
                            text = ui.output.ifEmpty { "Terminal output will appear here." },
                            color = if (ui.output.isEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontFamily = FontFamily.Monospace,
                            softWrap = false,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = ui.input,
                    onValueChange = vm::updateInput,
                    enabled = connected,
                    singleLine = true,
                    placeholder = {
                        Text(if (connected) "Enter a command" else "Connect to enter commands")
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.sendInput() }),
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionUp -> vm.historyUp()
                                Key.DirectionDown -> vm.historyDown()
                                else -> false
                            }
                        },
                )
                FilledIconButton(
                    onClick = vm::sendInput,
                    enabled = connected && ui.input.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Send command")
                }
            }
        }
    }
}
