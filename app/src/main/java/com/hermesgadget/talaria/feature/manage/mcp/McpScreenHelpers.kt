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

internal fun parseMcpArgs(raw: String): List<String> =
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
internal fun validateMcpOAuthAuthorizationUrl(raw: String): android.net.Uri {
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

internal fun isLoopbackHost(host: String): Boolean =
    host.trim('[', ']').lowercase(Locale.ROOT) in setOf(
        "localhost",
        "127.0.0.1",
        "::1",
        "0:0:0:0:0:0:0:1",
    )

internal fun parseMcpEnv(raw: String): Map<String, String> =
    raw.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        if (!trimmed.contains('=')) {
            null
        } else {
            trimmed.substringBefore('=').trim().takeIf { it.isNotEmpty() }
                ?.let { it to trimmed.substringAfter('=').trim() }
        }
    }.toMap()

internal fun normalizeBearerToken(token: String): String {
    val trimmed = token.trim()
    return if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
        trimmed.substring(7).trim()
    } else {
        trimmed
    }
}

internal fun mcpBearerEnvKey(name: String): String {
    val suffix = name
        .uppercase(Locale.ROOT)
        .replace(Regex("[^A-Za-z0-9_]"), "_")
        .trim('_')
    return "MCP_${suffix}_API_KEY"
}

internal fun buildEditedMcpServerConfig(
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

internal fun mergeEditedMcpEnv(
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

internal fun mcpServerConfigFromSummary(server: McpServer): JsonObject = buildJsonObject {
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

