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

package com.hermesgadget.talaria.feature.manage.system

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.domain.model.OpsActionResponse
import com.hermesgadget.talaria.domain.model.OpsHookCreateRequest
import com.hermesgadget.talaria.domain.model.OpsHookEntry
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold

@Composable
fun SystemScreen() {
    val context = LocalContext.current
    val vm: SystemViewModel = viewModel(factory = SystemViewModel.factory())
    val ui by vm.ui.collectAsStateWithLifecycle()

    var importConfirmation by remember { mutableStateOf<ImportUiState.Ready?>(null) }
    var deleteConfirmation by remember { mutableStateOf<OpsHookEntry?>(null) }
    var hookEvent by remember { mutableStateOf("") }
    var hookCommand by remember { mutableStateOf("") }
    var hookMatcher by remember { mutableStateOf("") }
    var hookTimeout by remember { mutableStateOf("") }
    var approveHook by remember { mutableStateOf(true) }
    var rawExpanded by remember { mutableStateOf(true) }

    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            vm.selectImport(uri, displayName(context, uri), context.contentResolver)
        }
    }

    LaunchedEffect(ui.shareRequest) {
        val request = ui.shareRequest ?: return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = request.mimeType
            putExtra(Intent.EXTRA_STREAM, request.uri)
            putExtra(Intent.EXTRA_SUBJECT, request.subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, request.chooserTitle))
        vm.consumeShareRequest()
    }

    if (importConfirmation != null) {
        val pending = importConfirmation!!
        AlertDialog(
            onDismissRequest = { importConfirmation = null },
            title = { Text("Restore Hermes backup?") },
            text = {
                Text(
                    "${pending.displayName} will overwrite the current Hermes configuration, sessions, and data. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    importConfirmation = null
                    vm.confirmImport()
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { importConfirmation = null }) { Text("Cancel") }
            },
        )
    }

    if (deleteConfirmation != null) {
        val hook = deleteConfirmation!!
        AlertDialog(
            onDismissRequest = { deleteConfirmation = null },
            title = { Text("Delete hook?") },
            text = { Text("Remove the ${hook.event} shell hook from Hermes configuration?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirmation = null
                    vm.deleteHook(hook)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmation = null }) { Text("Cancel") }
            },
        )
    }

    ScreenScaffold("System", "Host stats & gateway ops", actions = {
        TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
    }) {
        when {
            ui.loading && ui.stats == null -> LoadingBox()
            ui.error != null && ui.stats == null -> ErrorBox(ui.error!!, onRetry = { vm.refresh() })
            else -> {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    ui.stats?.let { stats ->
                        Section("Host") {
                            Text("OS: ${stats.os ?: "—"}")
                            Text("Host: ${stats.hostname ?: "—"}")
                            Text("Python: ${stats.python_version ?: stats.python ?: "—"}")
                            Text("Hermes: ${stats.hermes_version ?: "—"}")
                            Text("CPU %: ${stats.cpu_percent ?: "—"}")
                            stats.memory?.let { Text("Memory: $it") }
                            stats.disk?.let { Text("Disk: $it") }
                            stats.uptime?.let { Text("Uptime: $it") }
                                ?: stats.uptime_seconds?.let { Text("Uptime: ${formatUptime(it)}") }
                        }
                    }

                    Section("Gateway") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !ui.busy, onClick = { vm.runGateway("start") }) { Text("Start") }
                            Button(enabled = !ui.busy, onClick = { vm.runGateway("stop") }) { Text("Stop") }
                            Button(enabled = !ui.busy, onClick = { vm.runGateway("restart") }) { Text("Restart") }
                        }
                    }

                    Section("Doctor") {
                        OutlinedButton(onClick = { vm.runDoctor() }) { Text("Run doctor") }
                        ui.doctor?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }

                    Section("Security audit") {
                        OutlinedButton(onClick = { vm.runSecurityAudit() }) { Text("Run audit") }
                        ui.audit?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }

                    Section("Backup") {
                        OutlinedButton(onClick = { vm.runBackup() }) { Text("Run backup") }
                        ui.backup?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(
                            enabled = ui.backupDownload !is BackupDownloadUiState.Running,
                            onClick = { vm.downloadAndShareBackup() },
                        ) { Text("Create & share backup") }
                        when (val state = ui.backupDownload) {
                            BackupDownloadUiState.Idle -> Unit
                            BackupDownloadUiState.Running -> Text("Creating and downloading backup…")
                            is BackupDownloadUiState.Complete -> {
                                Text(
                                    "Downloaded ${formatBytes(state.bytes)} and opened the share sheet",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            is BackupDownloadUiState.Failed -> Text(
                                state.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    Section("Import backup") {
                        Text(
                            "Select a Hermes backup ZIP or JSON export. The server may require a full backup ZIP for restore.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                enabled = ui.importState !is ImportUiState.Preparing && ui.importState !is ImportUiState.Running,
                                onClick = {
                                    importFileLauncher.launch(arrayOf("application/json", "application/zip", "application/octet-stream"))
                                },
                            ) { Text("Choose file") }
                            when (val state = ui.importState) {
                                ImportUiState.Idle -> Text("No file selected", modifier = Modifier.padding(top = 12.dp))
                                is ImportUiState.Preparing -> Text("Reading ${state.displayName}…", modifier = Modifier.padding(top = 12.dp))
                                is ImportUiState.Ready -> Text(
                                    "${state.displayName} · ${formatBytes(state.file.length())}",
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                                is ImportUiState.Running -> Text("Restoring ${state.displayName}…", modifier = Modifier.padding(top = 12.dp))
                                is ImportUiState.Complete -> Text(
                                    formatOpsAction(state.response),
                                    modifier = Modifier.padding(top = 12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                is ImportUiState.Failed -> Text(
                                    state.message,
                                    modifier = Modifier.padding(top = 12.dp),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        val pending = ui.importState as? ImportUiState.Ready
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = pending != null,
                                onClick = { importConfirmation = pending },
                            ) { Text("Restore selected") }
                            if (pending != null || ui.importState is ImportUiState.Failed || ui.importState is ImportUiState.Complete) {
                                TextButton(onClick = { vm.cancelImport() }) { Text("Clear") }
                            }
                        }
                    }

                    Section("Hooks") {
                        when (val state = ui.hooks) {
                            HooksUiState.Loading -> Text("Loading hooks…")
                            is HooksUiState.Failed -> {
                                Text(state.message, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { vm.refreshHooks() }) { Text("Retry") }
                            }
                            is HooksUiState.Ready -> {
                                if (state.response.hooks.isEmpty()) {
                                    Text("No shell hooks configured")
                                } else {
                                    state.response.hooks.forEach { hook ->
                                        HookRow(
                                            hook = hook,
                                            enabled = !ui.hooksBusy,
                                            onDelete = { deleteConfirmation = hook },
                                        )
                                    }
                                }
                                Text(
                                    "Valid events: ${state.response.validEvents.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        OutlinedTextField(
                            value = hookEvent,
                            onValueChange = { hookEvent = it },
                            label = { Text("Event") },
                            supportingText = { Text("Example: on_session_start") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = hookCommand,
                            onValueChange = { hookCommand = it },
                            label = { Text("Command") },
                            supportingText = { Text("Shell command executed by Hermes") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = hookMatcher,
                            onValueChange = { hookMatcher = it },
                            label = { Text("Matcher (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = hookTimeout,
                            onValueChange = { hookTimeout = it },
                            label = { Text("Timeout seconds (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Approve hook", modifier = Modifier.padding(top = 12.dp))
                            Switch(checked = approveHook, onCheckedChange = { approveHook = it })
                        }
                        if (hookTimeout.isNotBlank() && hookTimeout.toIntOrNull() == null) {
                            Text("Timeout must be a whole number", color = MaterialTheme.colorScheme.error)
                        }
                        Button(
                            enabled = !ui.hooksBusy &&
                                hookEvent.isNotBlank() &&
                                hookCommand.isNotBlank() &&
                                (hookTimeout.isBlank() || hookTimeout.toIntOrNull() != null),
                            onClick = {
                                vm.createHook(
                                    OpsHookCreateRequest(
                                        event = hookEvent.trim(),
                                        command = hookCommand.trim(),
                                        matcher = hookMatcher.trim().takeIf { it.isNotEmpty() },
                                        timeout = hookTimeout.trim().takeIf { it.isNotEmpty() }?.toIntOrNull(),
                                        approve = approveHook,
                                    ),
                                )
                            },
                        ) { Text(if (ui.hooksBusy) "Saving…" else "Add hook") }
                        ui.hooksMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }

                    Section("Debug share") {
                        Text(
                            "Capture a redacted report and logs, then share the generated output file and URLs.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            enabled = ui.debugShare !is DebugShareUiState.Running,
                            onClick = { vm.createDebugShare() },
                        ) { Text(if (ui.debugShare is DebugShareUiState.Running) "Capturing…" else "Capture & share") }
                        when (val state = ui.debugShare) {
                            DebugShareUiState.Idle, DebugShareUiState.Running -> Unit
                            is DebugShareUiState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
                            is DebugShareUiState.Complete -> {
                                Text(
                                    "Redacted: ${state.response.redacted}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                state.response.urls.toSortedMap().forEach { (label, url) ->
                                    Text("$label: $url", style = MaterialTheme.typography.bodySmall)
                                }
                                state.response.failures.forEach { failure ->
                                    Text("Failure: $failure", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    Section("Raw YAML config") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Raw YAML", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { rawExpanded = !rawExpanded }) {
                                Text(if (rawExpanded) "Hide" else "Show")
                            }
                        }
                        if (rawExpanded) {
                            when (val state = ui.rawConfig) {
                                RawConfigUiState.Loading -> Text("Loading raw config…")
                                is RawConfigUiState.Unsupported -> Text(state.message)
                                is RawConfigUiState.Failed -> {
                                    Text(state.message, color = MaterialTheme.colorScheme.error)
                                    TextButton(onClick = { vm.refreshRawConfig() }) { Text("Retry") }
                                }
                                is RawConfigUiState.Ready -> {
                                    state.path?.let {
                                        Text("Path: $it", style = MaterialTheme.typography.bodySmall)
                                    }
                                    OutlinedTextField(
                                        value = state.yaml,
                                        onValueChange = vm::updateRawConfig,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 280.dp),
                                        minLines = 16,
                                        label = { Text("config.yaml") },
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            enabled = !state.saving && state.yaml != state.savedYaml,
                                            onClick = { vm.saveRawConfig() },
                                        ) { Text(if (state.saving) "Saving…" else "Save YAML") }
                                        OutlinedButton(
                                            enabled = !state.saving,
                                            onClick = { vm.refreshRawConfig() },
                                        ) { Text("Reload") }
                                    }
                                    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }

                    Section("Update check") {
                        OutlinedButton(onClick = { vm.checkUpdate() }) { Text("Check for updates") }
                        ui.update?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }

                    Section("Portal") {
                        OutlinedButton(onClick = { vm.refreshPortal() }) { Text("Refresh portal") }
                        ui.portal?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    // Memory & Curator now have dedicated structured screens
                    // (Manage → System group), not raw-JSON sections here.
                }
            }
        }
    }
}

@Composable
private fun HookRow(hook: OpsHookEntry, enabled: Boolean, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(hook.event, style = MaterialTheme.typography.titleSmall)
                TextButton(enabled = enabled, onClick = onDelete) { Text("Delete") }
            }
            hook.command?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                listOfNotNull(
                    hook.matcher?.let { "matcher=$it" },
                    hook.timeout?.let { "timeout=${it}s" },
                    hook.allowed?.let { if (it) "approved" else "not approved" },
                    hook.executable?.let { if (it) "executable" else "not executable" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "import.json"
}

private fun formatOpsAction(response: OpsActionResponse): String = buildString {
    append(if (response.ok) "Completed" else "Failed")
    response.name?.let { append(" · $it") }
    response.archive?.let { append("\nArchive: $it") }
    response.message?.let { append("\n$it") }
    response.error?.let { append("\n$it") }
    response.uploadedBytes?.let { append("\nUploaded: ${formatBytes(it)}") }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GiB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MiB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

private fun formatUptime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return listOfNotNull(
        days.takeIf { it > 0 }?.let { "${it}d" },
        hours.takeIf { it > 0 }?.let { "${it}h" },
        "${minutes}m",
    ).joinToString(" ")
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            content()
        }
    }
}
