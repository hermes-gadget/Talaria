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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.core.network.HermesEventClient
import com.hermesgadget.talaria.core.network.HermesSideEvent
import com.hermesgadget.talaria.domain.model.ToolCallUi
import kotlinx.coroutines.delay
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private enum class MonitorEntryKind { TOOL, PROMPT, DELEGATE, WORK }

private data class MonitorEntry(
    val id: String,
    val kind: MonitorEntryKind,
    val name: String,
    val status: String,
    val args: String? = null,
    val message: String? = null,
    val startedAt: Long,
    val updatedAt: Long,
)

/**
 * Read-only live activity panel for the current chat tab.
 *
 * ChatViewModel already exposes the durable tool/working/prompt state. The
 * monitor additionally observes the shared sidecar flow so it can retain
 * timing and delegate/subagent frames that are intentionally not transcript
 * messages. It never sends commands or changes chat state.
 */
@Composable
fun SubagentMonitor(
    active: ChatTab?,
    eventClient: HermesEventClient,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sidecarEntries by remember(eventClient, active?.id) { mutableStateOf(emptyList<MonitorEntry>()) }
    val startedAtByTool = remember { mutableMapOf<String, Long>() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(eventClient, active?.id) {
        sidecarEntries = emptyList()
        eventClient.events.collect { event ->
            if (event is HermesSideEvent.PromptExpired) {
                sidecarEntries = sidecarEntries.filterNot { entry ->
                    entry.kind == MonitorEntryKind.PROMPT &&
                        (event.requestId == null || entry.id == "prompt:${event.requestId}")
                }
                return@collect
            }
            val entry = event.toMonitorEntry(System.currentTimeMillis()) ?: return@collect
            sidecarEntries = upsertMonitorEntry(sidecarEntries, entry)
        }
    }

    val stateEntries = remember(active) {
        val tools = active?.tools.orEmpty().map { tool ->
            val started = startedAtByTool.getOrPut(tool.id) { System.currentTimeMillis() }
            tool.toMonitorEntry(started, System.currentTimeMillis())
        }
        val prompt = active?.prompt?.let { value ->
            val id = "prompt:${value.requestId ?: value.message.hashCode()}"
            val started = startedAtByTool.getOrPut(id) { System.currentTimeMillis() }
            value.toMonitorEntry(id, started, System.currentTimeMillis())
        }
        tools + listOfNotNull(prompt)
    }
    // A tool may first appear in ChatViewModel and only later in the sidecar
    // collector (or vice versa); the id merge keeps one stable row and timer.
    val merged = remember(sidecarEntries, stateEntries, active?.working) {
        val combined = sidecarEntries.fold(LinkedHashMap<String, MonitorEntry>()) { map, entry ->
            map[entry.id] = entry
            map
        }
        stateEntries.forEach { entry ->
            val previous = combined[entry.id]
            combined[entry.id] = if (previous == null) {
                entry
            } else {
                previous.copy(
                    status = entry.status,
                    args = entry.args ?: previous.args,
                    message = entry.message ?: previous.message,
                    updatedAt = maxOf(previous.updatedAt, entry.updatedAt),
                )
            }
        }
        if (active?.working == true && combined.values.none { it.isActive() }) {
            val started = startedAtByTool.getOrPut("working") { System.currentTimeMillis() }
            combined["working"] = MonitorEntry(
                id = "working",
                kind = MonitorEntryKind.WORK,
                name = "Agent turn",
                status = "RUNNING",
                message = "Waiting for the assistant response",
                startedAt = started,
                updatedAt = System.currentTimeMillis(),
            )
        }
        combined.values.sortedWith(compareByDescending<MonitorEntry> { it.updatedAt }.thenBy { it.id })
    }
    val activeEntries = merged.filter { it.isActive() }
    val recentEntries = merged.filterNot { it.isActive() }.take(MAX_RECENT_ENTRIES)

    LaunchedEffect(activeEntries.isNotEmpty()) {
        if (activeEntries.isNotEmpty()) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Hub, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    "Agent activity",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(
                    if (activeEntries.isEmpty()) "idle" else "${activeEntries.size} active",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (activeEntries.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close agent activity")
                }
            }
            if (activeEntries.isNotEmpty()) {
                Text(
                    "Active stack",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
                activeEntries.forEach { entry ->
                    MonitorEntryRow(entry = entry, now = now, defaultExpanded = true)
                }
            }
            if (recentEntries.isNotEmpty()) {
                Text(
                    "Recent",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(recentEntries, key = { it.id }) { entry ->
                        MonitorEntryRow(entry = entry, now = now, defaultExpanded = false)
                    }
                }
            }
            if (activeEntries.isEmpty() && recentEntries.isEmpty()) {
                Text(
                    "Tool calls and delegated work will appear here while Hermes is running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MonitorEntryRow(entry: MonitorEntry, now: Long, defaultExpanded: Boolean) {
    var expanded by remember(entry.id) { mutableStateOf(defaultExpanded) }
    val elapsed = if (entry.isActive()) now - entry.startedAt else entry.updatedAt - entry.startedAt
    val statusColor = when {
        entry.status == "ERROR" || entry.status.equals("FAILED", ignoreCase = true) ->
            MaterialTheme.colorScheme.error
        entry.isActive() -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (entry.isActive()) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = statusColor,
                )
            } else {
                Text(
                    if (statusColor == MaterialTheme.colorScheme.error) "!" else "✓",
                    color = statusColor,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.size(14.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${entry.kind.label()} · ${formatDuration(elapsed)} · ${entry.status.lowercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse ${entry.name}" else "Expand ${entry.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            entry.args?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 22.dp, top = 2.dp),
                )
            }
            entry.message?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 22.dp, top = 2.dp),
                )
            }
        }
    }
}

private fun ToolCallUi.toMonitorEntry(startedAt: Long, now: Long): MonitorEntry = MonitorEntry(
    id = "tool:$id",
    kind = MonitorEntryKind.TOOL,
    name = name,
    status = status,
    args = argsPreview,
    message = message,
    startedAt = startedAt,
    updatedAt = now,
)

private fun ChatPromptUi.toMonitorEntry(id: String, startedAt: Long, now: Long): MonitorEntry = MonitorEntry(
    id = id,
    kind = MonitorEntryKind.PROMPT,
    name = "${kind.name.lowercase().replaceFirstChar { it.uppercase() }} prompt",
    status = "WAITING",
    message = message,
    startedAt = startedAt,
    updatedAt = now,
)

private fun HermesSideEvent.toMonitorEntry(now: Long): MonitorEntry? = when (this) {
    is HermesSideEvent.Tool -> MonitorEntry(
        id = "tool:$id",
        kind = MonitorEntryKind.TOOL,
        name = name,
        status = status.name,
        args = argsPreview,
        message = message,
        startedAt = now,
        updatedAt = now,
    )

    is HermesSideEvent.Prompt -> MonitorEntry(
        id = "prompt:${requestId ?: message.hashCode()}",
        kind = MonitorEntryKind.PROMPT,
        name = "${kind.name.lowercase().replaceFirstChar { it.uppercase() }} prompt",
        status = "WAITING",
        message = message,
        startedAt = now,
        updatedAt = now,
    )

    is HermesSideEvent.Raw -> if (
        type.contains("delegate", ignoreCase = true) ||
            type.contains("subagent", ignoreCase = true) ||
            type.contains("agent", ignoreCase = true)
    ) {
        val id = payload["subagent_id"]?.jsonPrimitive?.contentOrNull
            ?: payload["delegate_id"]?.jsonPrimitive?.contentOrNull
            ?: payload["id"]?.jsonPrimitive?.contentOrNull
            ?: type
        val name = payload["goal"]?.jsonPrimitive?.contentOrNull
            ?: payload["task"]?.jsonPrimitive?.contentOrNull
            ?: payload["name"]?.jsonPrimitive?.contentOrNull
            ?: type
        val status = payload["status"]?.jsonPrimitive?.contentOrNull?.uppercase()
            ?: if (type.contains("complete", ignoreCase = true) || type.contains("done", ignoreCase = true)) {
                "DONE"
            } else {
                "RUNNING"
            }
        MonitorEntry(
            id = "delegate:$id",
            kind = MonitorEntryKind.DELEGATE,
            name = name,
            status = status,
            args = payload["tool_preview"]?.jsonPrimitive?.contentOrNull
                ?: payload["args"]?.toString(),
            message = payload["summary"]?.jsonPrimitive?.contentOrNull
                ?: payload["text"]?.jsonPrimitive?.contentOrNull,
            startedAt = now,
            updatedAt = now,
        )
    } else null

    else -> null
}

private fun upsertMonitorEntry(entries: List<MonitorEntry>, entry: MonitorEntry): List<MonitorEntry> {
    val index = entries.indexOfFirst { it.id == entry.id }
    if (index < 0) return (entries + entry).takeLast(MAX_SIDE_CAR_ENTRIES)
    val previous = entries[index]
    return entries.toMutableList().also {
        it[index] = entry.copy(startedAt = previous.startedAt)
    }
}

private fun MonitorEntry.isActive(): Boolean =
    status == "RUNNING" || status == "WAITING" || status == "QUEUED" || status == "PENDING"

private fun MonitorEntryKind.label(): String = when (this) {
    MonitorEntryKind.TOOL -> "tool"
    MonitorEntryKind.PROMPT -> "prompt"
    MonitorEntryKind.DELEGATE -> "delegate"
    MonitorEntryKind.WORK -> "turn"
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0L) / 1_000L)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3_600}h ${(seconds / 60) % 60}m"
    }
}

private const val MAX_RECENT_ENTRIES = 12
private const val MAX_SIDE_CAR_ENTRIES = 40
