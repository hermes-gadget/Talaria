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

import com.hermesgadget.talaria.domain.model.AnalyticsUsage
import com.hermesgadget.talaria.domain.model.StatusResponse
import com.hermesgadget.talaria.domain.model.SystemStats
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal const val COMMAND_CENTER_USAGE_DAYS = 7
internal const val COMMAND_CENTER_LOG_LINES = 40
internal val COMMAND_CENTER_LOG_SOURCES = listOf("agent", "gateway", "errors")

sealed interface CommandCenterSection<out T> {
    data class Available<T>(val value: T) : CommandCenterSection<T>

    data class Unavailable(val reason: String) : CommandCenterSection<Nothing>
}

data class CommandCenterGateway(
    val status: StatusResponse?,
    val stats: SystemStats?,
    val warnings: List<String> = emptyList(),
)

enum class CommandCenterLogLevel(val label: String) {
    TRACE("TRACE"),
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
    FATAL("FATAL"),
    UNKNOWN("LOG"),
}

data class CommandCenterLogLine(
    val source: String,
    val timestamp: String?,
    val level: CommandCenterLogLevel,
    val component: String?,
    val message: String,
    val raw: String,
)

data class CommandCenterLogs(
    val lines: List<CommandCenterLogLine>,
    val unavailableSources: List<String> = emptyList(),
)

data class CommandCenterUsageSummary(
    val periodDays: Int?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cacheReadTokens: Long?,
    val reasoningTokens: Long?,
    val estimatedCost: Double?,
    val actualCost: Double?,
    val sessions: Int?,
    val apiCalls: Long?,
)

data class CommandCenterContent(
    val gateway: CommandCenterSection<CommandCenterGateway>,
    val logs: CommandCenterSection<CommandCenterLogs>,
    val usage: CommandCenterSection<CommandCenterUsageSummary>,
    val refreshing: Boolean = false,
    val lastUpdatedMs: Long = 0L,
)

sealed interface CommandCenterUiState {
    data object Loading : CommandCenterUiState

    data class Content(val data: CommandCenterContent) : CommandCenterUiState

    data class Failure(val message: String) : CommandCenterUiState
}

private val logLinePattern = Regex(
    """^\s*(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[,.]\d{1,6})?(?:Z|[+-]\d{2}:?\d{2})?)\s+([A-Za-z]+)\s+(.*)$""",
)
private val componentPattern = Regex("""^([A-Za-z0-9_.-]+):\s*(.*)$""")

/** Parse the timestamp, level, component, and message from one Hermes log line. */
internal fun parseLogLine(raw: String, source: String): CommandCenterLogLine {
    val clean = raw.trimEnd('\r', '\n')
    val match = logLinePattern.matchEntire(clean)
    if (match == null) {
        return CommandCenterLogLine(
            source = source,
            timestamp = null,
            level = CommandCenterLogLevel.UNKNOWN,
            component = null,
            message = clean.trim(),
            raw = raw,
        )
    }

    val payload = match.groupValues[3].trim()
    val componentMatch = componentPattern.matchEntire(payload)
    val component = componentMatch?.groupValues?.get(1)
    val message = componentMatch?.groupValues?.get(2).orEmpty().ifBlank {
        componentMatch?.groupValues?.get(1) ?: payload
    }

    return CommandCenterLogLine(
        source = source,
        timestamp = match.groupValues[1],
        level = parseLogLevel(match.groupValues[2]),
        component = component,
        message = message,
        raw = raw,
    )
}

internal fun parseLogLines(source: String, lines: List<String>): List<CommandCenterLogLine> =
    lines
        .map { parseLogLine(it, source) }
        .sortedWith(compareByDescending<CommandCenterLogLine> { it.timestamp.orEmpty() })

private fun parseLogLevel(raw: String): CommandCenterLogLevel = when (raw.uppercase(Locale.US)) {
    "TRACE" -> CommandCenterLogLevel.TRACE
    "DEBUG" -> CommandCenterLogLevel.DEBUG
    "INFO", "INFORMATION" -> CommandCenterLogLevel.INFO
    "WARN", "WARNING" -> CommandCenterLogLevel.WARN
    "ERROR", "SEVERE" -> CommandCenterLogLevel.ERROR
    "FATAL", "CRITICAL" -> CommandCenterLogLevel.FATAL
    else -> CommandCenterLogLevel.UNKNOWN
}

/** Normalize both the legacy and current analytics response shapes into one summary. */
internal fun parseUsageSummary(data: AnalyticsUsage): CommandCenterUsageSummary {
    val dailyRows = dailyObjects(data.daily)
    val totals = data.totals

    return CommandCenterUsageSummary(
        periodDays = data.period_days ?: data.days,
        inputTokens = totals?.total_input
            ?: data.total_input_tokens
            ?: sumDailyLong(dailyRows, "input_tokens", "total_input_tokens"),
        outputTokens = totals?.total_output
            ?: data.total_output_tokens
            ?: sumDailyLong(dailyRows, "output_tokens", "total_output_tokens"),
        cacheReadTokens = totals?.total_cache_read
            ?: sumDailyLong(dailyRows, "cache_read_tokens", "total_cache_read"),
        reasoningTokens = totals?.total_reasoning
            ?: sumDailyLong(dailyRows, "reasoning_tokens", "total_reasoning"),
        estimatedCost = totals?.total_estimated_cost
            ?: data.total_cost
            ?: sumDailyDouble(dailyRows, "estimated_cost", "total_estimated_cost"),
        actualCost = totals?.total_actual_cost
            ?: sumDailyDouble(dailyRows, "actual_cost", "total_actual_cost"),
        sessions = totals?.total_sessions
            ?: data.session_count
            ?: sumDailyLong(dailyRows, "sessions", "session_count")?.toInt(),
        apiCalls = totals?.total_api_calls
            ?: sumDailyLong(dailyRows, "api_calls", "total_api_calls"),
    )
}

private fun dailyObjects(element: JsonElement?): List<JsonObject> = when (element) {
    is JsonArray -> element.mapNotNull { it as? JsonObject }
    is JsonObject -> element.values.mapNotNull { it as? JsonObject }
    else -> emptyList()
}

private fun JsonObject.numberLong(vararg names: String): Long? = names.asSequence()
    .mapNotNull { name ->
        val primitive = this[name] as? JsonPrimitive ?: return@mapNotNull null
        primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
    }
    .firstOrNull()

private fun JsonObject.numberDouble(vararg names: String): Double? = names.asSequence()
    .mapNotNull { name ->
        val primitive = this[name] as? JsonPrimitive ?: return@mapNotNull null
        primitive.doubleOrNull ?: primitive.longOrNull?.toDouble()
    }
    .firstOrNull()

private fun sumDailyLong(rows: List<JsonObject>, vararg names: String): Long? = rows
    .mapNotNull { it.numberLong(*names) }
    .takeIf { it.isNotEmpty() }
    ?.sum()

private fun sumDailyDouble(rows: List<JsonObject>, vararg names: String): Double? = rows
    .mapNotNull { it.numberDouble(*names) }
    .takeIf { it.isNotEmpty() }
    ?.sum()
