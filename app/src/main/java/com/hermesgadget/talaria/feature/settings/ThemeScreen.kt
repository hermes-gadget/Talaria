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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import com.hermesgadget.talaria.ui.theme.LocalSpacing
import com.hermesgadget.talaria.ui.theme.ThemePreset
import com.hermesgadget.talaria.ui.theme.ThemePresets
import com.hermesgadget.talaria.ui.theme.isTalariaDarkTheme

@Composable
fun ThemeScreen(vm: ThemeViewModel = viewModel(factory = ThemeViewModel.factory())) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val darkTheme = isTalariaDarkTheme()

    ScreenScaffold("Themes", "Preset palettes", showProfileSwitcher = true) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                "Choose a palette for the whole app. Changes apply immediately and survive restart.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ThemePresets.all.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    row.forEach { preset ->
                        ThemePresetCard(
                            preset = preset,
                            selected = preset.id.equals(ui.selectedPresetId, ignoreCase = true),
                            darkTheme = darkTheme,
                            modifier = Modifier.weight(1f),
                            onClick = { vm.selectPreset(preset) },
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            Text(
                "Server skin",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = spacing.sm),
            )
            Text(
                "If Hermes exposes color fields, they can be layered onto the selected preset.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val syncEnabled = ui.serverSkin is ServerSkinState.Available && !ui.syncing
            OutlinedButton(
                enabled = syncEnabled,
                onClick = { vm.syncFromServer() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (ui.syncing) "Syncing…" else "Sync from server")
            }

            when (val state = ui.serverSkin) {
                ServerSkinState.Checking -> Text(
                    "Checking /api/config for skin fields…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is ServerSkinState.Available -> Text(
                    "Supported fields: ${state.skin.supportedFields.sorted().joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                is ServerSkinState.Unsupported -> Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is ServerSkinState.Unavailable -> Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ui.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ThemePresetCard(
    preset: ThemePreset,
    selected: Boolean,
    darkTheme: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = preset.scheme(darkTheme)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = colors.surface,
        contentColor = colors.onSurface,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    preset.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = colors.primary)
                }
            }
            Text(
                preset.description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                minLines = 2,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                ColorDot(colors.primary)
                ColorDot(colors.secondary)
                ColorDot(colors.tertiary)
                ColorDot(preset.monochromeAccent)
            }
        }
    }
}

@Composable
private fun ColorDot(color: Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(color, CircleShape),
    )
}
