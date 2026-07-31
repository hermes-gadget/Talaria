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
package com.nousresearch.talaria.feature.manage.analytics

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.AnalyticsUsage
import com.nousresearch.talaria.ui.components.ErrorBox
import com.nousresearch.talaria.ui.components.LoadingBox
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Composable
fun AnalyticsScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    var data by remember { mutableStateOf<AnalyticsUsage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun reload() = scope.launch {
        loading = true
        repo.getAnalytics(30)
            .onSuccess {
                data = it
                error = null
                loading = false
            }
            .onFailure {
                error = it.message
                loading = false
            }
    }
    LaunchedEffect(Unit) { reload() }

    ScreenScaffold("Analytics", "Last 30 days", actions = {
        TextButton(onClick = { reload() }) { Text("Refresh") }
    }) {
        when {
            loading && data == null -> LoadingBox()
            error != null && data == null -> ErrorBox(error!!, onRetry = { reload() })
            else -> {
                val a = data ?: return@ScreenScaffold
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TotalCard("Sessions", a.session_count?.toString() ?: "—", Modifier.weight(1f))
                        TotalCard("Cost", a.total_cost?.let { "%.2f".format(it) } ?: "—", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TotalCard(
                            "Input",
                            formatTokens(a.total_input_tokens),
                            Modifier.weight(1f),
                        )
                        TotalCard(
                            "Output",
                            formatTokens(a.total_output_tokens),
                            Modifier.weight(1f),
                        )
                    }
                    val bars = parseDailyBars(a.daily)
                    if (bars.isNotEmpty()) {
                        Text("Daily", style = MaterialTheme.typography.titleMedium)
                        val max = bars.maxOf { it.value }.coerceAtLeast(1.0)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            bars.forEach { bar ->
                                val frac = (bar.value / max).toFloat().coerceIn(0.02f, 1f)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(frac)
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
                    }
                }
            }
        }
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

private fun formatTokens(n: Long?): String {
    if (n == null) return "—"
    return when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fk".format(n / 1_000.0)
        else -> n.toString()
    }
}

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
                        ?: el["input_tokens"]?.jsonPrimitive?.longOrNull?.toDouble()
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
