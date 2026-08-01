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
package com.nousresearch.talaria.feature.manage.mcp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.McpCatalogEntry
import com.nousresearch.talaria.domain.model.McpServer
import com.nousresearch.talaria.ui.components.ErrorBox
import com.nousresearch.talaria.ui.components.LoadingBox
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException

@Composable
fun McpScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    var list by remember { mutableStateOf<List<McpServer>?>(null) }
    var catalog by remember { mutableStateOf<List<McpCatalogEntry>?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var env by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf("none") }
    var bearerToken by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var authenticating by remember { mutableStateOf<String?>(null) }
    var installTarget by remember { mutableStateOf<McpCatalogEntry?>(null) }
    var catalogEnv by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var catalogBusy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    fun reload() = scope.launch {
        repo.getMcp()
            .onSuccess {
                list = it
                error = null
            }
            .onFailure { error = it.message }
    }

    fun reloadCatalog() = scope.launch {
        repo.getMcpCatalog()
            .onSuccess {
                catalog = it
                error = null
            }
            .onFailure { error = it.message }
    }

    fun authenticate(serverName: String) = scope.launch {
        authenticating = serverName
        testResult = null
        try {
            val started = repo.startMcpOAuth(serverName).getOrThrow()
            check(started.status != "error") { started.error ?: "OAuth failed to start" }
            val authorizationUrl = started.authorization_url
                ?: error("OAuth server did not provide an authorization URL")
            val scheme = android.net.Uri.parse(authorizationUrl).scheme?.lowercase()
            check(scheme == "https" || scheme == "http") { "OAuth returned an unsafe browser URL" }
            uriHandler.openUri(authorizationUrl)
            val completed = withTimeout(5 * 60_000L) {
                var flow = started
                while (flow.status !in setOf("approved", "error")) {
                    delay(1_000)
                    flow = repo.getMcpOAuthFlow(started.flow_id).getOrThrow()
                }
                flow
            }
            check(completed.status == "approved") { completed.error ?: "OAuth authorization failed" }
            testResult = "$serverName authenticated · ${completed.tools.size} tools"
            reload()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            testResult = "$serverName: ${failure.message ?: "OAuth authorization failed"}"
        } finally {
            authenticating = null
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete MCP server?") },
            text = { Text("Remove '$target' from the active Hermes profile?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        repo.deleteMcpServer(target)
                            .onSuccess { reload() }
                            .onFailure { error = it.message }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
    installTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (catalogBusy == null) installTarget = null },
            title = { Text("Install ${target.name}?") },
            text = {
                Column {
                    Text(target.description)
                    Text(
                        listOfNotNull(
                            target.transport.takeIf(String::isNotBlank),
                            target.source.takeIf(String::isNotBlank),
                            target.auth_type.takeIf { it != "none" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    (target.url ?: target.command)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    if (target.bootstrap.isNotEmpty()) {
                        Text(
                            "Bootstrap: ${target.bootstrap.joinToString(" && ")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    target.required_env.forEach { variable ->
                        OutlinedTextField(
                            value = catalogEnv[variable.name].orEmpty(),
                            onValueChange = { value -> catalogEnv = catalogEnv + (variable.name to value) },
                            label = { Text(variable.prompt.ifBlank { variable.name }) },
                            supportingText = { Text(variable.name) },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    if (target.post_install.isNotBlank()) {
                        Text(target.post_install, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                val hasRequiredValues = target.required_env
                    .filter { it.required }
                    .all { catalogEnv[it.name].orEmpty().isNotBlank() }
                TextButton(
                    enabled = catalogBusy == null && hasRequiredValues,
                    onClick = {
                        catalogBusy = target.name
                        scope.launch {
                            repo.installMcpCatalogEntry(target.name, catalogEnv).fold(
                                onSuccess = { status ->
                                    testResult = status?.lines?.lastOrNull()
                                        ?: "Installed and enabled ${target.name}"
                                    installTarget = null
                                    catalogEnv = emptyMap()
                                    catalogBusy = null
                                    reload()
                                    reloadCatalog()
                                },
                                onFailure = { failure ->
                                    testResult = "${target.name}: ${failure.message ?: "Install failed"}"
                                    catalogBusy = null
                                },
                            )
                        }
                    },
                ) { Text(if (catalogBusy == target.name) "Installing…" else "Install") }
            },
            dismissButton = {
                TextButton(
                    onClick = { installTarget = null; catalogEnv = emptyMap() },
                    enabled = catalogBusy == null,
                ) { Text("Cancel") }
            },
        )
    }
    LaunchedEffect(Unit) {
        reload()
        reloadCatalog()
    }

    ScreenScaffold("MCP", "Model Context Protocol servers", actions = {
        TextButton(onClick = { if (tab == 0) reload() else reloadCatalog() }) { Text("Refresh") }
    }) {
        when {
            list == null && error == null -> LoadingBox()
            error != null && list == null -> ErrorBox(error!!, onRetry = { reload() })
            else -> {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Servers") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Catalog") })
                }
                if (tab == 0) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Server name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Command (stdio)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL (HTTP/SSE)") },
                    supportingText = { Text("Provide exactly one of command or URL") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (command.isNotBlank()) {
                    OutlinedTextField(
                        value = args,
                        onValueChange = { args = it },
                        label = { Text("Arguments (one per line)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = env,
                        onValueChange = { env = it },
                        label = { Text("Environment (KEY=value, one per line)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (url.isNotBlank()) {
                    Row {
                        listOf("none", "header", "oauth").forEach { option ->
                            FilterChip(
                                selected = auth == option,
                                onClick = { auth = option },
                                label = { Text(option) },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                    if (auth == "header") {
                        OutlinedTextField(
                            value = bearerToken,
                            onValueChange = { bearerToken = it },
                            label = { Text("Bearer token") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Button(onClick = {
                    scope.launch {
                        val parsedEnv = env.lineSequence().mapNotNull { line ->
                            val trimmed = line.trim()
                            if (!trimmed.contains('=')) null
                            else trimmed.substringBefore('=').trim().takeIf { it.isNotEmpty() }
                                ?.let { it to trimmed.substringAfter('=').trim() }
                        }.toMap()
                        repo.addMcpServer(
                            name = name.trim(),
                            command = command.trim(),
                            url = url.trim(),
                            args = args.lines().map(String::trim).filter(String::isNotEmpty),
                            env = parsedEnv,
                            auth = auth,
                            bearerToken = bearerToken,
                        )
                            .onSuccess {
                                name = ""
                                command = ""
                                url = ""
                                args = ""
                                env = ""
                                auth = "none"
                                bearerToken = ""
                                reload()
                            }
                            .onFailure { error = it.message }
                    }
                }, enabled = name.isNotBlank() && (command.isNotBlank() xor url.isNotBlank())) {
                    Text("Add server")
                }
                testResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                LazyColumn {
                    items(list.orEmpty(), key = { it.name }) { s ->
                        Surface(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(s.name, style = MaterialTheme.typography.titleLarge)
                                        Text("${s.transport ?: "—"} · ${s.url ?: s.command ?: ""}")
                                        if (s.args.isNotEmpty()) Text("args: ${s.args.joinToString(" ")}")
                                        if (s.env.isNotEmpty()) Text("env: ${s.env.entries.joinToString { "${it.key}=${it.value}" }}")
                                        s.auth?.let { Text("auth: $it") }
                                        Text(
                                            "tools: ${s.tools?.joinToString().orEmpty()}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Switch(
                                        checked = s.enabled == true,
                                        onCheckedChange = { enabled ->
                                            scope.launch {
                                                repo.setMcpEnabled(s.name, enabled)
                                                    .onSuccess { reload() }
                                                    .onFailure { error = it.message }
                                            }
                                        },
                                    )
                                }
                                Row {
                                    TextButton(onClick = {
                                        scope.launch {
                                            repo.testMcp(s.name)
                                                .onSuccess { testResult = "${s.name}: $it" }
                                                .onFailure { testResult = "${s.name}: ${it.message}" }
                                        }
                                    }) { Text("Test") }
                                    if (s.url != null && s.auth == "oauth") {
                                        TextButton(
                                            onClick = { authenticate(s.name) },
                                            enabled = authenticating == null,
                                        ) {
                                            Text(if (authenticating == s.name) "Waiting for OAuth…" else "Authenticate")
                                        }
                                    }
                                    TextButton(onClick = { deleteTarget = s.name }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
                } else {
                    testResult?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (catalog == null) {
                        LoadingBox()
                    } else {
                        LazyColumn {
                            items(catalog.orEmpty(), key = { it.name }) { entry ->
                                Surface(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                                Text(entry.description, style = MaterialTheme.typography.bodySmall)
                                                Text(
                                                    listOfNotNull(
                                                        entry.transport.takeIf(String::isNotBlank),
                                                        entry.source.takeIf(String::isNotBlank),
                                                        entry.auth_type.takeIf { it != "none" },
                                                    ).joinToString(" · "),
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                            Text(
                                                when {
                                                    entry.enabled -> "Enabled"
                                                    entry.installed -> "Installed"
                                                    else -> "Available"
                                                },
                                                color = if (entry.enabled) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        }
                                        val target = entry.url ?: entry.command
                                        target?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                        if (entry.required_env.isNotEmpty()) {
                                            Text(
                                                "Requires: ${entry.required_env.joinToString { it.name }}",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        TextButton(
                                            enabled = !entry.installed && catalogBusy == null,
                                            onClick = {
                                                catalogEnv = emptyMap()
                                                installTarget = entry
                                            },
                                        ) { Text(if (entry.installed) "Installed" else "Review & install") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
