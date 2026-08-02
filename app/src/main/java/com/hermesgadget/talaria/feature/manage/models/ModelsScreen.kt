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
package com.hermesgadget.talaria.feature.manage.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.domain.model.ModelProvider
import com.hermesgadget.talaria.ui.components.CollapsibleSection
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: ModelsViewModel = viewModel(factory = ModelsViewModel.factory())) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    ui.confirmMessage?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissModelConfirmation,
            title = { Text(stringResource(R.string.models_confirm_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::confirmPendingModel) {
                    Text(stringResource(R.string.models_use_model))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissModelConfirmation) {
                    Text(stringResource(R.string.models_cancel))
                }
            },
        )
    }

    ScreenScaffold(
        title = stringResource(R.string.models_title),
        subtitle = ui.currentModel?.let {
            stringResource(R.string.models_subtitle_current, it)
        } ?: stringResource(R.string.models_subtitle_default),
        showProfileSwitcher = true,
        actions = {
            TextButton(onClick = vm::refresh) {
                Text(stringResource(R.string.models_refresh))
            }
        },
    ) {
        when {
            ui.loading && ui.providers.isEmpty() -> LoadingBox()
            ui.error != null && ui.providers.isEmpty() -> ErrorBox(ui.error!!) { vm.refresh() }
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ui.message?.let { msg ->
                    item(key = "model-message") {
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }

                item(key = "providers") {
                    CollapsibleSection(
                        title = stringResource(R.string.models_providers_section),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ui.providers.forEach { provider ->
                                ProviderCard(
                                    provider = provider,
                                    currentModel = ui.currentModel,
                                    settingModel = ui.setting,
                                    onSet = { vm.setModel(provider.slug, it) },
                                )
                            }
                        }
                    }
                }

                item(key = "moa") {
                    CollapsibleSection(
                        title = stringResource(R.string.models_moa_section),
                        collapsible = true,
                    ) {
                        when {
                            ui.moaLoading -> LoadingInline(R.string.models_moa_loading)
                            ui.moa != null -> MoaEditor(
                                config = ui.moa,
                                saving = ui.moaSaving,
                                saved = ui.moaSaved,
                                error = ui.moaError,
                                onRetry = vm::getMoaConfig,
                                onSave = vm::putMoaConfig,
                            )
                            else -> UnavailableSection(
                                error = ui.moaError,
                                unavailable = R.string.models_moa_unavailable,
                                onRetry = vm::getMoaConfig,
                            )
                        }
                    }
                }

                item(key = "auxiliary") {
                    CollapsibleSection(
                        title = stringResource(R.string.models_auxiliary_section),
                        collapsible = true,
                    ) {
                        when {
                            ui.auxiliaryLoading -> LoadingInline(R.string.models_auxiliary_loading)
                            ui.auxiliary != null -> AuxiliaryModelsSection(
                                models = ui.auxiliary,
                                onRefresh = vm::getAuxiliaryModels,
                            )
                            else -> UnavailableSection(
                                error = ui.auxiliaryError,
                                unavailable = R.string.models_auxiliary_unavailable,
                                onRetry = vm::getAuxiliaryModels,
                            )
                        }
                    }
                }

                item(key = "recommended") {
                    CollapsibleSection(
                        title = stringResource(R.string.models_recommended_section),
                        collapsible = true,
                    ) {
                        RecommendedDefaultsSection(
                            providers = ui.providers,
                            recommendations = ui.recommendedDefaults,
                            loading = ui.recommendedLoading,
                            errors = ui.recommendedErrors,
                            onLoad = vm::getRecommendedDefaultModel,
                            onUse = { provider, model -> vm.setModel(provider, model) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingInline(message: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Text(stringResource(message), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun UnavailableSection(
    error: String?,
    unavailable: Int,
    onRetry: () -> Unit,
) {
    Text(
        error?.takeIf { it.isNotBlank() } ?: stringResource(unavailable),
        color = if (error.isNullOrBlank()) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = onRetry) {
        Text(stringResource(R.string.models_retry))
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
                    Text(
                        provider.name.ifBlank { provider.slug },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val modelCount = stringResource(R.string.models_provider_count, provider.total_models)
                    val notAuthenticated = stringResource(R.string.models_not_authenticated)
                    val details = buildList {
                        add(modelCount)
                        provider.source?.takeIf { it.isNotBlank() }?.let { add(it) }
                        if (!provider.authenticated) add(notAuthenticated)
                    }.joinToString(" · ")
                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (provider.is_current) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.models_current)) },
                        enabled = false,
                    )
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

@Composable
private fun AuxiliaryModelsSection(
    models: AuxiliaryModelsUi,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.models_auxiliary_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val notSet = stringResource(R.string.models_not_set)
        val main = listOf(models.mainProvider, models.mainModel)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { notSet }
        Text(
            stringResource(R.string.models_auxiliary_main, main),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (models.tasks.isEmpty()) {
            Text(
                stringResource(R.string.models_auxiliary_no_tasks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            models.tasks.forEach { task ->
                AuxiliaryTaskCard(task)
            }
        }
        TextButton(onClick = onRefresh) {
            Text(stringResource(R.string.models_refresh))
        }
    }
}

@Composable
private fun AuxiliaryTaskCard(task: AuxiliaryTaskAssignmentUi) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            val taskName = task.task.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.models_auxiliary_unknown_task)
            Text(
                taskName,
                style = MaterialTheme.typography.titleSmall,
            )
            val isAuto = task.provider.isBlank() || task.provider.equals("auto", ignoreCase = true)
            val assignment = if (isAuto) {
                stringResource(R.string.models_auxiliary_auto)
            } else if (task.model.isBlank()) {
                stringResource(R.string.models_auxiliary_provider_default, task.provider)
            } else {
                stringResource(R.string.models_auxiliary_assignment, task.provider, task.model)
            }
            Text(
                assignment,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            task.baseUrl.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.models_auxiliary_base_url, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecommendedDefaultsSection(
    providers: List<ModelProvider>,
    recommendations: Map<String, RecommendedDefaultModelUi>,
    loading: Set<String>,
    errors: Map<String, String>,
    onLoad: (String) -> Unit,
    onUse: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.models_recommended_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (providers.isEmpty()) {
            Text(
                stringResource(R.string.models_recommended_no_providers),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        providers.forEach { provider ->
            val slug = provider.slug
            val recommendation = recommendations[slug]
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        provider.name.ifBlank { slug },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    when {
                        recommendation != null && recommendation.model.isNotBlank() -> {
                            Text(
                                stringResource(
                                    R.string.models_recommended_model,
                                    recommendation.model,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            recommendation.freeTier?.let { freeTier ->
                                Text(
                                    stringResource(
                                        if (freeTier) {
                                            R.string.models_recommended_free_tier
                                        } else {
                                            R.string.models_recommended_paid_tier
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onUse(slug, recommendation.model) }) {
                                Text(stringResource(R.string.models_recommended_use))
                            }
                        }
                        recommendation != null -> {
                            Text(
                                stringResource(R.string.models_recommended_no_model),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        errors[slug] != null -> {
                            Text(
                                errors[slug].orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (slug in loading) {
                        Text(
                            stringResource(R.string.models_recommended_loading),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        TextButton(
                            onClick = { onLoad(slug) },
                            enabled = slug.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.models_recommended_get))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoaEditor(
    config: MoaConfigDraft,
    saving: Boolean,
    saved: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onSave: (MoaConfigDraft) -> Unit,
) {
    var draft by remember(config) { mutableStateOf(config) }
    var selectedPreset by remember(config) { mutableStateOf(config.defaultPreset) }
    var newPresetName by remember { mutableStateOf("") }
    val preset = draft.presets[selectedPreset]

    if (preset == null) {
        UnavailableSection(
            error = null,
            unavailable = R.string.models_moa_unavailable,
            onRetry = onRetry,
        )
        return
    }

    fun updateSelected(update: (MoaPresetDraft) -> MoaPresetDraft) {
        draft = draft.updatePreset(selectedPreset, update)
    }

    val hasInvalidSlot = draft.presets.values.any { candidate ->
        candidate.referenceModels.isEmpty() ||
            candidate.referenceModels.any(::isInvalidMoaSlot) ||
            isInvalidMoaSlot(candidate.aggregator)
    }
    val hasRecursiveSlot = draft.presets.values.any { candidate ->
        candidate.referenceModels.any(::isRecursiveMoaSlot) ||
            isRecursiveMoaSlot(candidate.aggregator)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.models_moa_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            draft.presets.keys.forEach { name ->
                FilterChip(
                    selected = selectedPreset == name,
                    onClick = { selectedPreset = name },
                    label = { Text(name) },
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.models_moa_default_label, draft.defaultPreset),
                modifier = Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = { draft = draft.copy(defaultPreset = selectedPreset) },
                enabled = selectedPreset != draft.defaultPreset,
            ) {
                Text(stringResource(R.string.models_moa_set_default))
            }
            TextButton(
                onClick = {
                    val next = draft.removePreset(selectedPreset)
                    draft = next
                    selectedPreset = next.presets.keys.first()
                },
                enabled = draft.presets.size > 1,
            ) {
                Text(stringResource(R.string.models_moa_delete_preset))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newPresetName,
                onValueChange = { newPresetName = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.models_moa_new_preset)) },
            )
            OutlinedButton(
                onClick = {
                    val name = newPresetName.trim()
                    if (name.isNotBlank() && name !in draft.presets) {
                        draft = draft.copy(presets = draft.presets + (name to preset))
                        selectedPreset = name
                        newPresetName = ""
                    }
                },
                enabled = newPresetName.trim().isNotBlank() &&
                    newPresetName.trim() !in draft.presets,
            ) {
                Text(stringResource(R.string.models_moa_add_preset))
            }
        }

        CollapsibleSection(
            title = stringResource(R.string.models_moa_reference_models),
            collapsible = true,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                preset.referenceModels.forEachIndexed { index, slot ->
                    MoaSlotEditor(
                        slot = slot,
                        label = stringResource(R.string.models_moa_reference_slot, index + 1),
                        removable = preset.referenceModels.size > 1,
                        onChange = { changed ->
                            updateSelected { previous ->
                                previous.copy(
                                    referenceModels = previous.referenceModels.mapIndexed { slotIndex, item ->
                                        if (slotIndex == index) changed else item
                                    },
                                )
                            }
                        },
                        onRemove = {
                            updateSelected { previous ->
                                previous.copy(
                                    referenceModels = previous.referenceModels.filterIndexed { slotIndex, _ ->
                                        slotIndex != index
                                    },
                                )
                            }
                        },
                    )
                }
                OutlinedButton(
                    onClick = {
                        updateSelected { previous ->
                            previous.copy(
                                referenceModels = previous.referenceModels + MoaSlotDraft(
                                    provider = previous.aggregator.provider,
                                    model = previous.aggregator.model,
                                ),
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.models_moa_add_reference))
                }
            }
        }

        CollapsibleSection(
            title = stringResource(R.string.models_moa_aggregator),
            collapsible = true,
        ) {
            MoaSlotEditor(
                slot = preset.aggregator,
                label = stringResource(R.string.models_moa_aggregator_slot),
                removable = false,
                onChange = { changed -> updateSelected { it.copy(aggregator = changed) } },
                onRemove = {},
            )
        }

        if (hasRecursiveSlot) {
            Text(
                stringResource(R.string.models_moa_recursive_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (hasInvalidSlot) {
            Text(
                stringResource(R.string.models_moa_invalid_slots),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        error?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (saved) {
            Text(
                stringResource(R.string.models_moa_saved),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(draft) },
                enabled = !saving && !hasInvalidSlot && !hasRecursiveSlot,
            ) {
                Text(
                    stringResource(
                        if (saving) R.string.models_moa_saving else R.string.models_moa_save,
                    ),
                )
            }
            OutlinedButton(
                onClick = {
                    draft = config
                    selectedPreset = config.defaultPreset
                    newPresetName = ""
                },
                enabled = !saving,
            ) {
                Text(stringResource(R.string.models_moa_reset))
            }
        }
    }
}

@Composable
private fun MoaSlotEditor(
    slot: MoaSlotDraft,
    label: String,
    removable: Boolean,
    onChange: (MoaSlotDraft) -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = slot.provider,
                onValueChange = { onChange(slot.copy(provider = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.models_moa_provider)) },
            )
            OutlinedTextField(
                value = slot.model,
                onValueChange = { onChange(slot.copy(model = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.models_moa_model)) },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = slot.enabled,
                    onCheckedChange = { onChange(slot.copy(enabled = it)) },
                )
                Text(stringResource(R.string.models_moa_enabled))
                Spacer(Modifier.weight(1f))
                if (removable) {
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.models_moa_remove))
                    }
                }
            }
        }
    }
}

private fun isInvalidMoaSlot(slot: MoaSlotDraft): Boolean =
    slot.provider.isBlank() || slot.model.isBlank()

private fun isRecursiveMoaSlot(slot: MoaSlotDraft): Boolean =
    slot.provider.equals("moa", ignoreCase = true)
