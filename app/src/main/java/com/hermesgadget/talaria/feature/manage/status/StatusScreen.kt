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
package com.hermesgadget.talaria.feature.manage.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.domain.model.effectiveManagementProfile
import com.hermesgadget.talaria.domain.model.SessionSummary
import com.hermesgadget.talaria.domain.model.StatusResponse
import com.hermesgadget.talaria.core.util.formatHermesTimestamp
import com.hermesgadget.talaria.ui.components.CollapsibleSection
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.PollEffect
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import com.hermesgadget.talaria.core.util.suspendResult

internal data class ComputerUseCheck(
    val label: String,
    val status: String,
    val message: String,
)

internal data class ComputerUseStatus(
    val platform: String? = null,
    val platformSupported: Boolean? = null,
    val installed: Boolean? = null,
    val version: String? = null,
    val ready: Boolean? = null,
    val canGrant: Boolean? = null,
    val accessibility: Boolean? = null,
    val screenRecording: Boolean? = null,
    val checks: List<ComputerUseCheck> = emptyList(),
    val error: String? = null,
)

internal fun parseComputerUseStatus(payload: JsonElement): ComputerUseStatus {
    val body = payload as? JsonObject ?: return ComputerUseStatus()
    return ComputerUseStatus(
        platform = body.textValue("platform"),
        platformSupported = body.booleanValue("platform_supported"),
        installed = body.booleanValue("installed"),
        version = body.textValue("version"),
        ready = body.booleanValue("ready"),
        canGrant = body.booleanValue("can_grant"),
        accessibility = body.booleanValue("accessibility"),
        screenRecording = body.booleanValue("screen_recording"),
        checks = (body["checks"] as? JsonArray).orEmpty().mapNotNull { element ->
            val check = element as? JsonObject ?: return@mapNotNull null
            ComputerUseCheck(
                label = check.textValue("label").orEmpty(),
                status = check.textValue("status").orEmpty(),
                message = check.textValue("message").orEmpty(),
            )
        },
        error = body.textValue("error"),
    )
}

private fun JsonObject.textValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.booleanValue(key: String): Boolean? =
    textValue(key)?.lowercase()?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(onOpenSession: ((String) -> Unit)? = null) {
    val container = TalariaApp.instance.container
    val repo = container.hermesRepository
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<StatusResponse?>(null) }
    var currentSessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var computerUse by remember { mutableStateOf<ComputerUseStatus?>(null) }
    var computerUseError by remember { mutableStateOf<String?>(null) }
    var computerUseLoading by remember { mutableStateOf(true) }
    var grantingComputerUse by remember { mutableStateOf(false) }
    var computerUseGrantMessage by remember { mutableStateOf<String?>(null) }
    var computerUseGrantFailed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var lastUpdatedMs by remember { mutableLongStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }
    var actionsExpanded by remember { mutableStateOf(false) }

    fun applySuccess(s: StatusResponse) {
        status = s
        error = null
        loading = false
        refreshing = false
        lastUpdatedMs = System.currentTimeMillis()
    }

    suspend fun refreshData() {
        if (computerUse == null) computerUseLoading = true
        repo.refreshStatus()
            .onSuccess { applySuccess(it) }
            .onFailure {
                error = it.message
                loading = false
                refreshing = false
            }
        repo.getSessionsPage(limit = 20)
            .onSuccess { currentSessions = it.sessions }

        suspendResult {
            val profile = container.connectionStore.activeProfile()?.effectiveManagementProfile()
            container.clientFactory.apiForActive().getComputerUseStatus(profile)
        }.onSuccess {
            computerUse = parseComputerUseStatus(it)
            computerUseError = null
            computerUseLoading = false
        }.onFailure {
            computerUseError = it.message
            computerUseLoading = false
        }
    }

    // Poll only while the screen is RESUMED so we stop hitting the network when the
    // app is backgrounded (a plain LaunchedEffect loop would keep going off-screen).
    PollEffect(intervalMs = 10_000, tick) {
        refreshData()
    }

    val lastUpdatedLabel = if (lastUpdatedMs == 0L) {
        "never"
    } else {
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(lastUpdatedMs))
    }
    val grantUnavailableMessage = stringResource(R.string.tools_status_computer_use_grant_unavailable)
    val grantErrorFallback = stringResource(R.string.tools_status_computer_use_grant_failed)
    val grantStartedMessage = stringResource(R.string.tools_status_computer_use_grant_started)

    ScreenScaffold(
        "Status",
        "Live overview · auto-refresh 10s · updated $lastUpdatedLabel",
        actions = {
            IconButton(onClick = { actionsExpanded = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.tools_status_more_actions),
                )
            }
            DropdownMenu(
                expanded = actionsExpanded,
                onDismissRequest = { actionsExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tools_status_refresh)) },
                    onClick = {
                        actionsExpanded = false
                        refreshing = true
                        tick++
                    },
                )
            }
        },
    ) {
        when {
            loading && status == null -> LoadingBox()
            error != null && status == null -> ErrorBox(error!!) {
                loading = true
                tick++
            }
            else -> {
                val s = status ?: return@ScreenScaffold
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        refreshing = true
                        scope.launch {
                            refreshData()
                        }
                    },
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            CollapsibleSection(
                                title = stringResource(R.string.tools_status_overview),
                            ) {
                                Text("Hermes ${s.version ?: "—"}", style = MaterialTheme.typography.titleMedium)
                                s.release_date?.let {
                                    Text("Released $it", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    "Auth: ${if (s.auth_required == true) "required" else "open"} · " +
                                        s.auth_providers.joinToString().ifBlank { "—" },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                s.profile?.let {
                                    Text("Profile: $it", style = MaterialTheme.typography.bodyMedium)
                                }
                                val platforms = formatPlatforms(s.gateway?.platforms ?: s.gateway_platforms)
                                if (platforms.isEmpty()) {
                                    Text("No platform info", style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    platforms.forEach { line ->
                                        Text(line, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                        item {
                            CollapsibleSection(
                                title = stringResource(R.string.tools_status_gateway),
                                collapsible = true,
                            ) {
                                val gw = s.gateway
                                // Some dashboard versions nest state, others only
                                // emit the top-level gateway_running flag.
                                val running = gw?.running ?: s.gateway_running
                                Text(
                                    if (running == true) {
                                        "Running · pid=${gw?.pid ?: s.gateway_pid ?: "?"}"
                                    } else if (running == false) {
                                        "Stopped"
                                    } else {
                                        "Unknown"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = when (running) {
                                        true -> MaterialTheme.colorScheme.primary
                                        false -> MaterialTheme.colorScheme.error
                                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                (gw?.state ?: s.gateway_state)?.let {
                                    Text("State: $it", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        item {
                            ComputerUseCard(
                                status = computerUse,
                                loading = computerUseLoading,
                                error = computerUseError,
                                granting = grantingComputerUse,
                                grantMessage = computerUseGrantMessage,
                                grantFailed = computerUseGrantFailed,
                                unavailableMessage = grantUnavailableMessage,
                                onGrant = {
                                    if (!grantingComputerUse) {
                                        grantingComputerUse = true
                                        computerUseGrantMessage = null
                                        computerUseGrantFailed = false
                                        scope.launch {
                                            suspendResult {
                                                val profile = container.connectionStore.activeProfile()
                                                    ?.effectiveManagementProfile()
                                                container.clientFactory.apiForActive()
                                                    .grantComputerUsePermissions(profile)
                                            }.onSuccess {
                                                grantingComputerUse = false
                                                computerUseGrantMessage = grantStartedMessage
                                            }.onFailure { failure ->
                                                grantingComputerUse = false
                                                computerUseGrantFailed = true
                                                computerUseGrantMessage = if (
                                                    failure is HttpException && failure.code() == 400
                                                ) {
                                                    grantUnavailableMessage
                                                } else {
                                                    failure.message ?: grantErrorFallback
                                                }
                                            }
                                        }
                                    }
                                },
                            )
                        }
                        item {
                            val recent = (if (currentSessions.isNotEmpty()) currentSessions else s.sessions).take(20)
                            CollapsibleSection(
                                title = stringResource(R.string.tools_status_recent_sessions),
                                collapsible = true,
                            ) {
                                Text(
                                    "Active sessions: ${s.active_sessions ?: "—"}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Recent (up to 20)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                                if (recent.isEmpty()) {
                                    Text(
                                        "No recent sessions",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    recent.forEach { session ->
                                        SessionRow(session, onOpenSession)
                                    }
                                }
                            }
                        }
                        error?.let {
                            item { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComputerUseCard(
    status: ComputerUseStatus?,
    loading: Boolean,
    error: String?,
    granting: Boolean,
    grantMessage: String?,
    grantFailed: Boolean,
    unavailableMessage: String,
    onGrant: () -> Unit,
) {
    CollapsibleSection(title = stringResource(R.string.tools_status_computer_use)) {
        if (loading && status == null) {
            Text(stringResource(R.string.tools_status_computer_use_loading))
        }
        status?.let { computer ->
            val readinessText = when (computer.ready) {
                true -> stringResource(R.string.tools_status_computer_use_ready)
                false -> stringResource(R.string.tools_status_computer_use_not_ready)
                null -> stringResource(R.string.tools_status_computer_use_unknown)
            }
            Text(
                stringResource(R.string.tools_status_computer_use_readiness, readinessText),
                style = MaterialTheme.typography.titleMedium,
                color = when (computer.ready) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                stringResource(
                    R.string.tools_status_computer_use_platform,
                    computer.platform ?: stringResource(R.string.tools_status_unknown_value),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            computer.version?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.tools_status_computer_use_driver, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (computer.platformSupported == false) {
                Text(
                    stringResource(R.string.tools_status_computer_use_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (computer.installed == false) {
                Text(
                    stringResource(R.string.tools_status_computer_use_driver_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (computer.checks.isNotEmpty()) {
                Text(
                    stringResource(R.string.tools_status_computer_use_doctor),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                computer.checks.forEach { check ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            listOfNotNull(
                                check.label.takeIf(String::isNotBlank),
                                check.status.takeIf(String::isNotBlank),
                            ).joinToString(" · ").ifBlank {
                                stringResource(R.string.tools_status_unknown_value)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        check.message.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            when {
                computer.canGrant == true -> {
                    OutlinedButton(onClick = onGrant, enabled = !granting) {
                        Text(
                            if (granting) {
                                stringResource(R.string.tools_status_computer_use_granting)
                            } else {
                                stringResource(R.string.tools_status_computer_use_grant)
                            },
                        )
                    }
                }
                computer.canGrant == false && computer.platform != "darwin" -> {
                    Text(unavailableMessage, style = MaterialTheme.typography.bodySmall)
                }
            }
            grantMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (grantFailed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            computer.error?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
        error?.let {
            Text(
                it.ifBlank { stringResource(R.string.tools_status_computer_use_unavailable) },
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary, onOpenSession: ((String) -> Unit)?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onOpenSession != null) {
                    Modifier.clickable { onOpenSession(session.id) }
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                session.title ?: session.preview ?: session.id,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                listOfNotNull(
                    session.source,
                    session.model,
                    session.message_count?.let { "$it msgs" },
                    formatTokens(session.tokens, session.input_tokens, session.output_tokens),
                    formatHermesTimestamp(session.last_active),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            session.preview?.takeIf { it.isNotBlank() && it != session.title }?.let {
                Text(
                    it.take(160),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

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

private fun formatPlatforms(platforms: kotlinx.serialization.json.JsonElement?): List<String> {
    if (platforms == null) return emptyList()
    return when (platforms) {
        is JsonArray -> platforms.map { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull ?: el.toString()
                is JsonObject -> {
                    val name = el["name"]?.jsonPrimitive?.contentOrNull
                        ?: el["id"]?.jsonPrimitive?.contentOrNull
                        ?: el.keys.firstOrNull()
                    val state = el["state"]?.jsonPrimitive?.contentOrNull
                        ?: el["enabled"]?.toString()
                    listOfNotNull(name, state).joinToString(": ")
                }
                else -> el.toString()
            }
        }
        is JsonObject -> platforms.entries.map { (k, v) ->
            when (v) {
                is JsonPrimitive -> "$k: ${v.contentOrNull ?: v}"
                is JsonObject -> {
                    val state = v["state"]?.jsonPrimitive?.contentOrNull
                        ?: v["enabled"]?.toString()
                        ?: v.toString()
                    "$k: $state"
                }
                else -> "$k: $v"
            }
        }
        is JsonPrimitive -> listOf(platforms.contentOrNull ?: platforms.toString())
    }
}
