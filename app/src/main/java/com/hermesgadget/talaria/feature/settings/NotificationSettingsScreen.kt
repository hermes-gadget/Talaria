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

package com.hermesgadget.talaria.feature.settings

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import com.hermesgadget.talaria.ui.theme.LocalSpacing
import kotlinx.coroutines.delay

@Composable
fun NotificationSettingsScreen(
    vm: NotificationSettingsViewModel = viewModel(factory = NotificationSettingsViewModel.factory()),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = LocalSpacing.current

    LaunchedEffect(Unit) {
        while (true) {
            vm.refresh()
            delay(60_000L)
        }
    }

    ScreenScaffold("Notification settings", "Agent task alerts", showProfileSwitcher = true) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Quiet hours")
            SettingsRowSwitch("Quiet hours", ui.quietHoursEnabled, vm::setQuietHoursEnabled)
            Text(
                "Agent notifications remain monitored, but alerts are delivered silently during this window.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TimeRow(
                label = "Start",
                value = formatMinutes(ui.quietHoursStartMinutes, context),
                enabled = ui.quietHoursEnabled,
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> vm.setQuietHoursStart(hour * 60 + minute) },
                        ui.quietHoursStartMinutes / 60,
                        ui.quietHoursStartMinutes % 60,
                        DateFormat.is24HourFormat(context),
                    ).show()
                },
            )
            TimeRow(
                label = "End",
                value = formatMinutes(ui.quietHoursEndMinutes, context),
                enabled = ui.quietHoursEnabled,
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> vm.setQuietHoursEnd(hour * 60 + minute) },
                        ui.quietHoursEndMinutes / 60,
                        ui.quietHoursEndMinutes % 60,
                        DateFormat.is24HourFormat(context),
                    ).show()
                },
            )
            Text(
                if (ui.quietHoursActive) {
                    "Quiet hours active now"
                } else {
                    "Quiet hours inactive"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (ui.quietHoursActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = spacing.xs),
            )

            SectionHeader("Agent channels")
            SettingsRowSwitch(
                "Per-agent channels",
                ui.perAgentChannelsEnabled,
                vm::setPerAgentChannelsEnabled,
            )
            Text(
                "Sessions are assigned to a stable set of Android channels so each agent can be muted independently.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.xs))
            ui.channels.forEach { status ->
                ChannelCard(status)
            }

            SectionHeader("Test")
            Button(
                onClick = vm::sendTestNotification,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Send test notification")
            }
            ui.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
            Spacer(Modifier.height(spacing.xl))
        }
    }
}

@Composable
private fun TimeRow(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onClick, enabled = enabled) { Text(value) }
    }
}

@Composable
private fun SettingsRowSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChannelCard(status: NotificationChannelStatus) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(status.channel.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                status.channel.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                status.agents.takeIf { it.isNotEmpty() }?.joinToString() ?: "No active agent session",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    val spacing = LocalSpacing.current
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = spacing.lg, bottom = spacing.xs),
    )
}

private fun formatMinutes(minutes: Int, context: android.content.Context): String {
    val hour = Math.floorMod(minutes / 60, 24)
    val minute = Math.floorMod(minutes, 60)
    if (DateFormat.is24HourFormat(context)) return "%02d:%02d".format(hour, minute)
    val suffix = if (hour < 12) "AM" else "PM"
    val displayHour = when (val hour12 = hour % 12) {
        0 -> 12
        else -> hour12
    }
    return "%d:%02d %s".format(displayHour, minute, suffix)
}
