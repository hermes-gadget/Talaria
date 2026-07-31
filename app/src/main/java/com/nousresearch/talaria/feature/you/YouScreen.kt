package com.nousresearch.talaria.feature.you

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nousresearch.talaria.BuildConfig
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.ui.components.ScreenScaffold

@Composable
fun YouScreen(onConnect: () -> Unit, onPrivacy: () -> Unit) {
    val settings = TalariaApp.instance.container.settingsStore
    var notifications by remember { mutableStateOf(settings.notificationsEnabled) }
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
            RowSwitch("Telemetry (off by default)", telemetry) {
                telemetry = it; settings.telemetryEnabled = it
            }
        }
    }
}

@Composable
private fun RowSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
