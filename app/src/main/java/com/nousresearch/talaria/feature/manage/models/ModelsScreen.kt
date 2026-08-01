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
package com.nousresearch.talaria.feature.manage.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import com.nousresearch.talaria.domain.model.ModelProvider
import com.nousresearch.talaria.ui.components.ErrorBox
import com.nousresearch.talaria.ui.components.LoadingBox
import com.nousresearch.talaria.ui.components.ScreenScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: ModelsViewModel = viewModel(factory = ModelsViewModel.factory())) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    ui.confirmMessage?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissModelConfirmation,
            title = { Text("Confirm model") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::confirmPendingModel) { Text("Use model") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissModelConfirmation) { Text("Cancel") }
            },
        )
    }

    ScreenScaffold(
        title = "Models",
        subtitle = ui.currentModel?.let { "current · $it" } ?: "providers & models",
        showProfileSwitcher = true,
        actions = { TextButton(onClick = { vm.refresh() }) { Text("Refresh") } },
    ) {
        when {
            ui.loading && ui.providers.isEmpty() -> LoadingBox()
            ui.error != null && ui.providers.isEmpty() -> ErrorBox(ui.error!!) { vm.refresh() }
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ui.message?.let { msg ->
                    item {
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                items(ui.providers, key = { it.slug }) { p ->
                    ProviderCard(
                        provider = p,
                        currentModel = ui.currentModel,
                        settingModel = ui.setting,
                        onSet = { vm.setModel(p.slug, it) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCard(
    provider: ModelProvider,
    currentModel: String?,
    settingModel: String?,
    onSet: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(provider.is_current) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(provider.name.ifBlank { provider.slug }, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${provider.total_models} models" +
                            (provider.source?.let { " · $it" } ?: "") +
                            (if (!provider.authenticated) " · not authenticated" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (provider.is_current) {
                    AssistChip(onClick = {}, label = { Text("current") }, enabled = false)
                }
            }
            provider.warning?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (expanded) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    provider.models.forEach { model ->
                        val isCurrent = model == currentModel
                        FilterChip(
                            selected = isCurrent,
                            enabled = settingModel == null,
                            onClick = { if (!isCurrent) onSet(model) },
                            label = { Text(model) },
                            leadingIcon = if (isCurrent) {
                                { Icon(Icons.Filled.CheckCircle, contentDescription = null) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}
