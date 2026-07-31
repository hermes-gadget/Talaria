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
package com.nousresearch.talaria.feature.manage.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.SessionSummary
import com.nousresearch.talaria.domain.model.StatusResponse
import com.nousresearch.talaria.ui.components.ErrorBox
import com.nousresearch.talaria.ui.components.LoadingBox
import com.nousresearch.talaria.ui.components.ScreenScaffold
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(onOpenSession: ((String) -> Unit)? = null) {
    val repo = TalariaApp.instance.container.hermesRepository
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<StatusResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var lastUpdatedMs by remember { mutableLongStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }

    fun applySuccess(s: StatusResponse) {
        status = s
        error = null
        loading = false
        refreshing = false
        lastUpdatedMs = System.currentTimeMillis()
    }

    LaunchedEffect(tick) {
        while (isActive) {
            repo.refreshStatus()
                .onSuccess { applySuccess(it) }
                .onFailure {
                    error = it.message
                    loading = false
                    refreshing = false
                }
            delay(5_000)
        }
    }

    val lastUpdatedLabel = if (lastUpdatedMs == 0L) {
        "never"
    } else {
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(lastUpdatedMs))
    }

    ScreenScaffold(
        "Status",
        "Live overview · auto-refresh 5s · updated $lastUpdatedLabel",
        actions = {
            TextButton(onClick = {
                refreshing = true
                tick++
            }) { Text("Refresh") }
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
                            repo.refreshStatus()
                                .onSuccess { applySuccess(it) }
                                .onFailure {
                                    error = it.message
                                    refreshing = false
                                }
                        }
                    },
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            SectionCard("Version") {
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
                            }
                        }
                        item {
                            SectionCard("Gateway") {
                                val gw = s.gateway
                                // Some dashboard versions nest state, others only
                                // emit the top-level gateway_running flag.
                                val running = gw?.running ?: s.gateway_running
                                Text(
                                    if (running == true) {
                                        "Running · pid=${gw?.pid ?: "?"}"
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
                                gw?.state?.let {
                                    Text("State: $it", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        item {
                            SectionCard("Platforms") {
                                val platforms = formatPlatforms(s.gateway?.platforms)
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
                            Text(
                                "Active sessions: ${s.active_sessions ?: "—"}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                            Text(
                                "Recent (up to 20)",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        val recent = s.sessions.take(20)
                        if (recent.isEmpty()) {
                            item {
                                Text("No recent sessions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            items(recent, key = { it.id }) { session ->
                                SessionRow(session, onOpenSession)
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
                    formatTokens(session.tokens),
                    session.last_active,
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

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            content()
        }
    }
}

private fun formatTokens(tokens: kotlinx.serialization.json.JsonElement?): String? {
    if (tokens == null) return null
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
