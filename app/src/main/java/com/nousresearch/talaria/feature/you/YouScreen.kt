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

package com.nousresearch.talaria.feature.you

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.BuildConfig
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.core.data.prefs.ThemeMode
import com.nousresearch.talaria.ui.components.ScreenScaffold
import com.nousresearch.talaria.worker.SyncScheduler

@Composable
fun YouScreen(onConnect: () -> Unit, onPrivacy: () -> Unit) {
    val settings = TalariaApp.instance.container.settingsStore
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(settings.notificationsEnabled) }
    var bgSync by remember { mutableStateOf(settings.backgroundSyncEnabled) }
    var tts by remember { mutableStateOf(settings.ttsEnabled) }
    var cloudStt by remember { mutableStateOf(settings.cloudSttOptIn) }
    var telemetry by remember { mutableStateOf(settings.telemetryEnabled) }
    var dynamicColor by remember { mutableStateOf(settings.dynamicColor) }
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    val active = TalariaApp.instance.container.connectionStore.activeProfile()

    ScreenScaffold("You", "Talaria ${BuildConfig.VERSION_NAME}") {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                "Active: ${active?.name ?: "none"}",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                active?.baseUrl ?: "Not connected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text("Connections")
            }
            TextButton(onClick = onPrivacy) { Text("Privacy policy") }

            Text(
                "Appearance",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            RowSwitch("Material You dynamic color", dynamicColor) {
                dynamicColor = it
                settings.dynamicColor = it
            }
            Text(
                "Theme: ${themeMode.name.lowercase()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(
                onClick = {
                    themeMode = when (themeMode) {
                        ThemeMode.DARK -> ThemeMode.SYSTEM
                        ThemeMode.SYSTEM -> ThemeMode.LIGHT
                        ThemeMode.LIGHT -> ThemeMode.DARK
                    }
                    settings.themeMode = themeMode
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cycle theme (now ${themeMode.name.lowercase()})") }

            Text(
                "Notifications & voice",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            RowSwitch("Notifications", notifications) {
                notifications = it
                settings.notificationsEnabled = it
            }
            RowSwitch("Background sync (WorkManager)", bgSync) {
                bgSync = it
                settings.backgroundSyncEnabled = it
                SyncScheduler.ensurePeriodic(context)
            }
            RowSwitch("Speak responses (TTS)", tts) {
                tts = it
                settings.ttsEnabled = it
            }
            RowSwitch("Allow cloud speech recognition", cloudStt) {
                cloudStt = it
                settings.cloudSttOptIn = it
            }
            RowSwitch("Telemetry (off by default)", telemetry) {
                telemetry = it
                settings.telemetryEnabled = it
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Battery optimization settings") }

            Spacer(Modifier.height(16.dp))
            Text(
                "Hermes API baseline: ${BuildConfig.HERMES_API_BASELINE}",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "Network only contacts your configured Hermes endpoints. Default telemetry=${BuildConfig.DEFAULT_TELEMETRY}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RowSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onChange)
        },
    )
}
