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

package com.hermesgadget.talaria.feature.manage.sessions

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.util.formatHermesTimestamp
import com.hermesgadget.talaria.domain.model.SessionMessage
import com.hermesgadget.talaria.domain.model.SessionSummary
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import com.hermesgadget.talaria.ui.components.SimpleMarkdownText
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun SessionDetailScreen(
    sessionId: String,
    onOpenSession: (String) -> Unit,
    onDeleted: (() -> Unit)? = null,
) {
    val repo = TalariaApp.instance.container.hermesRepository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestVm: LatestDescendantViewModel = viewModel(factory = LatestDescendantViewModel.factory())
    val latestUi by latestVm.ui.collectAsStateWithLifecycle()
    var session by remember { mutableStateOf<SessionSummary?>(null) }
    var messages by remember { mutableStateOf<List<SessionMessage>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameTitle by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmCompact by remember { mutableStateOf(false) }
    var compacting by remember { mutableStateOf(false) }
    var actionsExpanded by remember { mutableStateOf(false) }
    var expandedTools by remember { mutableStateOf(setOf<Int>()) }
    val compactionGateway = remember { HermesSessionCompactionGateway() }
    val compactFailure = stringResource(R.string.sessions_compact_failed)
    val compactedNotice = stringResource(R.string.sessions_compacted)
    val compactInProgressNotice = stringResource(R.string.sessions_compact_in_progress)
    val compactAbortedNotice = stringResource(R.string.sessions_compact_aborted)
    val exportChooser = stringResource(R.string.sessions_export_chooser)
    val exportFailure = stringResource(R.string.sessions_export_failed)

    fun reload() {
        scope.launch {
            repo.getSession(sessionId)
                .onSuccess { session = it }
                .onFailure { error = it.message }
        }
        scope.launch {
            repo.loadMessages(sessionId)
                .onSuccess { messages = it; error = null }
                .onFailure { error = it.message }
        }
    }

    LaunchedEffect(sessionId) { reload() }

    LaunchedEffect(latestUi) {
        val result = (latestUi as? LatestDescendantUiState.Success)?.response ?: return@LaunchedEffect
        if (result.changed) {
            onOpenSession(result.sessionId)
        } else {
            message = "This is already the latest descendant"
        }
        latestVm.consume()
    }

    LaunchedEffect(latestUi) {
        (latestUi as? LatestDescendantUiState.Failure)?.let { error = it.message }
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameTitle,
                    onValueChange = { renameTitle = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameOpen = false
                    scope.launch {
                        repo.renameSession(sessionId, renameTitle)
                            .onSuccess { message = "Renamed" }
                            .onFailure { error = it.message }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete session?") },
            text = { Text("This removes the session on the Hermes host.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repo.deleteSession(sessionId)
                            .onSuccess { onDeleted?.invoke() }
                            .onFailure { error = it.message }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmCompact) {
        AlertDialog(
            onDismissRequest = { if (!compacting) confirmCompact = false },
            title = { Text(stringResource(R.string.sessions_compact_title)) },
            text = { Text(stringResource(R.string.sessions_compact_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCompact = false
                        compacting = true
                        scope.launch {
                            runCatching { compactionGateway.compact(sessionId) }
                                .onSuccess { result ->
                                    message = result.message ?: when {
                                        result.lockHeld -> compactInProgressNotice
                                        result.status == "aborted" -> compactAbortedNotice
                                        else -> compactedNotice
                                    }
                                    reload()
                                }
                                .onFailure { error = it.message ?: compactFailure }
                            compacting = false
                        }
                    },
                    enabled = !compacting,
                ) { Text(stringResource(R.string.sessions_compact)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmCompact = false },
                    enabled = !compacting,
                ) { Text(stringResource(R.string.sessions_cancel)) }
            },
        )
    }

    ScreenScaffold(
        stringResource(R.string.sessions_detail_title),
        sessionId.take(24),
        actions = {
            Box {
                IconButton(onClick = { actionsExpanded = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.sessions_more_actions),
                    )
                }
                DropdownMenu(
                    expanded = actionsExpanded,
                    onDismissRequest = { actionsExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessions_rename)) },
                        onClick = {
                            actionsExpanded = false
                            renameOpen = true
                            renameTitle = session?.title.orEmpty()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessions_export)) },
                        onClick = {
                            actionsExpanded = false
                            scope.launch {
                                repo.exportSessionMarkdown(sessionId)
                                    .onSuccess { md ->
                                        runCatching {
                                            val file = withContext(Dispatchers.IO) {
                                                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                                                File(dir, safeSessionExportFilename(sessionId)).also { it.writeText(md) }
                                            }
                                            val uri: Uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.files",
                                                file,
                                            )
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/markdown"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                putExtra(Intent.EXTRA_SUBJECT, "Hermes session $sessionId")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(
                                                Intent.createChooser(
                                                    intent,
                                                    exportChooser,
                                                ),
                                            )
                                        }.onFailure {
                                            error = it.message ?: exportFailure
                                        }
                                    }
                                    .onFailure { error = it.message }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessions_compact)) },
                        onClick = {
                            actionsExpanded = false
                            confirmCompact = true
                        },
                        enabled = !compacting,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessions_open_latest)) },
                        onClick = {
                            actionsExpanded = false
                            latestVm.load(sessionId)
                        },
                        enabled = latestUi !is LatestDescendantUiState.Loading,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessions_delete)) },
                        onClick = {
                            actionsExpanded = false
                            confirmDelete = true
                        },
                    )
                }
            }
        },
    ) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        session?.let { s -> SessionHeaderCard(s) }
        LazyColumn {
            itemsIndexed(messages, key = { idx, _ -> "$sessionId-$idx" }) { idx, m ->
                val roleColor = roleColor(m.role)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            m.role ?: "?",
                            style = MaterialTheme.typography.labelLarge,
                            color = roleColor,
                        )
                        SimpleMarkdownText(m.content.orEmpty(), modifier = Modifier.padding(top = 4.dp))
                        if (m.tool_calls != null) {
                            val open = idx in expandedTools
                            Text(
                                if (open) "Hide tool JSON ▴" else "Show tool JSON ▾",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .clickable {
                                        expandedTools = if (open) {
                                            expandedTools - idx
                                        } else {
                                            expandedTools + idx
                                        }
                                    },
                            )
                            AnimatedVisibility(visible = open) {
                                Text(
                                    m.tool_calls.toString(),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun safeSessionExportFilename(sessionId: String): String {
    val stem = sessionId
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('.', '_', '-')
        .take(72)
        .ifBlank { "export" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(sessionId.toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { "%02x".format(it) }
    return "session-$stem-$digest.md"
}

@Composable
private fun roleColor(role: String?): Color {
    return when (role?.lowercase()) {
        "user", "human" -> MaterialTheme.colorScheme.primary
        "assistant", "model", "ai" -> MaterialTheme.colorScheme.tertiary
        "system" -> MaterialTheme.colorScheme.secondary
        "tool", "function" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * Summary header: model · source · message/tool counts · token accounting ·
 * last activity, plus a LIVE chip when the dashboard reports the session running.
 */
@Composable
private fun SessionHeaderCard(s: SessionSummary) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.title ?: s.preview ?: "Session",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (s.live == true || s.is_active == true) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            "● LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Text(
                listOfNotNull(
                    s.source?.let { "source: $it" },
                    s.model?.let { "model: $it" },
                    s.message_count?.let { "$it msgs" },
                    s.tool_call_count?.let { "$it tool calls" },
                    formatTokens(s.tokens, s.input_tokens, s.output_tokens),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s.last_active?.let {
                Text("last active: ${formatHermesTimestamp(it)}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Compact token accounting for the header; mirrors the Status screen renderer. */
private fun formatTokens(
    tokens: kotlinx.serialization.json.JsonElement?,
    topInput: Long? = null,
    topOutput: Long? = null,
): String? {
    if (tokens == null) {
        return if (topInput != null || topOutput != null) "tok ${topInput ?: "?"}→${topOutput ?: "?"}" else null
    }
    return when (tokens) {
        is JsonPrimitive -> tokens.contentOrNull?.let { "tok $it" }
        is JsonObject -> {
            val input = tokens["input"]?.jsonPrimitive?.contentOrNull
                ?: tokens["prompt"]?.jsonPrimitive?.contentOrNull
            val output = tokens["output"]?.jsonPrimitive?.contentOrNull
                ?: tokens["completion"]?.jsonPrimitive?.contentOrNull
            val total = tokens["total"]?.jsonPrimitive?.contentOrNull
            when {
                total != null -> "tok $total"
                input != null || output != null -> "tok ${input ?: "?"}→${output ?: "?"}"
                else -> null
            }
        }
        else -> null
    }
}
