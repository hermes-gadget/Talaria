/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.manage.cron

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.core.util.formatHermesTimestamp
import com.hermesgadget.talaria.domain.model.AutomationBlueprint
import com.hermesgadget.talaria.domain.model.CronDeliveryTarget
import com.hermesgadget.talaria.domain.model.CronRun
import com.hermesgadget.talaria.domain.model.ManageCronJob
import com.hermesgadget.talaria.ui.components.CollapsibleSection
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold

@Composable
fun CronScreen(vm: CronViewModel = viewModel(factory = CronViewModel.factory())) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var prompt by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("0 9 * * *") }
    var delivery by remember { mutableStateOf("local") }
    var deliveryMenuOpen by remember { mutableStateOf(false) }
    var editJob by remember { mutableStateOf<ManageCronJob?>(null) }
    var deleteJob by remember { mutableStateOf<ManageCronJob?>(null) }
    var createSubmitting by remember { mutableStateOf(false) }
    var submittedPrompt by remember { mutableStateOf<String?>(null) }
    var submittedName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ui) {
        when (val current = ui) {
            is CronUiState.Content -> if (!current.busy && createSubmitting) {
                createSubmitting = false
                // CronViewModel only returns to a non-busy, message-less state
                // after the server mutation and follow-up reload succeeded.
                if (current.message == null) {
                    if (prompt == submittedPrompt) prompt = ""
                    if (name == submittedName) name = ""
                }
                submittedPrompt = null
                submittedName = null
            }
            is CronUiState.Failure -> {
                createSubmitting = false
                submittedPrompt = null
                submittedName = null
            }
            CronUiState.Loading -> Unit
        }
    }

    when (val state = ui) {
        CronUiState.Loading -> ScreenScaffold("Cron", "Scheduled automations") { LoadingBox() }
        is CronUiState.Failure -> ScreenScaffold("Cron", "Scheduled automations") {
            ErrorBox(state.message) { vm.refresh() }
        }
        is CronUiState.Content -> {
            LaunchedEffect(state.deliveryTargets) {
                if (state.deliveryTargets.none { it.id == delivery }) {
                    delivery = state.deliveryTargets.firstOrNull()?.id ?: "local"
                }
            }
            ScreenScaffold(
                "Cron",
                "Scheduled automations",
                actions = {
                    TextButton(
                        enabled = !state.busy && !createSubmitting,
                        onClick = vm::refresh,
                    ) { Text("Refresh") }
                },
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        CollapsibleSection(
                            title = androidx.compose.ui.res.stringResource(R.string.minor_cron_create_section),
                            collapsible = true,
                        ) {
                            CreateCronCard(
                                name = name,
                                prompt = prompt,
                                schedule = schedule,
                                delivery = delivery,
                                deliveryTargets = state.deliveryTargets,
                                deliveryMenuOpen = deliveryMenuOpen,
                                onNameChange = { name = it },
                                onPromptChange = { prompt = it },
                                onScheduleChange = { schedule = it },
                                onDeliveryChange = { delivery = it; deliveryMenuOpen = false },
                                onDeliveryMenuChange = { deliveryMenuOpen = it },
                                busy = state.busy || createSubmitting,
                                onCreate = {
                                    if (!createSubmitting && !state.busy &&
                                        prompt.isNotBlank() && schedule.isNotBlank()
                                    ) {
                                        createSubmitting = true
                                        submittedPrompt = prompt
                                        submittedName = name
                                        vm.create(prompt, schedule, name, delivery)
                                    }
                                },
                            )
                        }
                    }
                    state.message?.let { message ->
                        item { Text(message, color = MaterialTheme.colorScheme.error) }
                    }
                    item {
                        CollapsibleSection(
                            title = androidx.compose.ui.res.stringResource(R.string.minor_cron_jobs_section),
                        ) {
                            if (state.jobs.isEmpty()) {
                                Text(
                                    "No scheduled jobs",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            } else {
                                state.jobs.forEach { job ->
                                    CronJobCard(
                                        job = job,
                                        runs = state.runs[job.id].orEmpty(),
                                        runsExpanded = job.id in state.expandedJobs,
                                        runsBusy = state.busyJobId == job.id,
                                        busy = state.busy,
                                        onEdit = { editJob = job },
                                        onPause = { vm.pause(job.id) },
                                        onResume = { vm.resume(job.id) },
                                        onTrigger = { vm.trigger(job.id) },
                                        onFireNow = { vm.fireNow(job.id) },
                                        onToggleRuns = { vm.toggleRuns(job.id) },
                                        onDelete = { deleteJob = job },
                                    )
                                }
                            }
                        }
                    }
                    if (state.blueprints.isNotEmpty()) {
                        item {
                            CollapsibleSection(
                                title = androidx.compose.ui.res.stringResource(R.string.minor_cron_blueprints_section),
                                collapsible = true,
                            ) {
                                Text(
                                    "Start a scheduled job from a reusable automation template.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                state.blueprints.forEach { blueprint ->
                                    BlueprintCard(blueprint = blueprint, busy = state.busy) { values ->
                                        vm.instantiateBlueprint(blueprint.key, values)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editJob?.let { job ->
        EditCronDialog(
            job = job,
            deliveryTargets = (ui as? CronUiState.Content)?.deliveryTargets.orEmpty(),
            onDismiss = { editJob = null },
            onSave = { promptValue, scheduleValue, deliveryValue ->
                editJob = null
                vm.update(job.id, promptValue, scheduleValue, deliveryValue)
            },
        )
    }
    deleteJob?.let { job ->
        AlertDialog(
            onDismissRequest = { deleteJob = null },
            title = { Text("Delete scheduled job?") },
            text = { Text("Permanently delete '${job.name ?: job.id}' from Hermes?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteJob = null
                    vm.delete(job.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteJob = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CreateCronCard(
    name: String,
    prompt: String,
    schedule: String,
    delivery: String,
    deliveryTargets: List<CronDeliveryTarget>,
    deliveryMenuOpen: Boolean,
    busy: Boolean,
    onNameChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onScheduleChange: (String) -> Unit,
    onDeliveryChange: (String) -> Unit,
    onDeliveryMenuChange: (Boolean) -> Unit,
    onCreate: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Create job", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Name (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                label = { Text("Prompt") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = schedule,
                onValueChange = onScheduleChange,
                label = { Text("Schedule (cron)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            ScheduleChips(schedule, onScheduleChange)
            DeliveryPicker(
                selected = delivery,
                targets = deliveryTargets,
                expanded = deliveryMenuOpen,
                onExpandedChange = onDeliveryMenuChange,
                onSelected = onDeliveryChange,
            )
            Button(
                onClick = onCreate,
                enabled = !busy && prompt.isNotBlank() && schedule.isNotBlank(),
            ) {
                Text("Create")
            }
        }
    }
}

@Composable
private fun ScheduleChips(schedule: String, onScheduleChange: (String) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            "Every 15m" to "*/15 * * * *",
            "Hourly" to "0 * * * *",
            "Daily 9:00" to "0 9 * * *",
            "Weekdays 9:00" to "0 9 * * 1-5",
        ).forEach { (label, value) ->
            FilterChip(
                selected = schedule == value,
                onClick = { onScheduleChange(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun DeliveryPicker(
    selected: String,
    targets: List<CronDeliveryTarget>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit,
) {
    Box {
        TextButton(onClick = { onExpandedChange(true) }) {
            Text("Delivery: ${targets.firstOrNull { it.id == selected }?.name ?: selected}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            targets.forEach { target ->
                DropdownMenuItem(
                    text = { Text(target.name) },
                    onClick = { onSelected(target.id) },
                )
            }
        }
    }
}

@Composable
private fun CronJobCard(
    job: ManageCronJob,
    runs: List<CronRun>,
    runsExpanded: Boolean,
    runsBusy: Boolean,
    busy: Boolean,
    onEdit: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onTrigger: () -> Unit,
    onFireNow: () -> Unit,
    onToggleRuns: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(job.name ?: job.id, style = MaterialTheme.typography.titleLarge)
            job.prompt?.takeIf { it.isNotBlank() }?.let { Text(it) }
            Text(
                listOfNotNull(
                    job.scheduleDisplay ?: job.scheduleExpression,
                    job.state ?: if (job.enabled == false) "disabled" else "enabled",
                    job.deliver,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "last=${formatHermesTimestamp(job.lastRunAt) ?: "—"} · " +
                    "next=${formatHermesTimestamp(job.nextRunAt) ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            job.lastError?.takeIf { it.isNotBlank() }?.let {
                Text("Last error: $it", color = MaterialTheme.colorScheme.error)
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onEdit, enabled = !busy) { Text("Edit") }
                if (job.enabled == false || job.state.equals("paused", ignoreCase = true)) {
                    TextButton(onClick = onResume, enabled = !busy) { Text("Resume") }
                } else {
                    TextButton(onClick = onPause, enabled = !busy) { Text("Pause") }
                }
                TextButton(onClick = onTrigger, enabled = !busy) { Text("Run") }
                TextButton(onClick = onFireNow, enabled = !busy) {
                    Text(androidx.compose.ui.res.stringResource(R.string.minor_cron_fire_now))
                }
                TextButton(onClick = onToggleRuns, enabled = !busy) {
                    Text(if (runsExpanded) "Hide runs" else "Runs")
                }
                TextButton(onClick = onDelete, enabled = !busy) { Text("Delete") }
            }
            if (runsBusy) Text("Loading run history…", style = MaterialTheme.typography.bodySmall)
            if (runsExpanded) {
                if (runs.isEmpty() && !runsBusy) {
                    Text("No runs recorded", style = MaterialTheme.typography.bodySmall)
                }
                runs.forEach { run -> CronRunRow(run) }
            }
        }
    }
}

@Composable
private fun CronRunRow(run: CronRun) {
    var expanded by remember(run.id) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                listOfNotNull(
                    run.status,
                    formatHermesTimestamp(run.startedAt),
                    run.endedAt?.let { "ended ${formatHermesTimestamp(it)}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
            )
            (run.output ?: run.preview ?: run.error)?.takeIf { it.isNotBlank() }?.let {
                Text(it.take(500), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide details" else "Show details")
            }
            if (expanded) {
                Text(
                    listOfNotNull(
                        run.endReason?.let { "reason: $it" },
                        run.model?.let { "model: $it" },
                        run.messageCount?.let { "$it messages" },
                        run.toolCallCount?.let { "$it tool calls" },
                        run.inputTokens?.let { "input $it" },
                        run.outputTokens?.let { "output $it" },
                    ).joinToString(" · ").ifBlank { run.raw },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (run.raw.isNotBlank()) {
                    Text(
                        run.raw.take(12_000),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditCronDialog(
    job: ManageCronJob,
    deliveryTargets: List<CronDeliveryTarget>,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var prompt by remember(job.id) { mutableStateOf(job.prompt.orEmpty()) }
    var schedule by remember(job.id) { mutableStateOf(job.scheduleExpression.orEmpty()) }
    var delivery by remember(job.id) { mutableStateOf(job.deliver ?: deliveryTargets.firstOrNull()?.id ?: "local") }
    var expanded by remember(job.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit cron") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = schedule,
                    onValueChange = { schedule = it },
                    label = { Text("Schedule") },
                    modifier = Modifier.fillMaxWidth(),
                )
                DeliveryPicker(
                    selected = delivery,
                    targets = deliveryTargets,
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    onSelected = { delivery = it; expanded = false },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(prompt, schedule, delivery) },
                enabled = prompt.isNotBlank() && schedule.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BlueprintCard(blueprint: AutomationBlueprint, busy: Boolean, onInstantiate: (Map<String, String>) -> Unit) {
    var values by remember(blueprint.key) { mutableStateOf(initialBlueprintValues(blueprint)) }
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            Modifier
                .padding(12.dp)
                .heightIn(min = 56.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(blueprint.title, style = MaterialTheme.typography.titleMedium)
            blueprint.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            blueprint.fields.forEach { field ->
                val current = values[field.name].orEmpty()
                if (field.options.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        field.options.forEach { option ->
                            FilterChip(
                                selected = current == option,
                                onClick = { values = values + (field.name to option) },
                                label = { Text(option) },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = current,
                        onValueChange = { values = values + (field.name to it) },
                        label = { Text(field.label ?: field.name) },
                        supportingText = field.help?.let { help -> { Text(help) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = field.type != "textarea",
                    )
                }
            }
            TextButton(onClick = { onInstantiate(values) }, enabled = !busy) {
                Text("Instantiate")
            }
        }
    }
}
