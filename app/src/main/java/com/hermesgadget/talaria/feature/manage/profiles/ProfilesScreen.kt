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
package com.hermesgadget.talaria.feature.manage.profiles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.hermesgadget.talaria.domain.model.ProfileInfo
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch

@Composable
fun ProfilesScreen(onShortcut: ((String) -> Unit)? = null) {
    val container = TalariaApp.instance.container
    val repo = container.hermesRepository
    val connectionStore = container.connectionStore
    var list by remember { mutableStateOf<List<ProfileInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var activeName by remember { mutableStateOf<String?>(null) }
    var createName by remember { mutableStateOf("") }
    var createDescription by remember { mutableStateOf("") }
    var cloneFrom by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameName by remember { mutableStateOf("") }
    var descriptionTarget by remember { mutableStateOf<String?>(null) }
    var descriptionText by remember { mutableStateOf("") }
    var soulTarget by remember { mutableStateOf<String?>(null) }
    var soulText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() = scope.launch {
        repo.getProfiles(force = true)
            .onSuccess {
                list = it
                error = null
            }
            .onFailure { error = it.message }
        repo.getActiveProfileName().onSuccess { activeName = it ?: "default" }
    }
    LaunchedEffect(Unit) { reload() }

    fun switchActive(name: String, after: (() -> Unit)? = null) {
        scope.launch {
            repo.setActiveProfileName(name.ifBlank { "default" })
                .onSuccess {
                    connectionStore.setManagementProfile(name)
                    container.clientFactory.invalidate()
                    message = if (name.isBlank()) "Switched to default" else "Managing $name"
                    reload()
                    after?.invoke()
                }
                .onFailure { message = it.message }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename profile") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    label = { Text("New name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(enabled = renameName.isNotBlank(), onClick = {
                    val newName = renameName.trim()
                    renameTarget = null
                    scope.launch {
                        repo.renameProfile(target, newName)
                            .onSuccess {
                                if (connectionStore.activeProfile()?.managementProfile == target) {
                                    connectionStore.setManagementProfile(newName)
                                    container.clientFactory.invalidate()
                                }
                                message = "Renamed $target to $newName"
                                reload()
                            }
                            .onFailure { error = it.message }
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    descriptionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { descriptionTarget = null },
            title = { Text("Profile description") },
            text = {
                OutlinedTextField(
                    value = descriptionText,
                    onValueChange = { descriptionText = it },
                    label = { Text("Description (blank clears)") },
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = descriptionText
                    descriptionTarget = null
                    scope.launch {
                        repo.updateProfileDescription(target, value)
                            .onSuccess { message = "Description saved"; reload() }
                            .onFailure { error = it.message }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { descriptionTarget = null }) { Text("Cancel") } },
        )
    }

    soulTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { soulTarget = null },
            title = { Text("$target · SOUL.md") },
            text = {
                OutlinedTextField(
                    value = soulText,
                    onValueChange = { soulText = it },
                    label = { Text("Agent identity and behavior") },
                    minLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = soulText
                    soulTarget = null
                    scope.launch {
                        repo.updateProfileSoul(target, value)
                            .onSuccess { message = "SOUL.md saved for $target" }
                            .onFailure { error = it.message }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { soulTarget = null }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete profile?") },
            text = { Text("Permanently delete '$target' and its isolated Hermes home?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        repo.deleteProfile(target)
                            .onSuccess {
                                if (connectionStore.activeProfile()?.managementProfile == target) {
                                    connectionStore.setManagementProfile("")
                                    container.clientFactory.invalidate()
                                }
                                message = "Deleted $target"
                                reload()
                            }
                            .onFailure { error = it.message }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    ScreenScaffold("Profiles", "Isolated Hermes homes", actions = {
        TextButton(onClick = { reload() }) { Text("Refresh") }
    }) {
        when {
            list == null && error == null -> LoadingBox()
            error != null && list == null -> ErrorBox(error!!, onRetry = { reload() })
            else -> {
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.secondary)
                }
                Text(
                    "Gateway processes stay per-profile on the host — switching here scopes Manage/Chat API calls.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (onShortcut != null) {
                    Row(modifier = Modifier.padding(bottom = 8.dp)) {
                        OutlinedButton(onClick = { onShortcut("skills") }) { Text("Manage skills") }
                        OutlinedButton(onClick = { onShortcut("config") }) { Text("Open config") }
                        OutlinedButton(onClick = { onShortcut("sessions") }) { Text("Sessions") }
                    }
                }
                OutlinedButton(onClick = { switchActive("") }) {
                    Text("Use default (clear management profile)")
                }
                LazyColumn {
                    item {
                        Text("Create profile", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = createName,
                            onValueChange = { createName = it },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = createDescription,
                            onValueChange = { createDescription = it },
                            label = { Text("Description (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = cloneFrom,
                            onValueChange = { cloneFrom = it },
                            label = { Text("Clone from profile (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            enabled = createName.isNotBlank(),
                            onClick = {
                                val name = createName.trim()
                                scope.launch {
                                    repo.createProfile(name, createDescription, cloneFrom)
                                        .onSuccess {
                                            createName = ""
                                            createDescription = ""
                                            cloneFrom = ""
                                            message = "Created $name"
                                            reload()
                                        }
                                        .onFailure { error = it.message }
                                }
                            },
                        ) { Text("Create") }
                    }
                    items(list.orEmpty(), key = { it.name }) { p ->
                        Surface(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(p.name, style = MaterialTheme.typography.titleLarge)
                                Text(p.description ?: "")
                                Text(
                                    "model=${p.provider?.let { "$it/" }.orEmpty()}${p.model ?: "—"} " +
                                        "skills=${p.skill_count ?: 0} gateway=${p.gateway_running == true} " +
                                        "active=${p.name == activeName} default=${p.is_default}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(onClick = { switchActive(p.name) }) {
                                    Text(if (p.name == activeName) "Active · manage" else "Switch active")
                                }
                                if (onShortcut != null) {
                                    Row {
                                        TextButton(onClick = {
                                            switchActive(p.name) { onShortcut("skills") }
                                        }) { Text("Manage skills") }
                                        TextButton(onClick = {
                                            switchActive(p.name) { onShortcut("config") }
                                        }) { Text("Open config") }
                                    }
                                }
                                Row {
                                    TextButton(onClick = {
                                        renameTarget = p.name
                                        renameName = p.name
                                    }) { Text("Rename") }
                                    TextButton(onClick = {
                                        descriptionTarget = p.name
                                        descriptionText = p.description.orEmpty()
                                    }) { Text("Description") }
                                    TextButton(onClick = {
                                        scope.launch {
                                            repo.getProfileSoul(p.name)
                                                .onSuccess {
                                                    soulText = it
                                                    soulTarget = p.name
                                                }
                                                .onFailure { error = it.message }
                                        }
                                    }) { Text("SOUL") }
                                }
                                Row {
                                    TextButton(onClick = {
                                        scope.launch {
                                            repo.describeProfileAutomatically(p.name)
                                                .onSuccess {
                                                    message = "Generated description for ${p.name}"
                                                    reload()
                                                }
                                                .onFailure { error = it.message }
                                        }
                                    }) { Text("Auto-describe") }
                                    if (p.is_default != true) {
                                        TextButton(onClick = { deleteTarget = p.name }) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
