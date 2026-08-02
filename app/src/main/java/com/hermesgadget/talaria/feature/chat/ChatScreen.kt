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

package com.hermesgadget.talaria.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.MainActivity
import com.hermesgadget.talaria.domain.model.ToolCallUi
import com.hermesgadget.talaria.domain.model.scopeId
import com.hermesgadget.talaria.feature.pip.PipChatIntent
import com.hermesgadget.talaria.feature.pip.PipChatMessage
import com.hermesgadget.talaria.feature.pip.PipChatSnapshot
import com.hermesgadget.talaria.ui.components.SimpleMarkdownText

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    resumeSessionId: String? = null,
    initialShare: String? = null,
    initialShareImage: Uri? = null,
    onInitialShareConsumed: () -> Unit = {},
    onOpenSessions: () -> Unit,
    onNeedConnection: () -> Unit,
    vm: ChatViewModel = viewModel(factory = ChatViewModel.factory()),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val connectionStore = TalariaApp.instance.container.connectionStore
    val connectionProfiles by connectionStore.profiles.collectAsStateWithLifecycle()
    val activeConnectionId by connectionStore.activeId.collectAsStateWithLifecycle()
    val activeConnection = connectionProfiles.find { it.id == activeConnectionId }
        ?: connectionProfiles.firstOrNull()
    val connectionScope = activeConnection?.scopeId()
    val hasConnection = connectionScope != null
    val density = LocalDensity.current
    var renameTarget by remember { mutableStateOf<ChatTab?>(null) }
    var monitorOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val settings = TalariaApp.instance.container.settingsStore
    val lifecycleOwner = LocalLifecycleOwner.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.toggleListen()
        else vm.reportError("Microphone permission denied")
    }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let(vm::attachImage)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(resumeSessionId, connectionScope) {
        if (!hasConnection) onNeedConnection() else vm.ensureStarted(resumeSessionId)
    }
    // Ask once, in context, when the user opens a connected agent surface.
    // Settings can re-open the system prompt if the user later enables alerts.
    LaunchedEffect(hasConnection) {
        if (
            hasConnection && settings.notificationsEnabled && Build.VERSION.SDK_INT >= 33 &&
            !settings.notificationPermissionRequested &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            settings.notificationPermissionRequested = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Reconnect dead PTYs whenever Chat returns to the foreground (home/recents
    // leave the ViewModel alive with connected=false and no sockets).
    LaunchedEffect(lifecycleOwner, connectionScope) {
        if (!hasConnection) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.reconnectDisconnected()
        }
    }
    LaunchedEffect(initialShare, initialShareImage, ui.activeTabId) {
        if ((!initialShare.isNullOrBlank() || initialShareImage != null) && ui.activeTabId != null) {
            if (!initialShare.isNullOrBlank()) vm.updateDraft(initialShare)
            initialShareImage?.let(vm::attachImage)
            onInitialShareConsumed()
        }
    }
    val active = ui.active
    // Active turns are always a committed-message-only reading transcript.
    // Raw PTY/TUI output is available as an explicit diagnostic view only while idle.
    val transcriptMode = effectiveTranscriptMode(ui.transcriptMode, active?.working == true)
    val displayLines = visibleTranscriptLines(active, transcriptMode)
    // Follow the transcript only when the last line actually changed; instant
    // scroll (no animation) keeps up with stream-rate updates without jank.
    LaunchedEffect(displayLines.lastOrNull()?.let { it.id to it.text }, ui.activeTabId) {
        if (displayLines.isNotEmpty()) listState.scrollToItem(displayLines.lastIndex)
    }

    val status = when {
        active?.connecting == true -> "Connecting…"
        active?.connected == true -> buildString {
            append("Live · ${active.modelLabel ?: "Hermes"}")
            active.reasoningEffort?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            active.approvalMode?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            if (active.yolo) append(" · ⚡yolo")
            active.totalTokens?.let { append(" · ${it} tok") }
            active.costUsd?.let { append(" · $${"%.4f".format(it)}") }
        }
        else -> "Disconnected · tap to reconnect"
    }

    if (active?.prompt != null) {
        val prompt = active.prompt
        var clarifyText by remember(prompt.message) { mutableStateOf("") }
        val needsText = prompt.kind != com.hermesgadget.talaria.core.network.PromptKind.APPROVAL
        val masksText = prompt.kind == com.hermesgadget.talaria.core.network.PromptKind.SUDO ||
            prompt.kind == com.hermesgadget.talaria.core.network.PromptKind.SECRET
        AlertDialog(
            onDismissRequest = { vm.respondPrompt(false) },
            title = { Text(prompt.kind.name) },
            text = {
                Column {
                    Text(prompt.message)
                    if (prompt.choices.isNotEmpty()) {
                        Text(
                            "Choices: ${prompt.choices.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (needsText) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = clarifyText,
                            onValueChange = { clarifyText = it },
                            label = { Text(if (masksText) "Secure response" else "Response") },
                            visualTransformation = if (masksText) {
                                PasswordVisualTransformation()
                            } else {
                                androidx.compose.ui.text.input.VisualTransformation.None
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (needsText) vm.respondPrompt(true, clarifyText.ifBlank { null })
                        else vm.respondPrompt(true)
                    },
                ) { Text(if (needsText) "Send" else "Approve") }
            },
            dismissButton = {
                TextButton(onClick = { vm.respondPrompt(false) }) { Text("Deny") }
            },
        )
    }

    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename agent") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.renameTab(target.id, name)
                        renameTarget = null
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }

    when (val dialog = ui.sessionControls.dialog) {
        is ChatSessionDialog.Rewind -> AlertDialog(
            onDismissRequest = vm::dismissSessionDialog,
            title = { Text("Rewind to this message?") },
            text = {
                Text(
                    "This creates a new chat branch containing the conversation through this message. " +
                        "The current session stays open.\n\n${dialog.preview.take(240)}",
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmSessionDialog) { Text("Create branch") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissSessionDialog) { Text("Cancel") }
            },
        )
        is ChatSessionDialog.Compact -> AlertDialog(
            onDismissRequest = vm::dismissSessionDialog,
            title = { Text("Compact session?") },
            text = {
                Text("Hermes will summarize older context to reduce the session's context usage.")
            },
            confirmButton = {
                TextButton(onClick = vm::confirmSessionDialog) { Text("Compact") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissSessionDialog) { Text("Cancel") }
            },
        )
        is ChatSessionDialog.EditTitle -> {
            var title by remember(dialog.sessionId) { mutableStateOf(dialog.initialTitle) }
            AlertDialog(
                onDismissRequest = vm::dismissSessionDialog,
                title = { Text("Edit session title") },
                text = {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.confirmSessionTitle(title) }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = vm::dismissSessionDialog) { Text("Cancel") }
                },
            )
        }
        null -> Unit
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
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { vm.newSession() }) { Text("New agent") }
                TextButton(onClick = { vm.refreshSessions() }) { Text("Refresh") }
                TextButton(onClick = {
                    vm.toggleSessionRail(false)
                    onOpenSessions()
                }) { Text("All sessions") }
            }
            LazyColumn {
                items(ui.sessions, key = { it.id }) { s ->
                    val isOpen = ui.tabs.any { it.liveSessionId == s.id || it.resumeSessionId == s.id }
                    val parentId = ui.sessionBranchOrigins[s.id]
                    val parentTitle = parentId?.let { id ->
                        ui.sessions.firstOrNull { it.id == id }?.title?.takeIf { it.isNotBlank() }
                            ?: id.take(8)
                    }
                    ListItem(
                        headlineContent = {
                            Text(
                                (s.title ?: s.preview ?: s.id.take(8)) + if (isOpen) " · open" else "",
                                color = if (isOpen) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        supportingContent = {
                            Column {
                                Text("${s.source ?: "cli"} · ${s.model ?: "?"} · ${s.message_count ?: 0} msgs")
                                parentTitle?.let {
                                    Text(
                                        "Branch from $it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { vm.requestSessionTitleEdit(s.id) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit session title")
                            }
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
                items(ui.modelOptions, key = { it.id ?: it.name ?: it.label ?: it.hashCode() }) { opt ->
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
                        Text("Chat", style = MaterialTheme.typography.titleMedium)
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (active?.connected == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(
                                enabled = active != null && !active.connected && !active.connecting,
                            ) { vm.reconnectDisconnected() },
                        )
                    }
                },
                actions = {
                    val reading = transcriptMode == TranscriptMode.READING
                    if (active?.working == true && active.connected) {
                        IconButton(onClick = { vm.sendInterrupt() }) {
                            Icon(Icons.Filled.Stop, contentDescription = "Interrupt (Ctrl-C)")
                        }
                    }
                    if (active?.working != true) {
                        IconButton(onClick = {
                            vm.setTranscriptMode(
                                if (reading) TranscriptMode.TERMINAL else TranscriptMode.READING,
                            )
                        }) {
                            Icon(
                                if (reading) Icons.Filled.Terminal else Icons.AutoMirrored.Filled.Article,
                                contentDescription = if (reading) "Terminal view" else "Reading view",
                            )
                        }
                    }
                    val sessionReady = active?.liveSessionId != null || active?.resumeSessionId != null
                    val sessionActionRunning = ui.sessionControls.action is ChatSessionActionState.Running
                    IconButton(onClick = { vm.toggleSessionActions() }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Session actions")
                    }
                    DropdownMenu(
                        expanded = ui.showSessionActions,
                        onDismissRequest = { vm.toggleSessionActions(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Compact session") },
                            onClick = {
                                vm.toggleSessionActions(false)
                                vm.requestCompactSession()
                            },
                            enabled = sessionReady && active?.working != true && !sessionActionRunning,
                        )
                        DropdownMenuItem(
                            text = { Text("Edit session title") },
                            onClick = {
                                vm.toggleSessionActions(false)
                                vm.requestSessionTitleEdit()
                            },
                            enabled = sessionReady && !sessionActionRunning,
                        )
                    }
                    IconButton(onClick = { monitorOpen = !monitorOpen }) {
                        Icon(Icons.Filled.Hub, contentDescription = "Agent activity")
                    }
                    IconButton(onClick = { vm.toggleModelPicker() }) {
                        Icon(Icons.Filled.SmartToy, contentDescription = "Change model")
                    }
                    IconButton(onClick = { vm.toggleSteerPopover() }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Steer and trigger settings")
                    }
                    IconButton(
                        onClick = {
                            val tab = active ?: return@IconButton
                            val messages = (tab.readingMessages.ifEmpty { tab.lines })
                                .filter { it.text.isNotBlank() }
                                .takeLast(24)
                                .map { PipChatMessage(role = it.role, text = it.text) }
                            val snapshot = PipChatSnapshot(
                                title = tab.title,
                                messages = messages,
                                streamingText = tab.streamingText.takeIf {
                                    tab.assistantStreaming && it.isNotBlank()
                                }.orEmpty(),
                            )
                            if (context is MainActivity) {
                                context.openPipChat(snapshot)
                            } else {
                                context.startActivity(PipChatIntent.create(context, snapshot))
                            }
                        },
                        enabled = active != null,
                    ) {
                        Icon(
                            Icons.Filled.PictureInPictureAlt,
                            contentDescription = "Open chat in picture-in-picture",
                        )
                    }
                    DropdownMenu(
                        expanded = ui.showSteerPopover,
                        onDismissRequest = { vm.toggleSteerPopover(false) },
                    ) {
                        Text(
                            "Steer / trigger",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Model")
                                    Text(
                                        active?.modelLabel ?: "Choose a model",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                vm.toggleSteerPopover(false)
                                vm.toggleModelPicker(true)
                            },
                            enabled = active != null,
                        )
                        Text(
                            "Reasoning effort",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        CHAT_REASONING_EFFORTS.forEach { effort ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${if (active?.reasoningEffort == effort) "✓ " else ""}$effort",
                                    )
                                },
                                onClick = {
                                    vm.setReasoningEffort(effort)
                                    vm.toggleSteerPopover(false)
                                },
                                enabled = active?.liveSessionId != null || active?.resumeSessionId != null,
                            )
                        }
                        Text(
                            "Approval mode · global dashboard setting",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        CHAT_APPROVAL_MODES.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${if (active?.approvalMode == mode) "✓ " else ""}$mode",
                                    )
                                },
                                onClick = {
                                    vm.setApprovalMode(mode)
                                    vm.toggleSteerPopover(false)
                                },
                                enabled = active != null,
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("YOLO")
                                    Text(
                                        if (active?.yolo == true) "On · approvals bypassed for this session"
                                        else "Off · prompts remain enabled",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                if (sessionReady) vm.setYolo(active?.yolo != true)
                            },
                            enabled = sessionReady,
                            trailingIcon = {
                                Switch(
                                    checked = active?.yolo == true,
                                    onCheckedChange = { vm.setYolo(it) },
                                    enabled = sessionReady,
                                )
                            },
                        )
                        if (!sessionReady) {
                            Text(
                                "Reasoning and YOLO unlock after Hermes assigns a live session.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    IconButton(onClick = { vm.toggleSessionRail() }) {
                        Icon(Icons.Filled.History, contentDescription = "Sessions")
                    }
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
                        // The app's bottom navigation bar is hidden while the keyboard is
                        // open (see TalariaNavRoot), so the composer reaches the screen
                        // bottom and a plain imePadding() hugs the keyboard with no gap.
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (ui.showSlashPalette) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                        ) {
                            items(ui.slashSuggestions, key = { "${it.command}-${it.category}" }) { cmd ->
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            cmd.command,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            "${cmd.category} · ${cmd.description}",
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    modifier = Modifier.clickable { vm.pickSlash(cmd) },
                                )
                            }
                        }
                    }
                    if (ui.partialDictation.isNotBlank()) {
                        Text(
                            "…${ui.partialDictation}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (active?.imageAttachments?.isNotEmpty() == true) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            active.imageAttachments.forEach { attachment ->
                                val busy = attachment.status == ChatImageAttachmentStatus.UPLOADING
                                val staged = attachment.status == ChatImageAttachmentStatus.ATTACHED
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = if (attachment.status == ChatImageAttachmentStatus.ERROR) {
                                        MaterialTheme.colorScheme.errorContainer
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    },
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 8.dp),
                                    ) {
                                        if (busy) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(
                                            when {
                                                staged -> "${attachment.filename} · staged"
                                                attachment.status == ChatImageAttachmentStatus.ERROR ->
                                                    "${attachment.filename} · retry"
                                                else -> attachment.filename
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        IconButton(
                                            onClick = { vm.removeImageAttachment(attachment.id) },
                                            enabled = !busy && !staged,
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Remove ${attachment.filename}",
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (active?.queuedPrompts?.isNotEmpty() == true) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                Text(
                                    "${active.queuedPrompts.size} queued",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                )
                            }
                        }
                        OutlinedTextField(
                            value = active?.draft.orEmpty(),
                            onValueChange = vm::updateDraft,
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
                            placeholder = {
                                Text(
                                    "Message Hermes…",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            shape = MaterialTheme.shapes.large,
                            maxLines = 4,
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                imagePickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            enabled = active?.connected == true &&
                                active.imageAttachments.none {
                                    it.status == ChatImageAttachmentStatus.UPLOADING
                                },
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = "Attach image")
                        }
                        IconButton(
                            onClick = {
                                if (ui.listening) {
                                    vm.toggleListen()
                                    return@IconButton
                                }
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) vm.toggleListen()
                                else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                        ) {
                            Icon(
                                if (ui.listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = "Dictate",
                            )
                        }
                        FilledIconButton(
                            onClick = { vm.send() },
                            enabled = active?.connected == true &&
                                (!active.draft.isBlank() || active.imageAttachments.isNotEmpty()) &&
                                active.imageAttachments.none {
                                    it.status == ChatImageAttachmentStatus.UPLOADING
                                },
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
            SessionTabStrip(
                tabs = ui.tabs,
                activeTabId = ui.activeTabId,
                onSelect = { vm.switchTab(it) },
                onClose = { vm.closeTab(it) },
                onRename = { renameTarget = it },
                onAdd = { vm.newSession() },
            )
            if (monitorOpen) {
                SubagentMonitor(
                    active = active,
                    eventClient = TalariaApp.instance.container.eventClient,
                    onDismiss = { monitorOpen = false },
                )
            }
            active?.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            when (val action = ui.sessionControls.action) {
                is ChatSessionActionState.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        when (action.kind) {
                            ChatSessionActionKind.REWIND -> "Creating branch…"
                            ChatSessionActionKind.COMPACT -> "Compacting session…"
                            ChatSessionActionKind.RENAME -> "Saving session title…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ChatSessionActionState.Success -> Text(
                    action.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                is ChatSessionActionState.Failure -> Text(
                    action.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                ChatSessionActionState.Idle -> Unit
            }
            // A single live "working" indicator (spinner + the current tool), not a
            // running list of tool cards. It clears itself when the reply arrives.
            if (active?.working == true) {
                WorkingIndicator(currentTool = active.tools.firstOrNull())
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (displayLines.isEmpty()) {
                    item {
                        Text(
                            if (transcriptMode == TranscriptMode.READING) {
                                "No messages yet — say hello to Hermes."
                            } else {
                                "Waiting for terminal output…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                itemsIndexed(displayLines, key = { _, line -> line.id }) { displayedIndex, line ->
                    val mine = line.role == "user"
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = if (mine) 0.dp else 32.dp, start = if (mine) 32.dp else 0.dp)
                            .combinedClickable(
                                enabled = transcriptMode == TranscriptMode.READING,
                                onClick = {},
                                onLongClick = {
                                    vm.requestRewind(
                                        messageCount = branchMessageCount(line, displayedIndex),
                                        preview = line.text,
                                    )
                                },
                                onLongClickLabel = "Rewind to this message",
                            ),
                        color = when (line.role) {
                            "user" -> MaterialTheme.colorScheme.primaryContainer
                            "tool" -> MaterialTheme.colorScheme.tertiaryContainer
                            "system" -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        if (transcriptMode == TranscriptMode.READING || line.role == "assistant") {
                            SimpleMarkdownText(line.text, modifier = Modifier.padding(12.dp))
                        } else {
                            // Terminal mode: selectable so users can copy PTY output.
                            SelectionContainer {
                                Text(
                                    line.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
                }
                // Tool events arrive before message.complete and are cleared
                // when the turn settles. Rendering here keeps the card inside
                // the message-items region while still avoiding work on every
                // frame once the completed turn is immutable.
                if (active?.working == true) {
                    item(key = "changed-files-${active.id}") {
                        ChangedFilesCard(
                            tools = active.tools,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One tab per running Hermes agent, with a live status dot and a close affordance.
 * Long-pressing a tab opens a rename dialog (via [onRename]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionTabStrip(
    tabs: List<ChatTab>,
    activeTabId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onRename: (ChatTab) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val effectiveActive = activeTabId ?: tabs.firstOrNull()?.id
        tabs.forEach { tab ->
            val selected = tab.id == effectiveActive
            val dotColor = when {
                tab.connected -> MaterialTheme.colorScheme.primary
                tab.connecting -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.outline
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.combinedClickable(
                    onClick = { onSelect(tab.id) },
                    onLongClick = { onRename(tab) },
                    onLongClickLabel = "Rename agent",
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Text(
                        tab.title,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (tabs.size > 1) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close ${tab.title}",
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onClose(tab.id) },
                        )
                    }
                }
            }
        }
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = "New agent")
        }
    }
}

/**
 * Compact "agent is working" indicator: a spinner plus the current tool the model
 * is running (if any). Shown only while a turn is in flight; it vanishes when the
 * reply lands. Replaces the old running list of tool cards.
 */
@Composable
private fun WorkingIndicator(currentTool: ToolCallUi?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        val label = when {
            currentTool == null -> "Working…"
            currentTool.status == "RUNNING" -> "Running · ${currentTool.name}"
            currentTool.status == "ERROR" -> "${currentTool.name} failed — continuing…"
            else -> "${currentTool.name} done — thinking…"
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
