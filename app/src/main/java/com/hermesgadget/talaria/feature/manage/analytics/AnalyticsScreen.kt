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
package com.hermesgadget.talaria.feature.manage.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.domain.model.AnalyticsUsage
import com.hermesgadget.talaria.domain.model.EgressStatusResponse
import com.hermesgadget.talaria.ui.components.CollapsibleSection
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Composable
fun AnalyticsScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    val api = TalariaApp.instance.container.clientFactory.api()
    var data by remember { mutableStateOf<AnalyticsUsage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var modelRows by remember { mutableStateOf<List<AnalyticsModelRow>>(emptyList()) }
    var modelsLoading by remember { mutableStateOf(true) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var egressStatus by remember { mutableStateOf<EgressStatusResponse?>(null) }
    var egressLoading by remember { mutableStateOf(true) }
    var egressError by remember { mutableStateOf<String?>(null) }
    var days by remember { mutableIntStateOf(30) }
    var reloadJob by remember { mutableStateOf<Job?>(null) }
    var reloadGeneration by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun reload() {
        reloadJob?.cancel()
        val requestedDays = days
        val generation = ++reloadGeneration
        loading = true
        modelsLoading = true
        egressLoading = true
        reloadJob = scope.launch {
            val (analyticsResult, modelsResult, egressResult) = coroutineScope {
                val analytics = async { repo.getAnalytics(requestedDays) }
                val models = async { captureAnalyticsRequest { api.getAnalyticsModels() } }
                val egress = async { captureAnalyticsRequest { api.getEgressStatus() } }
                Triple(analytics.await(), models.await(), egress.await())
            }
            if (generation != reloadGeneration || requestedDays != days) return@launch
            analyticsResult
                .onSuccess {
                    data = it
                    error = null
                }
                .onFailure { error = it.message }
            loading = false
            modelsResult
                .onSuccess {
                    modelRows = parseAnalyticsModels(it)
                    modelsError = null
                }
                .onFailure { modelsError = it.message }
            modelsLoading = false
            egressResult
                .onSuccess {
                    egressStatus = it
                    egressError = null
                }
                .onFailure { egressError = it.message }
            egressLoading = false
        }
    }
    LaunchedEffect(days) { reload() }

    ScreenScaffold("Analytics", "Last $days days", actions = {
        TextButton(onClick = { reload() }) { Text("Refresh") }
    }) {
        // Range selector — the backend getAnalytics(days) param, now user-driven.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(bottom = 8.dp)) {
            val ranges = listOf(7, 30, 90)
            ranges.forEachIndexed { index, r ->
                SegmentedButton(
                    selected = days == r,
                    onClick = { days = r },
                    shape = SegmentedButtonDefaults.itemShape(index, ranges.size),
                ) { Text("${r}d") }
            }
        }
        when {
            loading && data == null -> LoadingBox()
            error != null && data == null -> ErrorBox(error!!, onRetry = { reload() })
            else -> {
                val a = data ?: return@ScreenScaffold
                val totals = a.totals
                val inputTokens = totals?.total_input ?: a.total_input_tokens
                val outputTokens = totals?.total_output ?: a.total_output_tokens
                val sessions = totals?.total_sessions ?: a.session_count
                val cost = totals?.total_actual_cost?.takeIf { it > 0.0 }
                    ?: totals?.total_estimated_cost
                    ?: a.total_cost
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CollapsibleSection(stringResource(R.string.minor_analytics_overview)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TotalCard("Sessions", sessions?.toString() ?: "—", Modifier.weight(1f))
                            TotalCard("Cost", cost?.let { "$%.2f".format(it) } ?: "—", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TotalCard(
                                "Input",
                                formatTokens(inputTokens),
                                Modifier.weight(1f),
                            )
                            TotalCard(
                                "Output",
                                formatTokens(outputTokens),
                                Modifier.weight(1f),
                            )
                        }
                    }
                    CollapsibleSection(
                        title = stringResource(R.string.minor_analytics_daily_usage),
                        collapsible = true,
                    ) {
                        val bars = parseDailyBars(a.daily)
                        if (bars.isNotEmpty()) {
                            val max = bars.maxOf { it.value }.coerceAtLeast(1.0)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                bars.forEachIndexed { index, bar ->
                                    val frac = (bar.value / max).toFloat().coerceIn(0.02f, 1f)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(frac)
                                            .semantics {
                                                contentDescription =
                                                    "${bar.label.ifBlank { "Day ${index + 1}" }}: " +
                                                        "${bar.value}"
                                            }
                                            .background(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.shapes.extraSmall,
                                            ),
                                    )
                                }
                            }
                            Text(
                                "${bars.size} days · max ${formatTokens(bars.maxOf { it.value.toLong() })}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                "No usage yet for this window.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    CollapsibleSection(
                        title = stringResource(R.string.minor_analytics_per_model),
                        collapsible = true,
                    ) {
                        when {
                            modelsLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            modelsError != null -> Text(
                                modelsError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            modelRows.isEmpty() -> Text(
                                stringResource(R.string.minor_analytics_no_models),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> modelRows.forEach { row -> AnalyticsModelCard(row) }
                        }
                    }
                    CollapsibleSection(
                        title = stringResource(R.string.minor_analytics_connectivity),
                        collapsible = true,
                    ) {
                        when {
                            egressLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            egressError != null -> Text(
                                egressError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            else -> EgressStatusCard(egressStatus)
                        }
                    }
                }
            }
        }
    }
}

private data class AnalyticsModelRow(
    val model: String,
    val provider: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val sessions: Long,
    val estimatedCost: Double,
    val actualCost: Double,
    val apiCalls: Long,
    val toolCalls: Long,
)

private fun parseAnalyticsModels(root: JsonElement): List<AnalyticsModelRow> {
    val elements = when (root) {
        is JsonArray -> root
        is JsonObject -> root["models"] as? JsonArray ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return elements.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val model = obj.stringValue("model", "name") ?: return@mapNotNull null
        AnalyticsModelRow(
            model = model,
            provider = obj.stringValue("provider", "billing_provider"),
            inputTokens = obj.longValue("input_tokens"),
            outputTokens = obj.longValue("output_tokens"),
            cacheReadTokens = obj.longValue("cache_read_tokens"),
            sessions = obj.longValue("sessions", "session_count"),
            estimatedCost = obj.doubleValue("estimated_cost"),
            actualCost = obj.doubleValue("actual_cost"),
            apiCalls = obj.longValue("api_calls"),
            toolCalls = obj.longValue("tool_calls"),
        )
    }
}

private fun JsonObject.stringValue(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

private fun JsonObject.longValue(vararg keys: String): Long = keys.firstNotNullOfOrNull { key ->
    val primitive = this[key] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
    primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
} ?: 0L

private fun JsonObject.doubleValue(vararg keys: String): Double = keys.firstNotNullOfOrNull { key ->
    val primitive = this[key] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
    primitive.doubleOrNull ?: primitive.longOrNull?.toDouble()
} ?: 0.0

@Composable
private fun AnalyticsModelCard(row: AnalyticsModelRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(row.model, style = MaterialTheme.typography.titleSmall)
            row.provider?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                listOf(
                    stringResource(R.string.minor_analytics_input, formatTokens(row.inputTokens)),
                    stringResource(R.string.minor_analytics_output, formatTokens(row.outputTokens)),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            if (row.cacheReadTokens > 0L) {
                Text(
                    stringResource(R.string.minor_analytics_cache, formatTokens(row.cacheReadTokens)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                listOf(
                    pluralStringResource(R.plurals.minor_analytics_sessions, row.sessions.toInt(), row.sessions),
                    stringResource(
                        R.string.minor_analytics_cost,
                        formatCost(row.actualCost.takeIf { it > 0.0 } ?: row.estimatedCost),
                    ),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.minor_analytics_calls, row.apiCalls, row.toolCalls),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EgressStatusCard(status: EgressStatusResponse?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = status?.text?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.minor_analytics_egress_empty),
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TotalCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private data class DailyBar(val label: String, val value: Double)

private suspend fun <T> captureAnalyticsRequest(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}

private fun formatTokens(n: Long?): String {
    if (n == null) return "—"
    return when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fk".format(n / 1_000.0)
        else -> n.toString()
    }
}

private fun formatCost(cost: Double): String = "$%.2f".format(cost)

private fun parseDailyBars(daily: kotlinx.serialization.json.JsonElement?): List<DailyBar> {
    if (daily == null) return emptyList()
    return when (daily) {
        is JsonArray -> daily.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> {
                    val v = el.doubleOrNull ?: el.longOrNull?.toDouble() ?: return@mapNotNull null
                    DailyBar("", v)
                }
                is JsonObject -> {
                    val v = el["tokens"]?.jsonPrimitive?.doubleOrNull
                        ?: el["total"]?.jsonPrimitive?.doubleOrNull
                        ?: el["count"]?.jsonPrimitive?.doubleOrNull
                        ?: el["value"]?.jsonPrimitive?.doubleOrNull
                        ?: run {
                            val input = el["input_tokens"]?.jsonPrimitive?.longOrNull
                            val output = el["output_tokens"]?.jsonPrimitive?.longOrNull
                            if (input != null || output != null) (input ?: 0L).toDouble() + (output ?: 0L) else null
                        }
                        ?: return@mapNotNull null
                    val label = el["date"]?.jsonPrimitive?.contentOrNull
                        ?: el["day"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    DailyBar(label, v)
                }
                else -> null
            }
        }
        is JsonObject -> daily.entries.mapNotNull { (k, v) ->
            val num = when (v) {
                is JsonPrimitive -> v.doubleOrNull ?: v.longOrNull?.toDouble()
                is JsonObject -> v["tokens"]?.jsonPrimitive?.doubleOrNull
                    ?: v["total"]?.jsonPrimitive?.doubleOrNull
                else -> null
            } ?: return@mapNotNull null
            DailyBar(k, num)
        }
        else -> emptyList()
    }
}
