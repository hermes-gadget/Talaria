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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.domain.model.SystemStats
import com.hermesgadget.talaria.domain.model.ActionStatus
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

@Composable
fun SystemScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    var stats by remember { mutableStateOf<SystemStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var doctor by remember { mutableStateOf<String?>(null) }
    var audit by remember { mutableStateOf<String?>(null) }
    var backup by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<String?>(null) }
    var portal by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reloadStats() = scope.launch {
        loading = true
        repo.getSystemStats()
            .onSuccess {
                stats = it
                error = null
                loading = false
            }
            .onFailure {
                error = it.message
                loading = false
            }
    }

    LaunchedEffect(Unit) {
        reloadStats()
        repo.getPortal().onSuccess { portal = pretty(it) }
    }

    ScreenScaffold("System", "Host stats & gateway ops", actions = {
        TextButton(onClick = { reloadStats() }) { Text("Refresh") }
    }) {
        when {
            loading && stats == null -> LoadingBox()
            error != null && stats == null -> ErrorBox(error!!, onRetry = { reloadStats() })
            else -> {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val s = stats
                    if (s != null) {
                        Section("Host") {
                            Text("OS: ${s.os ?: "—"}")
                            Text("Host: ${s.hostname ?: "—"}")
                            Text("Python: ${s.python_version ?: s.python ?: "—"}")
                            Text("Hermes: ${s.hermes_version ?: "—"}")
                            Text("CPU %: ${s.cpu_percent ?: "—"}")
                            s.memory?.let { Text("Memory: $it") }
                            s.disk?.let { Text("Disk: $it") }
                            s.uptime?.let { Text("Uptime: $it") }
                                ?: s.uptime_seconds?.let { Text("Uptime: ${formatUptime(it)}") }
                        }
                    }

                    Section("Gateway") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        repo.gateway("start")
                                        busy = false
                                        reloadStats()
                                    }
                                },
                            ) { Text("Start") }
                            Button(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        repo.gateway("stop")
                                        busy = false
                                        reloadStats()
                                    }
                                },
                            ) { Text("Stop") }
                            Button(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        repo.gateway("restart")
                                        busy = false
                                        reloadStats()
                                    }
                                },
                            ) { Text("Restart") }
                        }
                    }

                    Section("Doctor") {
                        OutlinedButton(onClick = {
                            scope.launch {
                                repo.runDoctorToCompletion()
                                    .onSuccess { doctor = formatAction(it) }
                                    .onFailure { doctor = it.message }
                            }
                        }) { Text("Run doctor") }
                        doctor?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }

                    Section("Security audit") {
                        OutlinedButton(onClick = {
                            scope.launch {
                                repo.runSecurityAuditToCompletion()
                                    .onSuccess { audit = formatAction(it) }
                                    .onFailure { audit = it.message }
                            }
                        }) { Text("Run audit") }
                        audit?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }

                    Section("Backup") {
                        OutlinedButton(onClick = {
                            scope.launch {
                                repo.runBackupToCompletion()
                                    .onSuccess { backup = formatAction(it) }
                                    .onFailure { backup = it.message }
                            }
                        }) { Text("Run backup") }
                        backup?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }

                    Section("Update check") {
                        OutlinedButton(onClick = {
                            scope.launch {
                                repo.checkUpdate()
                                    .onSuccess { update = pretty(it) }
                                    .onFailure { update = it.message }
                            }
                        }) { Text("Check for updates") }
                        update?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }

                    Section("Portal") {
                        OutlinedButton(onClick = {
                            scope.launch {
                                repo.getPortal()
                                    .onSuccess { portal = pretty(it) }
                                    .onFailure { portal = it.message }
                            }
                        }) { Text("Refresh portal") }
                        portal?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    // Memory & Curator now have dedicated structured screens
                    // (Manage → System group), not raw-JSON sections here.
                }
            }
        }
    }
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

private fun pretty(el: JsonElement): String {
    val raw = el.toString()
    return if (raw.length > 2_000) raw.take(2_000) + "…" else raw
}

private fun formatAction(action: ActionStatus): String = buildString {
    append(if (action.exit_code == 0) "Completed" else "Exited ${action.exit_code ?: "?"}")
    if (action.lines.isNotEmpty()) {
        append('\n')
        append(action.lines.joinToString("\n").takeLast(4_000))
    }
}
