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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import com.nousresearch.talaria.BuildConfig
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.ui.components.ScreenScaffold
import com.nousresearch.talaria.worker.SyncScheduler
import androidx.compose.ui.Modifier

@Composable
fun YouScreen(onConnect: () -> Unit, onPrivacy: () -> Unit) {
    val settings = TalariaApp.instance.container.settingsStore
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(settings.notificationsEnabled) }
    var bgSync by remember { mutableStateOf(settings.backgroundSyncEnabled) }
    var tts by remember { mutableStateOf(settings.ttsEnabled) }
    var cloudStt by remember { mutableStateOf(settings.cloudSttOptIn) }
    var telemetry by remember { mutableStateOf(settings.telemetryEnabled) }
    val active = TalariaApp.instance.container.connectionStore.activeProfile()

    ScreenScaffold("You", "Talaria ${BuildConfig.VERSION_NAME}") {
        Column {
            Text("Active: ${active?.name ?: "none"}", style = MaterialTheme.typography.titleLarge)
            Text(active?.baseUrl ?: "Not connected", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connections") }
            TextButton(onClick = onPrivacy) { Text("Privacy policy") }
            RowSwitch("Notifications", notifications) {
                notifications = it; settings.notificationsEnabled = it
            }
            RowSwitch("Background sync (WorkManager)", bgSync) {
                bgSync = it; settings.backgroundSyncEnabled = it
                SyncScheduler.ensurePeriodic(context)
            }
            RowSwitch("Speak responses (TTS)", tts) {
                tts = it; settings.ttsEnabled = it
            }
            RowSwitch("Allow cloud speech recognition", cloudStt) {
                cloudStt = it; settings.cloudSttOptIn = it
            }
            RowSwitch("Telemetry (off by default)", telemetry) {
                telemetry = it; settings.telemetryEnabled = it
            }
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Battery optimization settings") }
            Text(
                "Hermes API baseline: ${BuildConfig.HERMES_API_BASELINE}",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "Network only contacts your configured Hermes endpoints. Default telemetry=${BuildConfig.DEFAULT_TELEMETRY}.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun RowSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
