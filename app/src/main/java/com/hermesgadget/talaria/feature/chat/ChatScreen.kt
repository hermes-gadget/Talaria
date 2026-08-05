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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.awaitCancellation
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.MainActivity
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.domain.model.ToolCallUi
import com.hermesgadget.talaria.domain.model.scopeId
import com.hermesgadget.talaria.feature.manage.sessions.SessionFilters
import com.hermesgadget.talaria.feature.manage.sessions.SessionTab
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
    var sessionRailTab by remember { mutableStateOf(SessionTab.Chats) }
    val windowInfo = LocalWindowInfo.current
    val isExpanded = with(LocalDensity.current) {
        windowInfo.containerSize.width.toDp() >= 600.dp
    }

    val context = LocalContext.current
    val microphonePermissionDenied = stringResource(R.string.chat_microphone_permission_denied)
    val settings = TalariaApp.instance.container.settingsStore
    val lifecycleOwner = LocalLifecycleOwner.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.toggleListen()
        else vm.reportError(microphonePermissionDenied)
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
            vm.setChatLifecycleStarted(true)
            try {
                vm.reconnectDisconnected()
                awaitCancellation()
            } finally {
                vm.setChatLifecycleStarted(false)
            }
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
    val searchedLines = filterTranscriptLines(displayLines, ui.transcriptQuery)
    // Follow the transcript only when the last line actually changed; instant
    // scroll (no animation) keeps up with stream-rate updates without jank.
    LaunchedEffect(searchedLines.lastOrNull()?.let { it.id to it.text }, ui.activeTabId, ui.transcriptQuery) {
        if (searchedLines.isNotEmpty()) listState.scrollToItem(searchedLines.lastIndex)
    }

    val status = when (val recovery = active?.transportRecovery) {
        is ChatTransportRecoveryState.Recovering -> stringResource(
            R.string.chat_status_recovering,
            recovery.attempt,
            recovery.maxAttempts,
            ((recovery.delayMs + 999L) / 1000L).coerceAtLeast(1L),
        )
        ChatTransportRecoveryState.Reconciled -> stringResource(R.string.chat_status_reconciled)
        is ChatTransportRecoveryState.Retry -> stringResource(R.string.chat_status_retry)
        is ChatTransportRecoveryState.ConsentRequired -> stringResource(R.string.chat_status_consent_required)
        null -> stringResource(R.string.chat_status_disconnected)
        ChatTransportRecoveryState.None -> when {
            active?.connecting == true -> stringResource(R.string.chat_status_connecting)
            active?.connected == true -> {
                var liveStatus = stringResource(
                    R.string.chat_status_live,
                    active.modelLabel ?: stringResource(R.string.app_name),
                )
                val reasoningEffort = active.reasoningEffort?.takeIf { it.isNotBlank() }
                if (reasoningEffort != null) {
                    liveStatus += stringResource(R.string.chat_status_detail, reasoningEffort)
                }
                val approvalMode = active.approvalMode?.takeIf { it.isNotBlank() }
                if (approvalMode != null) {
                    liveStatus += stringResource(R.string.chat_status_detail, approvalMode)
                }
                if (active.yolo) liveStatus += stringResource(R.string.chat_status_yolo)
                if (active.totalTokens != null) {
                    liveStatus += pluralStringResource(R.plurals.chat_status_tokens, active.totalTokens.toInt(), active.totalTokens)
                }
                if (active.costUsd != null) {
                    liveStatus += stringResource(R.string.chat_status_cost, active.costUsd)
                }
                liveStatus
            }
            else -> stringResource(R.string.chat_status_disconnected)
        }
    }

    if (active?.prompt != null) {
        val prompt = active.prompt
        val promptSessionId = active.liveSessionId ?: active.resumeSessionId
        // Message text is not an identity: two consecutive secret/clarify
        // requests can deliberately carry the same wording. The gateway
        // request id is preferred, with the per-event instance id covering
        // gateways that omit one; tab, kind, and session remain part of scope.
        val promptStateKey = listOf(
            active.id,
            promptSessionId.orEmpty(),
            prompt.kind.name,
            prompt.requestId.orEmpty(),
            prompt.instanceId,
        )
        var clarifyText by remember(promptStateKey) { mutableStateOf("") }
        // N0.2: approval prompts offer explicit choice buttons instead of a
        // single generic Approve. Broad choices require a confirm step.
        var pendingBroadChoice by remember(promptStateKey) { mutableStateOf<String?>(null) }
        val needsText = prompt.kind != com.hermesgadget.talaria.core.network.PromptKind.APPROVAL
        val masksText = prompt.kind == com.hermesgadget.talaria.core.network.PromptKind.SUDO ||
            prompt.kind == com.hermesgadget.talaria.core.network.PromptKind.SECRET
        val approvalChoices = if (needsText) emptyList() else {
            val offered = prompt.choices
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && it != "deny" }
                .distinct()
            offered.ifEmpty { listOf(ApprovalChoicePolicy.SAFE_ONESHOT_CHOICES.first()) }
        }
        AlertDialog(
            onDismissRequest = {
                clarifyText = ""
                pendingBroadChoice = null
                vm.respondPrompt(false)
            },
            title = { Text(prompt.kind.name) },
            text = {
                Column {
                    Text(prompt.message)
                    if (prompt.choices.isNotEmpty()) {
                        Text(
                            stringResource(R.string.chat_choices, prompt.choices.joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    pendingBroadChoice?.let { broad ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.chat_approval_broad_warning, broad),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (needsText) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = clarifyText,
                            onValueChange = { clarifyText = it },
                            label = {
                                Text(
                                    stringResource(
                                        if (masksText) R.string.chat_secure_response else R.string.chat_response,
                                    ),
                                )
                            },
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
                if (needsText) {
                    TextButton(
                        onClick = {
                            val response = clarifyText.ifBlank { null }
                            clarifyText = ""
                            vm.respondPrompt(true, response)
                        },
                    ) {
                        Text(stringResource(R.string.common_send))
                    }
                } else {
                    // N0.2: explicit per-choice approval buttons. Broad choices
                    // arm a confirm step instead of granting immediately.
                    val broad = pendingBroadChoice
                    if (broad != null) {
                        TextButton(onClick = {
                            pendingBroadChoice = null
                            vm.respondPrompt(true, approvalChoice = broad)
                        }) {
                            Text(stringResource(R.string.chat_approval_confirm, broad))
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.End) {
                            approvalChoices.forEach { choice ->
                                val isBroad = ApprovalChoicePolicy.requiresExplicitBroadConfirm(choice)
                                TextButton(onClick = {
                                    if (isBroad) pendingBroadChoice = choice
                                    else vm.respondPrompt(true, approvalChoice = choice)
                                }) {
                                    Text(stringResource(R.string.chat_approval_choice_button, choice))
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    clarifyText = ""
                    pendingBroadChoice = null
                    vm.respondPrompt(false)
                }) {
                    Text(stringResource(R.string.common_deny))
                }
            },
        )
    }

    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.chat_rename_agent)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.common_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.renameTab(target.id, name)
                        renameTarget = null
                    },
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    when (val dialog = ui.sessionControls.dialog) {
        is ChatSessionDialog.Rewind -> AlertDialog(
            onDismissRequest = vm::dismissSessionDialog,
            title = { Text(stringResource(R.string.chat_rewind_title)) },
            text = {
                Text(
                    stringResource(R.string.chat_rewind_body, dialog.preview.take(240)),
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmSessionDialog) {
                    Text(stringResource(R.string.common_create_branch))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissSessionDialog) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
        is ChatSessionDialog.Compact -> AlertDialog(
            onDismissRequest = vm::dismissSessionDialog,
            title = { Text(stringResource(R.string.chat_compact_title)) },
            text = {
                Text(stringResource(R.string.chat_compact_body))
            },
            confirmButton = {
                TextButton(onClick = vm::confirmSessionDialog) {
                    Text(stringResource(R.string.common_compact))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissSessionDialog) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
        is ChatSessionDialog.EditTitle -> {
            var title by remember(dialog.sessionId) { mutableStateOf(dialog.initialTitle) }
            AlertDialog(
                onDismissRequest = vm::dismissSessionDialog,
                title = { Text(stringResource(R.string.common_edit_session_title)) },
                text = {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.common_title)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.confirmSessionTitle(title) }) {
                        Text(stringResource(R.string.common_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = vm::dismissSessionDialog) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
        is ChatSessionDialog.MessageActions -> AlertDialog(
            onDismissRequest = vm::dismissSessionDialog,
            title = { Text(stringResource(R.string.chat_message_actions_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.chat_message_actions_body,
                            dialog.target.text.take(240),
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    if (dialog.target.role == "user") {
                        TextButton(
                            onClick = vm::beginMessageEdit,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.chat_edit_message))
                        }
                    }
                    TextButton(
                        onClick = vm::beginMessageBranch,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.chat_branch_new_chat))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissSessionDialog) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            confirmButton = {},
        )
        is ChatSessionDialog.EditMessage -> {
            var edited by remember(dialog.target.text) { mutableStateOf(dialog.target.text) }
            AlertDialog(
                onDismissRequest = vm::dismissSessionDialog,
                title = { Text(stringResource(R.string.chat_edit_message_title)) },
                text = {
                    OutlinedTextField(
                        value = edited,
                        onValueChange = { edited = it },
                        label = { Text(stringResource(R.string.chat_edit_message_label)) },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.confirmMessageEdit(edited) }) {
                        Text(stringResource(R.string.common_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = vm::dismissSessionDialog) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
        null -> Unit
    }

    if (ui.showSessionRail && !isExpanded) {
        ModalBottomSheet(
            onDismissRequest = { vm.toggleSessionRail(false) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                stringResource(R.string.chat_sessions),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { vm.newSession() }) {
                    Text(stringResource(R.string.common_new_agent))
                }
                TextButton(onClick = { vm.refreshSessions() }) {
                    Text(stringResource(R.string.common_refresh))
                }
                TextButton(onClick = {
                    vm.toggleSessionRail(false)
                    onOpenSessions()
                }) { Text(stringResource(R.string.common_all_sessions)) }
            }
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SessionTab.entries.forEach { tab ->
                    FilterChip(
                        selected = sessionRailTab == tab,
                        onClick = { sessionRailTab = tab },
                        label = {
                            Text(
                                when (tab) {
                                    SessionTab.Chats -> stringResource(R.string.sessions_tab_chats)
                                    SessionTab.Automation -> stringResource(R.string.sessions_tab_automation)
                                    SessionTab.All -> stringResource(R.string.sessions_tab_all)
                                },
                            )
                        },
                    )
                }
            }
            LazyColumn {
                val filtered = ui.sessions.filter {
                    SessionFilters.matchesTab(it.source, sessionRailTab)
                }
                items(filtered, key = { it.id }) { s ->
                    val isOpen = ui.tabs.any { it.liveSessionId == s.id || it.resumeSessionId == s.id }
                    val parentId = ui.sessionBranchOrigins[s.id]
                    val parentTitle = parentId?.let { id ->
                        ui.sessions.firstOrNull { it.id == id }?.title?.takeIf { it.isNotBlank() }
                            ?: id.take(8)
                    }
                    ListItem(
                        headlineContent = {
                            Text(
                                (s.title ?: s.preview ?: s.id.take(8)) +
                                    if (isOpen) stringResource(R.string.chat_open_suffix) else "",
                                color = if (isOpen) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    stringResource(
                                        R.string.chat_session_metadata,
                                        s.source ?: stringResource(R.string.chat_cli_source),
                                        s.model ?: stringResource(R.string.chat_unknown_model),
                                        s.message_count ?: 0,
                                    ),
                                )
                                if (parentTitle != null) {
                                    Text(
                                        stringResource(R.string.chat_branch_from, parentTitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { vm.requestSessionTitleEdit(s.id) }) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = stringResource(
                                        R.string.chat_edit_session_title_content_description,
                                    ),
                                )
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
                stringResource(R.string.chat_models),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
            LazyColumn {
                items(ui.modelOptions, key = { it.id ?: it.name ?: it.label ?: it.hashCode() }) { opt ->
                    val label = opt.label ?: opt.name ?: opt.id ?: stringResource(R.string.chat_model_fallback)
                    ListItem(
                        headlineContent = { Text(label) },
                        supportingContent = { opt.provider?.let { Text(it) } },
                        modifier = Modifier.clickable { vm.selectModel(opt) },
                    )
                }
                if (ui.modelOptions.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.chat_no_model_options),
                            Modifier.padding(16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    Box(Modifier.fillMaxSize()) {
        val openSessionIds = ui.tabs.mapNotNull { it.resumeSessionId ?: it.liveSessionId }.toSet()
        Row(Modifier.fillMaxSize()) {
            if (isExpanded) {
                SessionRailPane(
                    sessions = ui.sessions,
                    sessionBranchOrigins = ui.sessionBranchOrigins,
                    openSessionIds = openSessionIds,
                    sessionRailTab = sessionRailTab,
                    onTabSelect = { sessionRailTab = it },
                    onNewSession = { vm.newSession() },
                    onRefreshSessions = { vm.refreshSessions() },
                    onOpenAllSessions = onOpenSessions,
                    onResumeSession = { vm.resumeSession(it) },
                )
            }
    Scaffold(
        modifier = Modifier.weight(1f).fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.titleMedium)
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
                                enabled = active != null &&
                                    !active.connected &&
                                    !active.connecting &&
                                    active.transportRecovery !is ChatTransportRecoveryState.Retry &&
                                    active.transportRecovery !is ChatTransportRecoveryState.ConsentRequired,
                            ) { vm.reconnectDisconnected() },
                        )
                    }
                },
                actions = {
                    val reading = transcriptMode == TranscriptMode.READING
                    val sessionReady = active?.liveSessionId != null || active?.resumeSessionId != null
                    val sessionActionRunning = ui.sessionControls.action is ChatSessionActionState.Running
                    // Three visible actions at most. The first slot is contextual:
                    // interrupt while a turn is running, transcript-mode toggle
                    // while idle. Everything else lives in the overflow menu.
                    if (active?.working == true && active.connected) {
                        IconButton(onClick = { vm.sendInterrupt() }) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = stringResource(R.string.chat_interrupt),
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            vm.setTranscriptMode(
                                if (reading) TranscriptMode.TERMINAL else TranscriptMode.READING,
                            )
                        }) {
                            Icon(
                                if (reading) Icons.Filled.Terminal else Icons.AutoMirrored.Filled.Article,
                                contentDescription = stringResource(
                                    if (reading) R.string.chat_terminal_view else R.string.chat_reading_view,
                                ),
                            )
                        }
                    }
                    IconButton(
                        onClick = { vm.toggleTranscriptSearch() },
                        enabled = active != null,
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.chat_find_in_session),
                        )
                    }
                    if (!isExpanded) {
                        IconButton(onClick = { vm.toggleSessionRail() }) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = stringResource(R.string.chat_sessions),
                            )
                        }
                    }
                    IconButton(onClick = { vm.toggleSteerPopover() }) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.chat_steer_settings),
                        )
                    }
                    IconButton(onClick = { vm.toggleSessionActions() }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.chat_session_actions),
                        )
                    }
                    DropdownMenu(
                        expanded = ui.showSessionActions,
                        onDismissRequest = { vm.toggleSessionActions(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_agent_activity)) },
                            leadingIcon = { Icon(Icons.Filled.Hub, contentDescription = null) },
                            onClick = {
                                vm.toggleSessionActions(false)
                                monitorOpen = !monitorOpen
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_pip)) },
                            leadingIcon = {
                                Icon(Icons.Filled.PictureInPictureAlt, contentDescription = null)
                            },
                            onClick = {
                                vm.toggleSessionActions(false)
                                val tab = active ?: return@DropdownMenuItem
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
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_compact_session)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Compress, contentDescription = null)
                            },
                            onClick = {
                                vm.toggleSessionActions(false)
                                vm.requestCompactSession()
                            },
                            enabled = sessionReady && active?.working != true && !sessionActionRunning,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_edit_session_title)) },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = {
                                vm.toggleSessionActions(false)
                                vm.requestSessionTitleEdit()
                            },
                            enabled = sessionReady && !sessionActionRunning,
                        )
                    }
                    DropdownMenu(
                        expanded = ui.showSteerPopover,
                        onDismissRequest = { vm.toggleSteerPopover(false) },
                    ) {
                        Text(
                            stringResource(R.string.chat_steer_trigger),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(stringResource(R.string.chat_models))
                                    Text(
                                        active?.modelLabel ?: stringResource(R.string.chat_choose_model),
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
                            stringResource(R.string.chat_reasoning_effort),
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
                            stringResource(R.string.chat_approval_global),
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
                                    Text(stringResource(R.string.chat_yolo))
                                    Text(
                                        if (active?.yolo == true) {
                                            stringResource(R.string.chat_yolo_on)
                                        } else {
                                            stringResource(R.string.chat_yolo_off)
                                        },
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
                                stringResource(R.string.chat_unlock_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
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
                                            stringResource(
                                                R.string.common_subtitle_separator,
                                                "${cmd.category} · ${cmd.description}",
                                            ),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    modifier = Modifier.clickable { vm.pickSlash(cmd) },
                                )
                            }
                        }
                    }
                    if (ui.composerSuggestions.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp),
                        ) {
                            item {
                                Text(
                                    stringResource(R.string.chat_composer_suggestions),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            }
                            items(
                                ui.composerSuggestions,
                                key = { "${it.kind}-${it.label}" },
                            ) { suggestion ->
                                ListItem(
                                    headlineContent = { Text(suggestion.label) },
                                    modifier = Modifier.clickable {
                                        vm.pickComposerCompletion(suggestion)
                                    },
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
                    if (ui.composerReferences.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ui.composerReferences.forEach { reference ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Text(
                                        reference.value,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
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
                                                staged -> stringResource(
                                                    R.string.chat_attachment_staged,
                                                    attachment.filename,
                                                )
                                                attachment.status == ChatImageAttachmentStatus.ERROR ->
                                                    stringResource(
                                                        R.string.chat_attachment_retry,
                                                        attachment.filename,
                                                    )
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
                                                contentDescription = stringResource(
                                                    R.string.chat_remove_attachment,
                                                    attachment.filename,
                                                ),
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
                                    pluralStringResource(R.plurals.chat_queued, active.queuedPrompts.size, active.queuedPrompts.size),
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
                                    stringResource(R.string.chat_message_placeholder),
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
                            Icon(
                                Icons.Filled.AttachFile,
                                contentDescription = stringResource(R.string.chat_attach_image),
                            )
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
                                contentDescription = stringResource(R.string.chat_dictate),
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
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.common_send),
                            )
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
            if (ui.showTranscriptSearch) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = ui.transcriptQuery,
                        onValueChange = vm::updateTranscriptQuery,
                        singleLine = true,
                        label = { Text(stringResource(R.string.chat_find_in_session)) },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        pluralStringResource(
                            R.plurals.chat_search_matches,
                            transcriptMatchCount(displayLines, ui.transcriptQuery),
                            transcriptMatchCount(displayLines, ui.transcriptQuery),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    IconButton(onClick = { vm.toggleTranscriptSearch(false) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.chat_close_search),
                        )
                    }
                }
            }
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
            ui.reconciliationStatus?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::refreshSessions) {
                        Text(stringResource(R.string.chat_refresh_now), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            when (val recovery = active?.transportRecovery) {
                is ChatTransportRecoveryState.Recovering -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        recovery.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                is ChatTransportRecoveryState.Retry -> Column(
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    Text(
                        recovery.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { active?.id?.let(vm::reconnectTab) }) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
                is ChatTransportRecoveryState.ConsentRequired -> Column(
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.chat_consent_required_message, recovery.origin),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { vm.grantCleartextConsent() }) {
                        Text(stringResource(R.string.chat_consent_allow_http))
                    }
                }
                ChatTransportRecoveryState.None,
                ChatTransportRecoveryState.Reconciled -> Unit
                null -> Unit
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
                            ChatSessionActionKind.REWIND -> stringResource(R.string.chat_action_rewind)
                            ChatSessionActionKind.COMPACT -> stringResource(R.string.chat_action_compact)
                            ChatSessionActionKind.RENAME -> stringResource(R.string.chat_action_rename)
                            ChatSessionActionKind.EDIT -> stringResource(R.string.chat_action_edit)
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
                if (searchedLines.isEmpty()) {
                    item {
                        Text(
                            if (ui.transcriptQuery.isNotBlank()) {
                                stringResource(R.string.chat_search_no_results)
                            } else if (transcriptMode == TranscriptMode.READING) {
                                stringResource(R.string.chat_empty_reading)
                            } else {
                                stringResource(R.string.chat_empty_terminal)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                itemsIndexed(searchedLines, key = { _, line -> line.id }) { displayedIndex, line ->
                    val mine = line.role == "user"
                    val originalIndex = displayLines.indexOfFirst { it.id == line.id }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = if (mine) 0.dp else 32.dp, start = if (mine) 32.dp else 0.dp)
                            .combinedClickable(
                                enabled = transcriptMode == TranscriptMode.READING,
                                onClick = {},
                                onLongClick = {
                                    vm.requestMessageActions(
                                        line = line,
                                        displayedIndex = originalIndex.takeIf { it >= 0 } ?: displayedIndex,
                                    )
                                },
                                onLongClickLabel = stringResource(R.string.chat_message_actions),
                            ),
                        color = if (mine)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        if (transcriptMode == TranscriptMode.READING || line.role == "assistant") {
                            SimpleMarkdownText(
                                markdown = line.text,
                                modifier = Modifier.padding(12.dp),
                                highlightQuery = ui.transcriptQuery,
                            )
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
        } // End Row
    } // End Box
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
                    onLongClickLabel = stringResource(R.string.chat_rename_agent),
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
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.chat_close_agent, tab.title),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onClose(tab.id) },
                    )
                }
            }
        }
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.chat_new_agent))
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
            currentTool == null -> stringResource(R.string.chat_working)
            currentTool.status == "RUNNING" -> stringResource(R.string.chat_running, currentTool.name)
            currentTool.status == "ERROR" -> stringResource(
                R.string.chat_failed_continuing,
                currentTool.name,
            )
            else -> stringResource(R.string.chat_done_thinking, currentTool.name)
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
