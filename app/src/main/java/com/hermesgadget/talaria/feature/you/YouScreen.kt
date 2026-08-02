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

package com.hermesgadget.talaria.feature.you

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.hermesgadget.talaria.BuildConfig
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.prefs.ThemeMode
import com.hermesgadget.talaria.core.data.prefs.AppLocale
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import com.hermesgadget.talaria.ui.theme.LocalSpacing
import com.hermesgadget.talaria.worker.SyncScheduler
import com.hermesgadget.talaria.core.notifications.AgentTaskNotificationService

@Composable
fun YouScreen(onConnect: () -> Unit) {
    val settings = TalariaApp.instance.container.settingsStore
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    var notifications by remember { mutableStateOf(settings.notificationsEnabled) }
    var agentPermissions by remember { mutableStateOf(settings.notifyAgentPermissions) }
    var taskCompletions by remember { mutableStateOf(settings.notifyTaskCompletions) }
    var bgSync by remember { mutableStateOf(settings.backgroundSyncEnabled) }
    var tts by remember { mutableStateOf(settings.ttsEnabled) }
    var cloudStt by remember { mutableStateOf(settings.cloudSttOptIn) }
    var telemetry by remember { mutableStateOf(settings.telemetryEnabled) }
    var dynamicColor by remember { mutableStateOf(settings.dynamicColor) }
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var appLocale by remember { mutableStateOf(TalariaApp.instance.container.localeManager.currentLocale()) }
    var languagePickerOpen by remember { mutableStateOf(false) }
    var privacyExpanded by remember { mutableStateOf(false) }
    val active = TalariaApp.instance.container.connectionStore.activeProfile()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            notifications = false
            settings.notificationsEnabled = false
            AgentTaskNotificationService.stopAll(context)
        }
    }

    if (languagePickerOpen) {
        AlertDialog(
            onDismissRequest = { languagePickerOpen = false },
            confirmButton = {
                TextButton(onClick = { languagePickerOpen = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            title = { Text(stringResource(R.string.you_language)) },
            text = {
                Column {
                    AppLocale.entries.forEach { locale ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    appLocale = locale
                                    languagePickerOpen = false
                                    TalariaApp.instance.container.localeManager.setLocale(context, locale)
                                    (context as? Activity)?.recreate()
                                }
                                .padding(vertical = spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = appLocale == locale, onClick = null)
                            Text(localeLabel(locale), modifier = Modifier.padding(start = spacing.sm))
                        }
                    }
                }
            },
        )
    }

    ScreenScaffold(
        stringResource(R.string.you_title),
        stringResource(R.string.you_version, BuildConfig.VERSION_NAME),
        showProfileSwitcher = true,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.you_active, active?.name ?: stringResource(R.string.you_none)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                active?.baseUrl ?: stringResource(R.string.you_not_connected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.md))
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_connections))
            }

            SectionHeader(stringResource(R.string.you_appearance))
            RowSwitch(stringResource(R.string.you_dynamic_color), dynamicColor) {
                dynamicColor = it
                settings.dynamicColor = it
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.you_theme), style = MaterialTheme.typography.bodyMedium)
                val modes = listOf(ThemeMode.DARK, ThemeMode.SYSTEM, ThemeMode.LIGHT)
                SingleChoiceSegmentedButtonRow {
                    modes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = {
                                themeMode = mode
                                settings.themeMode = mode
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) { Text(themeLabel(mode)) }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { languagePickerOpen = true }
                    .padding(vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.you_language), style = MaterialTheme.typography.bodyMedium)
                Text(
                    localeLabel(appLocale),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionHeader(stringResource(R.string.you_notifications_voice))
            RowSwitch(stringResource(R.string.you_notifications), notifications) {
                notifications = it
                settings.notificationsEnabled = it
                if (!it) {
                    AgentTaskNotificationService.stopAll(context)
                } else if (
                    Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    settings.notificationPermissionRequested = true
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            AnimatedVisibility(visible = notifications) {
                Column {
                    RowSwitch(stringResource(R.string.you_agent_permissions), agentPermissions) {
                        agentPermissions = it
                        settings.notifyAgentPermissions = it
                        if (!it && !taskCompletions) AgentTaskNotificationService.stopAll(context)
                    }
                    RowSwitch(stringResource(R.string.you_agent_completion), taskCompletions) {
                        taskCompletions = it
                        settings.notifyTaskCompletions = it
                        if (!it && !agentPermissions) AgentTaskNotificationService.stopAll(context)
                    }
                }
            }
            RowSwitch(stringResource(R.string.you_background_sync), bgSync) {
                bgSync = it
                settings.backgroundSyncEnabled = it
                SyncScheduler.ensurePeriodic(context)
            }
            RowSwitch(stringResource(R.string.you_speak_responses), tts) {
                tts = it
                settings.ttsEnabled = it
            }
            RowSwitch(stringResource(R.string.you_cloud_speech), cloudStt) {
                cloudStt = it
                settings.cloudSttOptIn = it
            }
            RowSwitch(stringResource(R.string.you_telemetry), telemetry) {
                telemetry = it
                settings.telemetryEnabled = it
            }

            // Privacy folded in as an expandable section (was a standalone screen).
            SectionHeader(stringResource(R.string.you_privacy))
            TextButton(
                onClick = { privacyExpanded = !privacyExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.you_local_first),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Icon(
                    if (privacyExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (privacyExpanded) R.string.common_collapse else R.string.common_expand,
                    ),
                )
            }
            AnimatedVisibility(visible = privacyExpanded) {
                Text(
                    stringResource(R.string.you_privacy_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = spacing.sm),
                )
            }

            Spacer(Modifier.height(spacing.md))
            Text(
                stringResource(R.string.you_api_baseline, BuildConfig.HERMES_API_BASELINE),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                stringResource(R.string.you_network_description, BuildConfig.DEFAULT_TELEMETRY),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.xl))
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

@Composable
private fun RowSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun localeLabel(locale: AppLocale): String = when (locale) {
    AppLocale.SYSTEM -> stringResource(R.string.language_system_default)
    AppLocale.ENGLISH -> stringResource(R.string.language_english)
    AppLocale.JAPANESE -> stringResource(R.string.language_japanese)
    AppLocale.SIMPLIFIED_CHINESE -> stringResource(R.string.language_simplified_chinese)
    AppLocale.TRADITIONAL_CHINESE -> stringResource(R.string.language_traditional_chinese)
    AppLocale.ARABIC -> stringResource(R.string.language_arabic)
}

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
}
