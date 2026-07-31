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
package com.nousresearch.talaria.feature.manage.curator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nousresearch.talaria.domain.model.CuratorState
import com.nousresearch.talaria.feature.manage.SimpleManageViewModel
import com.nousresearch.talaria.ui.components.ErrorBox
import com.nousresearch.talaria.ui.components.LoadingBox
import com.nousresearch.talaria.ui.components.ScreenScaffold
import com.nousresearch.talaria.ui.theme.LocalSpacing

@Composable
fun CuratorScreen() {
    val spacing = LocalSpacing.current
    val vm: SimpleManageViewModel = viewModel(
        factory = SimpleManageViewModel.factory { getCuratorState() },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state = ui.data as? CuratorState

    ScreenScaffold("Curator", actions = {
        TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
    }) {
        when {
            ui.loading && state == null -> LoadingBox()
            ui.error != null && state == null -> ErrorBox(ui.error!!, onRetry = { vm.refresh() })
            state == null -> Text("Curator unavailable.")
            else -> Column(verticalArrangement = Arrangement.spacedBy(spacing.itemGap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    StatusChip(if (state.enabled) "enabled" else "disabled", state.enabled)
                    if (state.paused) StatusChip("paused", false)
                }
                CuratorCard(state)
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, positive: Boolean) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = if (positive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            disabledLabelColor = if (positive) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    )
}

@Composable
private fun CuratorCard(state: CuratorState) {
    val spacing = LocalSpacing.current
    val rows = listOfNotNull(
        state.interval_hours?.let { "Interval" to "${it.toInt()}h" },
        state.last_run_at?.let { "Last run" to it.replace('T', ' ').substringBefore('.') },
        state.min_idle_hours?.let { "Min idle" to "${it}h" },
        state.stale_after_days?.let { "Stale after" to "$it days" },
        state.archive_after_days?.let { "Archive after" to "$it days" },
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.cardPad)) {
            rows.forEach { (k, v) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = spacing.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
