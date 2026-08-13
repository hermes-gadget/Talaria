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
package com.hermesgadget.talaria.feature.manage.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.domain.model.McpCatalogEntry
import com.hermesgadget.talaria.domain.model.McpServer
import com.hermesgadget.talaria.ui.components.CollapsibleSection
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import androidx.core.net.toUri
import com.hermesgadget.talaria.core.util.suspendResult

private val MCP_AUTH_MODES = setOf("none", "header", "oauth")
private val MCP_EDITABLE_CONFIG_FIELDS = setOf(
    "url",
    "command",
    "args",
    "env",
    "auth",
    "headers",
    "oauth",
)

/** Serializes this client's MCP read/merge/write transactions. */
private val mcpConfigWriteMutex = Mutex()

@Composable
fun McpScreen() {
    val container = TalariaApp.instance.container
    val repo = container.hermesRepository
    val api = container.clientFactory.api()
    val context = LocalContext.current
    // Resource templates hoisted to composition (LocalContext.getString in
    // non-composable callbacks would trip LocalContextGetResourceValueCall).
    val mcpAddSuccessTpl = stringResource(R.string.mcp_add_success)
    val mcpUpdateSuccessTpl = stringResource(R.string.mcp_update_success)
    val mcpTestResultTpl = stringResource(R.string.mcp_test_result)
    val mcpInstallSuccessTpl = stringResource(R.string.mcp_install_success)
    val mcpEditServerSectionTpl = stringResource(R.string.mcp_edit_server_section)
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

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
    var editTarget by remember { mutableStateOf<McpServer?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var authenticating by remember { mutableStateOf<String?>(null) }
    var actionMenuTarget by remember { mutableStateOf<String?>(null) }
    var formBusy by remember { mutableStateOf(false) }
    var editBaseline by remember { mutableStateOf<JsonObject?>(null) }
    var editBaselineLoading by remember { mutableStateOf(false) }
    var editGeneration by remember { mutableLongStateOf(0L) }
    var installTarget by remember { mutableStateOf<McpCatalogEntry?>(null) }
    var catalogEnv by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var catalogBusy by remember { mutableStateOf<String?>(null) }

    val oauthStartFallback = stringResource(R.string.mcp_oauth_start_failed)
    val oauthMissingUrl = stringResource(R.string.mcp_oauth_missing_url)
    val oauthAuthorizationFallback = stringResource(R.string.mcp_oauth_authorization_failed)
    val addFallback = stringResource(R.string.mcp_add_failed)
    val updateFallback = stringResource(R.string.mcp_update_failed)
    val installFallback = stringResource(R.string.mcp_install_failed)

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

    fun clearForm() {
        editGeneration += 1
        editBaseline = null
        editBaselineLoading = false
        editTarget = null
        name = ""
        command = ""
        url = ""
        args = ""
        env = ""
        auth = "none"
        bearerToken = ""
    }

    fun beginEdit(server: McpServer) {
        val requestedGeneration = editGeneration + 1
        editGeneration = requestedGeneration
        editBaseline = null
        editBaselineLoading = true
        editTarget = server
        name = server.name
        command = server.command.orEmpty()
        url = server.url.orEmpty()
        args = server.args.joinToString("\n")
        env = server.env.entries.joinToString("\n") { (key, value) -> "$key=$value" }
        auth = server.auth
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in MCP_AUTH_MODES }
            ?: "none"
        // The API redacts bearer values. An empty field means preserve the
        // existing header; entering a value provisions a replacement token.
        bearerToken = ""
        actionMenuTarget = null
        testResult = null
        error = null
        scope.launch {
            suspendResult {
                api.getConfig()["mcp_servers"] as? JsonObject
                    ?: error("Could not read the MCP configuration. Refresh and try again.")
            }.fold(
                onSuccess = { baseline ->
                    if (editGeneration == requestedGeneration && editTarget?.name == server.name) {
                        editBaseline = baseline
                        editBaselineLoading = false
                    }
                },
                onFailure = { failure ->
                    if (editGeneration == requestedGeneration && editTarget?.name == server.name) {
                        editBaselineLoading = false
                        error = failure.message ?: "Could not read the MCP configuration"
                    }
                },
            )
        }
    }

    fun authenticate(serverName: String) = scope.launch {
        authenticating = serverName
        testResult = null
        try {
            val started = repo.startMcpOAuth(serverName).getOrThrow()
            check(started.status != "error") { started.error ?: oauthStartFallback }
            val authorizationUrl = started.authorization_url
                ?: error(oauthMissingUrl)
            val authorizationUri = validateMcpOAuthAuthorizationUrl(authorizationUrl)
            uriHandler.openUri(authorizationUri.toString())
            val completed = withTimeout(5 * 60_000L) {
                var flow = started
                while (flow.status !in setOf("approved", "error")) {
                    delay(1_000)
                    flow = repo.getMcpOAuthFlow(started.flow_id).getOrThrow()
                }
                flow
            }
            check(completed.status == "approved") {
                completed.error ?: oauthAuthorizationFallback
            }
            testResult = TalariaApp.instance.resources.getQuantityString(
                R.plurals.mcp_oauth_authenticated,
                completed.tools.size,
                serverName,
                completed.tools.size,
            )
            reload()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            testResult = mcpTestResultTpl.format(serverName, failure.message ?: oauthAuthorizationFallback)
        } finally {
            authenticating = null
        }
    }

    val parsedArgs = parseMcpArgs(args)
    val parsedEnv = parseMcpEnv(env)
    val submittedName = name.trim()
    val submittedCommand = command.trim()
    val submittedUrl = url.trim()
    val submittedAuth = if (submittedUrl.isNotBlank()) auth else "none"
    val selectedAuth = editTarget
        ?.auth
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it in MCP_AUTH_MODES }
        ?: "none"
    val formChanged = editTarget?.let { target ->
        submittedName != target.name ||
            submittedCommand != target.command.orEmpty() ||
            submittedUrl != target.url.orEmpty() ||
            parsedArgs != target.args ||
            parsedEnv != target.env ||
            submittedAuth != selectedAuth ||
            bearerToken.isNotBlank()
    } ?: true
    val nameTaken = submittedName.isNotBlank() &&
        list.orEmpty().any { it.name != editTarget?.name && it.name == submittedName }
    val bearerRequired = submittedAuth == "header" &&
        bearerToken.isBlank() &&
        selectedAuth != "header"
    val editReady = editTarget == null || (!editBaselineLoading && editBaseline != null)
    val canSubmit = !formBusy &&
        submittedName.isNotBlank() &&
        (submittedCommand.isNotBlank() xor submittedUrl.isNotBlank()) &&
        !nameTaken &&
        formChanged &&
        !bearerRequired &&
        editReady

    fun submitForm() {
        if (!canSubmit) return
        val target = editTarget
        val submittedArgs = parsedArgs
        val submittedEnv = parsedEnv
        val submittedBearerToken = bearerToken
        val submittedDisplayedEnv = target?.env.orEmpty()
        formBusy = true
        error = null
        scope.launch {
            try {
                if (target == null) {
                    repo.addMcpServer(
                        name = submittedName,
                        command = submittedCommand,
                        url = submittedUrl,
                        args = submittedArgs,
                        env = submittedEnv,
                        auth = submittedAuth,
                        bearerToken = submittedBearerToken,
                    ).getOrThrow()
                    testResult = mcpAddSuccessTpl.format(submittedName)
                } else {
                    val baselineServers = editBaseline
                        ?: error("The MCP configuration was not loaded. Refresh and try again.")
                    mcpConfigWriteMutex.withLock {
                        // Hermes currently exposes only a whole-map PUT and no
                        // revision/ETag condition. Compare the edit-start
                        // snapshot with a fresh read before constructing the
                        // replacement so an observed concurrent change is
                        // rejected instead of silently clobbered. The small
                        // GET→PUT race remains until the server adds a
                        // conditional write primitive.
                        val rawConfig = api.getConfig()
                        val rawServers = (rawConfig["mcp_servers"] as? JsonObject)
                            ?: JsonObject(emptyMap())
                        val changedServers = changedMcpServerNames(baselineServers, rawServers)
                        check(changedServers.isEmpty()) {
                            "MCP configuration changed while this edit was open " +
                                "(${changedServers.sorted().joinToString()}). Refresh and retry."
                        }

                        val serverEntries = rawServers.entries
                            .associate { it.key to it.value }
                            .toMutableMap()
                        val existingConfig = (serverEntries[target.name] as? JsonObject)
                            ?: error("MCP server '${target.name}' no longer exists. Refresh and retry.")
                        val updatedConfig = buildEditedMcpServerConfig(
                            existing = existingConfig,
                            selected = target,
                            name = submittedName,
                            command = submittedCommand,
                            url = submittedUrl,
                            args = submittedArgs,
                            displayedEnv = submittedDisplayedEnv,
                            env = submittedEnv,
                            auth = submittedAuth,
                            bearerToken = submittedBearerToken,
                        )
                        serverEntries.remove(target.name)
                        check(submittedName == target.name || submittedName !in serverEntries) {
                            "An MCP server named '$submittedName' was added while this edit was open. " +
                                "Refresh and retry."
                        }
                        serverEntries[submittedName] = updatedConfig
                        val replacement = buildJsonObject {
                            serverEntries.forEach { (serverName, serverConfig) ->
                                put(serverName, serverConfig)
                            }
                        }
                        val normalizedBearer = normalizeBearerToken(submittedBearerToken)
                        if (submittedAuth == "header" && normalizedBearer.isNotBlank()) {
                            api.putEnv(
                                buildJsonObject {
                                    put("key", mcpBearerEnvKey(submittedName))
                                    put("value", normalizedBearer)
                                },
                            )
                        }
                        api.updateMcpServer(
                            buildJsonObject {
                                put("servers", replacement)
                            },
                        )
                        repo.clearCache()
                    }
                    testResult = mcpUpdateSuccessTpl.format(submittedName)
                }
                clearForm()
                reload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = failure.message ?: if (target == null) addFallback else updateFallback
            } finally {
                formBusy = false
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.mcp_delete_title)) },
            text = { Text(stringResource(R.string.mcp_delete_message, target)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            repo.deleteMcpServer(target)
                                .onSuccess { reload() }
                                .onFailure { error = it.message }
                        }
                    },
                ) { Text(stringResource(R.string.mcp_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.mcp_cancel))
                }
            },
        )
    }

    installTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (catalogBusy == null) installTarget = null },
            title = { Text(stringResource(R.string.mcp_install_title, target.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(target.description)
                    Text(
                        listOfNotNull(
                            target.transport.takeIf(String::isNotBlank),
                            target.source.takeIf(String::isNotBlank),
                            target.auth_type.takeIf { it != "none" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    (target.url ?: target.command)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    if (target.bootstrap.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.mcp_bootstrap,
                                target.bootstrap.joinToString(" && "),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    target.required_env.forEach { variable ->
                        OutlinedTextField(
                            value = catalogEnv[variable.name].orEmpty(),
                            onValueChange = { value ->
                                catalogEnv = catalogEnv + (variable.name to value)
                            },
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
                                    testResult = status?.lines?.lastOrNull() ?: mcpInstallSuccessTpl.format(target.name)
                                    installTarget = null
                                    catalogEnv = emptyMap()
                                    catalogBusy = null
                                    reload()
                                    reloadCatalog()
                                },
                                onFailure = { failure ->
                                    testResult = mcpTestResultTpl.format(target.name, failure.message ?: installFallback)
                                    catalogBusy = null
                                },
                            )
                        }
                    },
                ) {
                    Text(
                        if (catalogBusy == target.name) {
                            stringResource(R.string.mcp_installing)
                        } else {
                            stringResource(R.string.mcp_catalog_review_install)
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        installTarget = null
                        catalogEnv = emptyMap()
                    },
                    enabled = catalogBusy == null,
                ) { Text(stringResource(R.string.mcp_cancel)) }
            },
        )
    }

    LaunchedEffect(Unit) {
        reload()
        reloadCatalog()
    }

    ScreenScaffold(
        title = stringResource(R.string.mcp_screen_title),
        subtitle = stringResource(R.string.mcp_screen_subtitle),
        actions = {
            TextButton(onClick = { if (tab == 0) reload() else reloadCatalog() }) {
                Text(stringResource(R.string.mcp_refresh))
            }
        },
    ) {
        when {
            list == null && error == null -> LoadingBox()
            error != null && list == null -> ErrorBox(
                error.orEmpty(),
                onRetry = { reload() },
            )
            else -> {
                Column(Modifier.fillMaxSize()) {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            text = { Text(stringResource(R.string.mcp_tab_servers)) },
                        )
                        Tab(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            text = { Text(stringResource(R.string.mcp_tab_catalog)) },
                        )
                    }
                    if (tab == 0) {
                        val formTitle = editTarget?.let { mcpEditServerSectionTpl.format(it.name) } ?: stringResource(R.string.mcp_add_server_section)
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            testResult?.let { result ->
                                item {
                                    Text(
                                        result,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            error?.let { message ->
                                item {
                                    Text(message, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            item {
                                CollapsibleSection(
                                    title = formTitle,
                                    collapsible = editTarget == null,
                                ) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OutlinedTextField(
                                            value = name,
                                            onValueChange = { name = it },
                                            label = {
                                                Text(stringResource(R.string.mcp_server_name_label))
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                        )
                                        OutlinedTextField(
                                            value = command,
                                            onValueChange = { command = it },
                                            label = {
                                                Text(stringResource(R.string.mcp_command_label))
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        OutlinedTextField(
                                            value = url,
                                            onValueChange = { url = it },
                                            label = {
                                                Text(stringResource(R.string.mcp_url_label))
                                            },
                                            supportingText = {
                                                Text(stringResource(R.string.mcp_transport_supporting))
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        if (command.isNotBlank()) {
                                            OutlinedTextField(
                                                value = args,
                                                onValueChange = { args = it },
                                                label = {
                                                    Text(stringResource(R.string.mcp_args_label))
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            OutlinedTextField(
                                                value = env,
                                                onValueChange = { env = it },
                                                label = {
                                                    Text(stringResource(R.string.mcp_env_label))
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                        if (url.isNotBlank()) {
                                            Row {
                                                listOf(
                                                    "none" to R.string.mcp_auth_none,
                                                    "header" to R.string.mcp_auth_header,
                                                    "oauth" to R.string.mcp_auth_oauth,
                                                ).forEach { (option, label) ->
                                                    FilterChip(
                                                        selected = auth == option,
                                                        onClick = { auth = option },
                                                        label = { Text(stringResource(label)) },
                                                        modifier = Modifier.padding(end = 4.dp),
                                                    )
                                                }
                                            }
                                            if (auth == "header") {
                                                OutlinedTextField(
                                                    value = bearerToken,
                                                    onValueChange = { bearerToken = it },
                                                    label = {
                                                        Text(stringResource(R.string.mcp_bearer_token_label))
                                                    },
                                                    supportingText = if (editTarget != null) {
                                                        {
                                                            Text(
                                                                stringResource(
                                                                    R.string.mcp_bearer_token_keep,
                                                                ),
                                                            )
                                                        }
                                                    } else {
                                                        null
                                                    },
                                                    visualTransformation = PasswordVisualTransformation(),
                                                    modifier = Modifier.fillMaxWidth(),
                                                    singleLine = true,
                                                )
                                            }
                                        }
                                        if (nameTaken) {
                                            Text(
                                                stringResource(
                                                    R.string.mcp_duplicate_name,
                                                    submittedName,
                                                ),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = ::submitForm,
                                                enabled = canSubmit,
                                            ) {
                                                Text(
                                                    if (editTarget == null) {
                                                        stringResource(R.string.mcp_add_action)
                                                    } else {
                                                        stringResource(R.string.mcp_save_changes)
                                                    },
                                                )
                                            }
                                            if (editTarget != null) {
                                                OutlinedButton(onClick = ::clearForm) {
                                                    Text(stringResource(R.string.mcp_cancel_edit))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            item {
                                CollapsibleSection(
                                    title = stringResource(R.string.mcp_configured_servers),
                                    collapsible = true,
                                ) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        val servers = list.orEmpty()
                                        if (servers.isEmpty()) {
                                            Text(stringResource(R.string.mcp_no_servers))
                                        } else {
                                            servers.forEach { server ->
                                                McpServerCard(
                                                    server = server,
                                                    menuExpanded = actionMenuTarget == server.name,
                                                    authenticating = authenticating,
                                                    onMenuExpandedChange = { expanded ->
                                                        actionMenuTarget = if (expanded) {
                                                            server.name
                                                        } else {
                                                            null
                                                        }
                                                    },
                                                    onEdit = ::beginEdit,
                                                    onTest = { target ->
                                                        scope.launch {
                                                            repo.testMcp(target.name)
                                                                .onSuccess {
                                                                    testResult = mcpTestResultTpl.format(target.name, it.toString())
                                                                }
                                                                .onFailure {
                                                                    testResult = mcpTestResultTpl.format(target.name, it.message.orEmpty())
                                                                }
                                                        }
                                                    },
                                                    onAuthenticate = { target -> authenticate(target.name) },
                                                    onDelete = { target -> deleteTarget = target.name },
                                                    onEnabledChange = { enabled ->
                                                        scope.launch {
                                                            repo.setMcpEnabled(server.name, enabled)
                                                                .onSuccess { reload() }
                                                                .onFailure { error = it.message }
                                                        }
                                                    },
                                                )
                                            }
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
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 8.dp),
                            ) {
                                items(catalog.orEmpty(), key = { it.name }) { entry ->
                                    Surface(
                                        Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        shape = MaterialTheme.shapes.medium,
                                    ) {
                                        Column(
                                            Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(
                                                        entry.name,
                                                        style = MaterialTheme.typography.titleMedium,
                                                    )
                                                    Text(
                                                        entry.description,
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
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
                                                        entry.enabled -> stringResource(
                                                            R.string.mcp_catalog_enabled,
                                                        )
                                                        entry.installed -> stringResource(
                                                            R.string.mcp_catalog_installed,
                                                        )
                                                        else -> stringResource(
                                                            R.string.mcp_catalog_available,
                                                        )
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
                                            target?.let {
                                                Text(it, style = MaterialTheme.typography.bodySmall)
                                            }
                                            if (entry.required_env.isNotEmpty()) {
                                                Text(
                                                    stringResource(
                                                        R.string.mcp_catalog_requires,
                                                        entry.required_env.joinToString { it.name },
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                            TextButton(
                                                enabled = !entry.installed && catalogBusy == null,
                                                onClick = {
                                                    catalogEnv = emptyMap()
                                                    installTarget = entry
                                                },
                                            ) {
                                                Text(
                                                    if (entry.installed) {
                                                        stringResource(R.string.mcp_catalog_installed)
                                                    } else {
                                                        stringResource(
                                                            R.string.mcp_catalog_review_install,
                                                        )
                                                    },
                                                )
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
    }
}

private fun parseMcpArgs(raw: String): List<String> =
    raw.lines().map(String::trim).filter(String::isNotEmpty)

/** Return every MCP entry that changed between the edit baseline and save. */
internal fun changedMcpServerNames(
    baseline: JsonObject,
    current: JsonObject,
): Set<String> = (baseline.keys + current.keys)
    .filter { name -> baseline[name] != current[name] }
    .toSet()

/**
 * Browser navigation is a credential-bearing OAuth boundary. Remote hosts must
 * use TLS; plain HTTP is retained only for the loopback callback/authorization
 * endpoints used by local Hermes instances.
 */
private fun validateMcpOAuthAuthorizationUrl(raw: String): android.net.Uri {
    val uri = raw.toUri()
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT)
    val hostForError = host ?: "<missing-host>"
    val isLoopback = host?.let(::isLoopbackHost) == true
    val safe = host != null && when (scheme) {
        "https" -> true
        "http" -> isLoopback
        else -> false
    }
    check(safe) {
        "Rejected OAuth authorization URL (${scheme ?: "<missing-scheme>"}://$hostForError): " +
            "HTTPS is required for remote hosts; HTTP is allowed only for loopback hosts."
    }
    return uri
}

private fun isLoopbackHost(host: String): Boolean =
    host.trim('[', ']').lowercase(Locale.ROOT) in setOf(
        "localhost",
        "127.0.0.1",
        "::1",
        "0:0:0:0:0:0:0:1",
    )

private fun parseMcpEnv(raw: String): Map<String, String> =
    raw.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        if (!trimmed.contains('=')) {
            null
        } else {
            trimmed.substringBefore('=').trim().takeIf { it.isNotEmpty() }
                ?.let { it to trimmed.substringAfter('=').trim() }
        }
    }.toMap()

private fun normalizeBearerToken(token: String): String {
    val trimmed = token.trim()
    return if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
        trimmed.substring(7).trim()
    } else {
        trimmed
    }
}

private fun mcpBearerEnvKey(name: String): String {
    val suffix = name
        .uppercase(Locale.ROOT)
        .replace(Regex("[^A-Za-z0-9_]"), "_")
        .trim('_')
    return "MCP_${suffix}_API_KEY"
}

private fun buildEditedMcpServerConfig(
    existing: JsonObject,
    selected: McpServer,
    name: String,
    command: String,
    url: String,
    args: List<String>,
    displayedEnv: Map<String, String>,
    env: Map<String, String>,
    auth: String,
    bearerToken: String,
): JsonObject {
    val existingEnv = existing["env"] as? JsonObject
    val mergedEnv = mergeEditedMcpEnv(existingEnv, displayedEnv, env)
    val normalizedBearer = normalizeBearerToken(bearerToken)
    return buildJsonObject {
        existing.forEach { (key, value) ->
            if (key !in MCP_EDITABLE_CONFIG_FIELDS) {
                put(key, value)
            }
        }
        if (command.isNotBlank()) {
            put("command", command)
            if (args.isNotEmpty()) {
                put("args", JsonArray(args.map(::JsonPrimitive)))
            }
            if (mergedEnv.isNotEmpty()) {
                put("env", mergedEnv)
            }
        } else {
            put("url", url)
            when (auth) {
                "oauth" -> {
                    put("auth", "oauth")
                    existing["oauth"]?.let { put("oauth", it) }
                }
                "header" -> {
                    if (normalizedBearer.isNotBlank()) {
                        put(
                            "headers",
                            buildJsonObject {
                                put(
                                    "Authorization",
                                    "Bearer ${mcpBearerEnvKey(name)}",
                                )
                            },
                        )
                    } else {
                        existing["headers"]?.let { put("headers", it) }
                    }
                }
            }
        }
        if (selected.enabled == false && !existing.containsKey("enabled")) {
            put("enabled", false)
        }
    }
}

private fun mergeEditedMcpEnv(
    existing: JsonObject?,
    displayed: Map<String, String>,
    edited: Map<String, String>,
): JsonObject = buildJsonObject {
    existing?.forEach { (key, value) ->
        if (edited[key] == displayed[key]) {
            put(key, value)
        }
    }
    edited.forEach { (key, value) ->
        if (displayed[key] != value || existing?.containsKey(key) != true) {
            put(key, value)
        }
    }
}

private fun mcpServerConfigFromSummary(server: McpServer): JsonObject = buildJsonObject {
    server.command?.takeIf(String::isNotBlank)?.let { put("command", it) }
    server.url?.takeIf(String::isNotBlank)?.let { put("url", it) }
    if (server.args.isNotEmpty()) {
        put("args", JsonArray(server.args.map(::JsonPrimitive)))
    }
    if (server.env.isNotEmpty()) {
        put("env", buildJsonObject {
            server.env.forEach { (key, value) -> put(key, value) }
        })
    }
    if (server.auth == "oauth") {
        put("auth", "oauth")
    }
    if (server.enabled == false) {
        put("enabled", false)
    }
}

@Composable
private fun McpServerCard(
    server: McpServer,
    menuExpanded: Boolean,
    authenticating: String?,
    onMenuExpandedChange: (Boolean) -> Unit,
    onEdit: (McpServer) -> Unit,
    onTest: (McpServer) -> Unit,
    onAuthenticate: (McpServer) -> Unit,
    onDelete: (McpServer) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(server.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(
                            R.string.mcp_transport_summary,
                            server.transport ?: stringResource(R.string.mcp_not_available),
                            server.url ?: server.command
                                ?: stringResource(R.string.mcp_not_available),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (server.enabled == true) {
                            stringResource(R.string.mcp_enabled)
                        } else {
                            stringResource(R.string.mcp_disabled)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = server.enabled == true,
                    onCheckedChange = onEnabledChange,
                )
                Box {
                    IconButton(onClick = { onMenuExpandedChange(true) }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.mcp_server_actions),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { onMenuExpandedChange(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.mcp_edit_action)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Edit, contentDescription = null)
                            },
                            onClick = {
                                onMenuExpandedChange(false)
                                onEdit(server)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.mcp_test_action)) },
                            onClick = {
                                onMenuExpandedChange(false)
                                onTest(server)
                            },
                        )
                        if (server.url != null && server.auth == "oauth") {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (authenticating == server.name) {
                                            stringResource(R.string.mcp_waiting_oauth)
                                        } else {
                                            stringResource(R.string.mcp_authenticate_action)
                                        },
                                    )
                                },
                                enabled = authenticating == null,
                                onClick = {
                                    onMenuExpandedChange(false)
                                    onAuthenticate(server)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.mcp_delete_action)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Delete, contentDescription = null)
                            },
                            onClick = {
                                onMenuExpandedChange(false)
                                onDelete(server)
                            },
                        )
                    }
                }
            }
            if (server.args.isNotEmpty()) {
                Text(
                    stringResource(R.string.mcp_args_summary, server.args.joinToString(" ")),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (server.env.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.mcp_env_summary,
                        server.env.entries.joinToString { (key, value) -> "$key=$value" },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            server.auth?.takeIf(String::isNotBlank)?.let {
                Text(
                    stringResource(R.string.mcp_auth_summary, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            server.tools?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    stringResource(R.string.mcp_tools_summary, it.joinToString()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
