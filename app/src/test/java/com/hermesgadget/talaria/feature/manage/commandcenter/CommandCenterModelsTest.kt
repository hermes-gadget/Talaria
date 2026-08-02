/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */

package com.hermesgadget.talaria.feature.manage.commandcenter

import com.hermesgadget.talaria.domain.model.AnalyticsUsage
import com.hermesgadget.talaria.domain.model.AnalyticsTotals
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandCenterModelsTest {
    @Test
    fun parsesTimestampWarningComponentAndMessage() {
        val line = parseLogLine(
            "2026-08-02 01:54:09,239 WARNING tools.mcp_tool: Connection closed\n",
            source = "errors",
        )

        assertEquals("2026-08-02 01:54:09,239", line.timestamp)
        assertEquals(CommandCenterLogLevel.WARN, line.level)
        assertEquals("tools.mcp_tool", line.component)
        assertEquals("Connection closed", line.message)
        assertEquals("errors", line.source)
    }

    @Test
    fun keepsUnstructuredLinesAsUnknown() {
        val line = parseLogLine("gateway started", source = "gateway")

        assertNull(line.timestamp)
        assertEquals(CommandCenterLogLevel.UNKNOWN, line.level)
        assertNull(line.component)
        assertEquals("gateway started", line.message)
    }

    @Test
    fun usageSummaryPrefersCurrentTotals() {
        val summary = parseUsageSummary(
            AnalyticsUsage(
                period_days = 7,
                daily = buildJsonArray {
                    add(buildJsonObject {
                        put("input_tokens", 1_000L)
                        put("output_tokens", 200L)
                    })
                },
                totals = AnalyticsTotals(
                    total_input = 12_000L,
                    total_output = 3_000L,
                    total_cache_read = 50_000L,
                    total_reasoning = 700L,
                    total_estimated_cost = 1.25,
                    total_actual_cost = 0.75,
                    total_sessions = 9,
                    total_api_calls = 42L,
                ),
            ),
        )

        assertEquals(7, summary.periodDays)
        assertEquals(12_000L, summary.inputTokens)
        assertEquals(3_000L, summary.outputTokens)
        assertEquals(50_000L, summary.cacheReadTokens)
        assertEquals(700L, summary.reasoningTokens)
        assertEquals(1.25, summary.estimatedCost!!, 0.001)
        assertEquals(0.75, summary.actualCost!!, 0.001)
        assertEquals(9, summary.sessions)
        assertEquals(42L, summary.apiCalls)
    }

    @Test
    fun usageSummarySumsDailyRowsForLegacyOrPartialResponses() {
        val summary = parseUsageSummary(
            AnalyticsUsage(
                days = 2,
                daily = buildJsonArray {
                    add(buildJsonObject {
                        put("input_tokens", 10L)
                        put("output_tokens", 3L)
                        put("cache_read_tokens", 100L)
                        put("reasoning_tokens", 2L)
                        put("estimated_cost", 0.2)
                        put("actual_cost", 0.1)
                        put("sessions", 2L)
                        put("api_calls", 4L)
                    })
                    add(buildJsonObject {
                        put("input_tokens", 20L)
                        put("output_tokens", 7L)
                        put("cache_read_tokens", 200L)
                        put("reasoning_tokens", 5L)
                        put("estimated_cost", 0.3)
                        put("actual_cost", 0.0)
                        put("sessions", 3L)
                        put("api_calls", 6L)
                    })
                },
            ),
        )

        assertEquals(2, summary.periodDays)
        assertEquals(30L, summary.inputTokens)
        assertEquals(10L, summary.outputTokens)
        assertEquals(300L, summary.cacheReadTokens)
        assertEquals(7L, summary.reasoningTokens)
        assertEquals(0.5, summary.estimatedCost!!, 0.001)
        assertEquals(0.1, summary.actualCost!!, 0.001)
        assertEquals(5, summary.sessions)
        assertEquals(10L, summary.apiCalls)
    }
}
