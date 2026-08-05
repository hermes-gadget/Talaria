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


package com.hermesgadget.talaria.widget

import com.hermesgadget.talaria.R
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
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.domain.model.scopeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TalariaStatusWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val settings = TalariaApp.instance.container.settingsStore
        val connectLabel = context.getString(R.string.widget_status_connect)
        val gwUp = context.getString(R.string.widget_status_gw_up)
        val gwDown = context.getString(R.string.widget_status_gw_down)
        val snapshot = withContext(Dispatchers.IO) {
            val scopeId = TalariaApp.instance.container.connectionStore.activeProfile()?.scopeId()
                ?: return@withContext WidgetSnapshot(connectLabel, 0, stale = false)
            val live = runCatching {
                val status = TalariaApp.instance.container.hermesRepository.refreshStatus().getOrThrow()
                val gw = if ((status.gateway?.running ?: status.gateway_running) == true) gwUp else gwDown
                val line = "Hermes ${status.version ?: "?"} · $gw · sessions ${status.active_sessions ?: 0}"
                // Refresh the offline cache while we have a fresh read.
                settings.setCachedStatusLine(scopeId, line)
                settings.setCachedStatusUpdatedAt(scopeId, System.currentTimeMillis())
                line
            }.getOrNull()
            val pending = settings.pendingPairingCount(scopeId)
            val cached = settings.cachedStatusLine(scopeId)
            when {
                live != null -> WidgetSnapshot(live, pending, stale = false)
                cached != null -> WidgetSnapshot(cached, pending, stale = true)
                else -> WidgetSnapshot(connectLabel, 0, stale = false)
            }
        }
        provideContent {
            GlanceTheme {
                StatusContent(snapshot, context)
            }
        }
    }
}

private data class WidgetSnapshot(val line: String, val pendingPairing: Int, val stale: Boolean)

@Composable
private fun StatusContent(snapshot: WidgetSnapshot, context: Context) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(context.getString(R.string.widget_status_title), style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp))
        Text(
            if (snapshot.stale) context.getString(R.string.widget_status_cached, snapshot.line) else snapshot.line,
            style = TextStyle(fontSize = 13.sp),
        )
        if (snapshot.pendingPairing > 0) {
            Text(
                context.resources.getQuantityString(
                    R.plurals.widget_status_pairing,
                    snapshot.pendingPairing,
                    snapshot.pendingPairing,
                ),
                style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
            )
        }
    }
}

class TalariaStatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TalariaStatusWidget()
}
