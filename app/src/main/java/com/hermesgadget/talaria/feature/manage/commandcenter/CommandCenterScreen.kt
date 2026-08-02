/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.manage.commandcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.ColorScheme
import com.hermesgadget.talaria.domain.model.StatusResponse
import com.hermesgadget.talaria.domain.model.SystemStats
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandCenterScreen(onOpenSystem: () -> Unit) {
    val vm: CommandCenterViewModel = viewModel(factory = CommandCenterViewModel.factory())
    val ui by vm.ui.collectAsStateWithLifecycle()

    ScreenScaffold(
        title = "Command Center",
        subtitle = "Status · logs · usage · maintenance",
        actions = {
            TextButton(
                onClick = vm::refresh,
                enabled = ui !is CommandCenterUiState.Loading,
            ) { Text("Refresh") }
        },
    ) {
        when (val state = ui) {
            CommandCenterUiState.Loading -> LoadingBox()
            is CommandCenterUiState.Failure -> ErrorBox(state.message, onRetry = vm::refresh)
            is CommandCenterUiState.Content -> PullToRefreshBox(
                isRefreshing = state.data.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                CommandCenterContent(state.data, onOpenSystem)
            }
        }
    }
}

@Composable
private fun CommandCenterContent(data: CommandCenterContent, onOpenSystem: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { GatewaySection(data.gateway) }
        item { LogsSection(data.logs) }
        item { UsageSection(data.usage) }
        item { MaintenanceSection(onOpenSystem) }
        if (data.lastUpdatedMs > 0L) {
            item {
                Text(
                    "Updated ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(data.lastUpdatedMs))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun GatewaySection(section: CommandCenterSection<CommandCenterGateway>) {
    when (section) {
        is CommandCenterSection.Unavailable -> DisabledSection("Gateway", section.reason)
        is CommandCenterSection.Available -> {
            val gateway = section.value
            SectionCard("Gateway") {
                gateway.status?.let { status -> GatewayStatusBlock(status) }
                gateway.stats?.let { stats -> HostStatsBlock(stats, gateway.status) }
                gateway.warnings.forEach { warning ->
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GatewayStatusBlock(status: StatusResponse) {
    val running = status.gateway?.running ?: status.gateway_running
    Text(
        when (running) {
            true -> "Running"
            false -> "Stopped"
            null -> "Gateway state unavailable"
        },
        style = MaterialTheme.typography.titleMedium,
        color = when (running) {
            true -> MaterialTheme.colorScheme.primary
            false -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    Text("Version: ${status.version ?: "—"}")
    status.active_sessions?.let { Text("Active sessions: $it") }
}

@Composable
private fun HostStatsBlock(stats: SystemStats, status: StatusResponse?) {
    Spacer(modifier = Modifier.height(4.dp))
    Text("Host: ${stats.hostname ?: "—"}")
    Text("Hermes: ${stats.hermes_version ?: status?.version ?: "—"}")
    Text("Uptime: ${formatUptime(stats.uptime_seconds) ?: stats.uptime?.toString()?.trim('"') ?: "—"}")
    Text("CPU: ${stats.cpu_percent?.let { "%.1f%%".format(Locale.US, it) } ?: "—"}")
}

@Composable
private fun LogsSection(section: CommandCenterSection<CommandCenterLogs>) {
    when (section) {
        is CommandCenterSection.Unavailable -> DisabledSection("Logs", section.reason)
        is CommandCenterSection.Available -> {
            val logs = section.value
            SectionCard("Logs") {
                Text(
                    "Recent agent, gateway, and error lines",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                logs.unavailableSources.forEach { source ->
                    Text(
                        "Disabled: $source",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (logs.lines.isEmpty()) {
                    Text("No recent log lines.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    logs.lines.take(48).forEach { line -> LogLineRow(line) }
                }
            }
        }
    }
}

@Composable
private fun LogLineRow(line: CommandCenterLogLine) {
    val colors = MaterialTheme.colorScheme
    val levelColor = logLevelColor(line.level, colors)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp, end = 7.dp)
                .size(7.dp)
                .background(levelColor, RoundedCornerShape(50)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                listOfNotNull(line.timestamp, line.source, line.level.label).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = levelColor,
            )
            Text(
                buildString {
                    line.component?.let { append(it).append(": ") }
                    append(line.message)
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun logLevelColor(level: CommandCenterLogLevel, colors: ColorScheme): Color = when (level) {
    CommandCenterLogLevel.ERROR, CommandCenterLogLevel.FATAL -> colors.error
    CommandCenterLogLevel.WARN -> colors.tertiary
    CommandCenterLogLevel.INFO -> colors.primary
    CommandCenterLogLevel.DEBUG, CommandCenterLogLevel.TRACE -> colors.secondary
    CommandCenterLogLevel.UNKNOWN -> colors.onSurfaceVariant
}

@Composable
private fun UsageSection(section: CommandCenterSection<CommandCenterUsageSummary>) {
    when (section) {
        is CommandCenterSection.Unavailable -> DisabledSection("Usage", section.reason)
        is CommandCenterSection.Available -> {
            val usage = section.value
            SectionCard("Usage") {
                Text(
                    "Last ${usage.periodDays ?: COMMAND_CENTER_USAGE_DAYS} days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageMetric("Sessions", usage.sessions?.toString() ?: "—", Modifier.weight(1f))
                    UsageMetric("API calls", formatCount(usage.apiCalls), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageMetric("Input", formatCount(usage.inputTokens), Modifier.weight(1f))
                    UsageMetric("Output", formatCount(usage.outputTokens), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageMetric("Cache read", formatCount(usage.cacheReadTokens), Modifier.weight(1f))
                    UsageMetric("Reasoning", formatCount(usage.reasoningTokens), Modifier.weight(1f))
                }
                val actual = usage.actualCost?.takeIf { it > 0.0 }
                val cost = actual ?: usage.estimatedCost
                UsageMetric(
                    if (actual != null) "Actual cost" else "Estimated cost",
                    cost?.let { "%.2f".format(Locale.US, it) } ?: "—",
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun UsageMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            Text(value, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun MaintenanceSection(onOpenSystem: () -> Unit) {
    SectionCard("Maintenance") {
        Text(
            "Open System to review and run the gateway maintenance actions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onOpenSystem, modifier = Modifier.weight(1f)) { Text("Doctor") }
            OutlinedButton(onClick = onOpenSystem, modifier = Modifier.weight(1f)) { Text("Backup") }
            OutlinedButton(onClick = onOpenSystem, modifier = Modifier.weight(1f)) { Text("Restart") }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DisabledSection(title: String, reason: String) {
    SectionCard(title) {
        Text("Disabled", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatCount(value: Long?): String = value?.let {
    when {
        it >= 1_000_000 -> "%.1fM".format(Locale.US, it / 1_000_000.0)
        it >= 1_000 -> "%.1fK".format(Locale.US, it / 1_000.0)
        else -> it.toString()
    }
} ?: "—"

private fun formatUptime(seconds: Long?): String? {
    if (seconds == null || seconds < 0L) return null
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return buildString {
        if (days > 0) append(days).append("d ")
        if (hours > 0 || days > 0) append(hours).append("h ")
        append(minutes).append("m")
    }
}
