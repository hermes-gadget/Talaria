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
package com.hermesgadget.talaria.feature.manage.cron

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.util.formatHermesTimestamp
import com.hermesgadget.talaria.domain.model.CronJob
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch

@Composable
fun CronScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    var jobs by remember { mutableStateOf<List<CronJob>>(emptyList()) }
    var prompt by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("0 9 * * *") }
    var editJob by remember { mutableStateOf<CronJob?>(null) }
    var editPrompt by remember { mutableStateOf("") }
    var editSchedule by remember { mutableStateOf("") }
    var deleteJob by remember { mutableStateOf<CronJob?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() = scope.launch {
        repo.getCron()
            .onSuccess { jobs = it }
            .onFailure { message = it.message }
    }
    LaunchedEffect(Unit) { reload() }

    ScreenScaffold("Cron", "Scheduled automations") {
        OutlinedTextField(prompt, { prompt = it }, label = { Text("Prompt") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(schedule, { schedule = it }, label = { Text("Schedule (cron)") }, modifier = Modifier.fillMaxWidth())
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            listOf(
                "Every 15m" to "*/15 * * * *",
                "Hourly" to "0 * * * *",
                "Daily 9:00" to "0 9 * * *",
                "Weekdays 9:00" to "0 9 * * 1-5",
            ).forEach { (label, cron) ->
                FilterChip(
                    selected = schedule == cron,
                    onClick = { schedule = cron },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
        Button(onClick = {
            scope.launch {
                repo.createCron(prompt, schedule, null, "local")
                    .onSuccess { prompt = ""; reload() }
                    .onFailure { message = it.message }
            }
        }) { Text("Create") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn {
            items(jobs, key = { it.id }) { job ->
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(job.name ?: job.id, style = MaterialTheme.typography.titleLarge)
                        Text(job.prompt ?: "")
                        Text("${job.schedule} · ${job.state} · ${job.deliver}")
                        Text(
                            "last=${formatHermesTimestamp(job.last_run) ?: "—"} · " +
                                "next=${formatHermesTimestamp(job.next_run) ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            TextButton(onClick = {
                                editJob = job
                                editPrompt = job.prompt.orEmpty()
                                editSchedule = job.schedule.orEmpty()
                            }) { Text("Edit") }
                            TextButton(onClick = { scope.launch { repo.pauseCron(job.id); reload() } }) { Text("Pause") }
                            TextButton(onClick = { scope.launch { repo.resumeCron(job.id); reload() } }) { Text("Resume") }
                            TextButton(onClick = { scope.launch { repo.triggerCron(job.id); reload() } }) { Text("Run") }
                            TextButton(onClick = { deleteJob = job }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    editJob?.let { job ->
        AlertDialog(
            onDismissRequest = { editJob = null },
            title = { Text("Edit cron") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editPrompt,
                        onValueChange = { editPrompt = it },
                        label = { Text("Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editSchedule,
                        onValueChange = { editSchedule = it },
                        label = { Text("Schedule") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.updateCron(job.id, editPrompt, editSchedule)
                            .onSuccess {
                                editJob = null
                                reload()
                            }
                            .onFailure { message = it.message }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editJob = null }) { Text("Cancel") }
            },
        )
    }

    deleteJob?.let { job ->
        AlertDialog(
            onDismissRequest = { deleteJob = null },
            title = { Text("Delete scheduled job?") },
            text = { Text("Permanently delete '${job.name ?: job.id}'?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteJob = null
                    scope.launch {
                        repo.deleteCron(job.id)
                            .onSuccess { reload() }
                            .onFailure { message = it.message }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteJob = null }) { Text("Cancel") } },
        )
    }
}
