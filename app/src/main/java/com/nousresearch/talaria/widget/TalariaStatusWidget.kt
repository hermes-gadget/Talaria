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


package com.nousresearch.talaria.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.nousresearch.talaria.TalariaApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TalariaStatusWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val line = withContext(Dispatchers.IO) {
            runCatching {
                val status = TalariaApp.instance.container.hermesRepository.refreshStatus().getOrThrow()
                val gw = if (status.gateway?.running == true) "GW up" else "GW down"
                "Hermes ${status.version ?: "?"} · $gw · sessions ${status.active_sessions ?: 0}"
            }.getOrElse { "Talaria · connect Hermes" }
        }
        provideContent {
            GlanceTheme {
                StatusContent(line)
            }
        }
    }
}

@Composable
private fun StatusContent(line: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Talaria", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp))
        Text(line, style = TextStyle(fontSize = 13.sp))
    }
}

class TalariaStatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TalariaStatusWidget()
}
