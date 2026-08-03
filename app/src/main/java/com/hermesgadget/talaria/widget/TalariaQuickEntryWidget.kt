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

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.FilledButton
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.hermesgadget.talaria.MainActivity

/** The two launcher-safe actions exposed by the quick-entry widget. */
object QuickEntryWidgetIntents {
    const val NEW_CHAT_URI = "talaria://chat?focus=composer"
    const val TALK_URI = "talaria://voice"

    fun newChat(packageName: String? = null): Intent = deepLink(NEW_CHAT_URI, packageName)

    fun talk(packageName: String? = null): Intent = deepLink(TALK_URI, packageName)

    private fun deepLink(uri: String, packageName: String?): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(uri),
    ).apply {
        packageName?.let { targetPackage ->
            // MainActivity's legacy intent filter predates the Voice host. Keep
            // the URI for app routing while targeting the app explicitly so the
            // widget action is not rejected by host matching at resolution time.
            setPackage(targetPackage)
            component = ComponentName(targetPackage, MainActivity::class.java.name)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

class TalariaQuickEntryWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                QuickEntryContent(packageName = context.packageName)
            }
        }
    }
}

@Composable
private fun QuickEntryContent(packageName: String) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Talaria quick entry",
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp),
        )
        Spacer(GlanceModifier.height(4.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledButton(
                text = "New chat",
                onClick = actionStartActivity(QuickEntryWidgetIntents.newChat(packageName)),
            )
            Spacer(GlanceModifier.width(8.dp))
            FilledButton(
                text = "Talk",
                onClick = actionStartActivity(QuickEntryWidgetIntents.talk(packageName)),
            )
        }
    }
}

class TalariaQuickEntryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TalariaQuickEntryWidget()
}
