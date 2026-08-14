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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.util.formatHermesTimestamp
import com.hermesgadget.talaria.domain.model.ProfileInfo
import com.hermesgadget.talaria.ui.components.CollapsibleSection
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.hermesgadget.talaria.core.util.suspendResult

@Composable
fun ProfilesScreen(onShortcut: ((String) -> Unit)? = null) {
    // Hoisted resource templates (callbacks are non-composable).
    val profilesModelSavedTpl = stringResource(R.string.minor_profiles_model_saved)
    val profilesTerminalOpenedTpl = stringResource(R.string.minor_profiles_terminal_opened)

    val container = TalariaApp.instance.container
    val repo = container.hermesRepository
    val connectionStore = container.connectionStore
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
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
    var profileSessions by remember { mutableStateOf<List<ProfileSessionRow>?>(null) }
    var sessionsLoading by remember { mutableStateOf(false) }
    var sessionsError by remember { mutableStateOf<String?>(null) }
    var modelTarget by remember { mutableStateOf<ProfileInfo?>(null) }
    var modelProvider by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    var setupCommandTarget by remember { mutableStateOf<String?>(null) }
    var setupCommand by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // H1: generation guard so a slow reload cannot overwrite a newer one.
    var reloadJob by remember { mutableStateOf<Job?>(null) }
    var reloadGeneration by remember { mutableIntStateOf(0) }

    suspend fun loadSessions() {
        sessionsLoading = true
        suspendResult { container.clientFactory.apiForActive().getProfilesSessions() }
            .onSuccess {
                profileSessions = parseProfileSessions(it)
                sessionsError = null
            }
            .onFailure { sessionsError = it.message }
        sessionsLoading = false
    }

    fun reload() {
        reloadJob?.cancel()
        val generation = ++reloadGeneration
        reloadJob = scope.launch {
            repo.getProfiles(force = true)
                .onSuccess {
                    if (generation == reloadGeneration) {
                        list = it
                        error = null
                    }
                }
                .onFailure { if (generation == reloadGeneration) error = it.message }
            repo.getActiveProfileName().onSuccess {
                if (generation == reloadGeneration) activeName = it ?: "default"
            }
            loadSessions()
        }
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

    modelTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { modelTarget = null },
            title = { Text(stringResource(R.string.minor_profiles_model_title, target.name)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = modelProvider,
                        onValueChange = { modelProvider = it },
                        label = { Text(stringResource(R.string.minor_profiles_provider)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        label = { Text(stringResource(R.string.minor_profiles_model)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = modelProvider.isNotBlank() && modelName.isNotBlank(),
                    onClick = {
                        val provider = modelProvider.trim()
                        val model = modelName.trim()
                        modelTarget = null
                        scope.launch {
                            suspendResult {
                                container.clientFactory.apiForActive().putProfileModel(
                                    target.name,
                                    buildJsonObject {
                                        put("provider", provider)
                                        put("model", model)
                                    },
                                )
                            }
                                .onSuccess {
                                    message = profilesModelSavedTpl.format(target.name)
                                    reload()
                                }
                                .onFailure { error = it.message }
                        }
                    },
                ) { Text(stringResource(R.string.minor_profiles_save_model)) }
            },
            dismissButton = { TextButton(onClick = { modelTarget = null }) { Text("Cancel") } },
        )
    }

    setupCommandTarget?.let { target ->
        val command = setupCommand.orEmpty()
        AlertDialog(
            onDismissRequest = { setupCommandTarget = null },
            title = { Text(stringResource(R.string.minor_profiles_setup_command_title, target)) },
            text = {
                if (command.isBlank()) {
                    Text(stringResource(R.string.minor_profiles_setup_command_missing))
                } else {
                    OutlinedTextField(
                        value = command,
                        onValueChange = {},
                        readOnly = true,
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                if (command.isNotBlank()) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(command)) }) {
                        Text(stringResource(R.string.minor_profiles_copy_command))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { setupCommandTarget = null }) { Text("Close") }
            },
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
                LazyColumn(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    item {
                        CollapsibleSection(
                            title = stringResource(R.string.minor_profiles_create_section),
                            collapsible = true,
                        ) {
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
                    }
                    item {
                        CollapsibleSection(stringResource(R.string.minor_profiles_list_section)) {
                            list.orEmpty().forEach { p ->
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
                                                modelTarget = p
                                                modelProvider = p.provider.orEmpty()
                                                modelName = p.model.orEmpty()
                                            }) {
                                                Text(stringResource(R.string.minor_profiles_set_model))
                                            }
                                            TextButton(onClick = {
                                                scope.launch {
                                                    suspendResult {
                                                        container.clientFactory.apiForActive().openProfileTerminal(p.name)
                                                    }
                                                        .onSuccess {
                                                            message = profilesTerminalOpenedTpl.format(p.name)
                                                        }
                                                        .onFailure { error = it.message }
                                                }
                                            }) {
                                                Text(stringResource(R.string.minor_profiles_open_terminal))
                                            }
                                            TextButton(onClick = {
                                                scope.launch {
                                                    suspendResult {
                                                        container.clientFactory.apiForActive().getProfileSetupCommand(p.name)
                                                    }
                                                        .onSuccess {
                                                            setupCommand = parseProfileCommand(it)
                                                            setupCommandTarget = p.name
                                                        }
                                                        .onFailure { error = it.message }
                                                }
                                            }) {
                                                Text(stringResource(R.string.minor_profiles_setup_command))
                                            }
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
                    item {
                        CollapsibleSection(
                            title = stringResource(R.string.minor_profiles_sessions_section),
                            collapsible = true,
                        ) {
                            when {
                                sessionsLoading -> LoadingBox()
                                sessionsError != null -> {
                                    Text(
                                        sessionsError!!,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                profileSessions.isNullOrEmpty() -> Text(
                                    stringResource(R.string.minor_profiles_no_sessions),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                else -> profileSessions.orEmpty().forEach { session ->
                                    ProfileSessionCard(session)
                                }
                            }
                            TextButton(
                                onClick = { scope.launch { loadSessions() } },
                                enabled = !sessionsLoading,
                            ) {
                                Text(stringResource(R.string.minor_profiles_refresh_sessions))
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ProfileSessionRow(
    val id: String,
    val profile: String?,
    val title: String,
    val model: String?,
    val lastActive: String?,
    val active: Boolean,
)

private fun parseProfileSessions(root: JsonElement): List<ProfileSessionRow> {
    val elements = when (root) {
        is JsonArray -> root
        is JsonObject -> root["sessions"] as? JsonArray ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return elements.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.stringValue("id", "session_id") ?: return@mapNotNull null
        ProfileSessionRow(
            id = id,
            profile = obj.stringValue("profile"),
            title = obj.stringValue("title", "name") ?: id,
            model = obj.stringValue("model"),
            lastActive = obj.stringValue("last_active", "lastActive", "last_active_at"),
            active = obj.booleanValue("is_active", "active") ?: false,
        )
    }
}

private fun parseProfileCommand(root: JsonElement): String? =
    (root as? JsonObject)?.stringValue("command")

private fun JsonObject.stringValue(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

private fun JsonObject.booleanValue(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.booleanOrNull
}

@Composable
private fun ProfileSessionCard(session: ProfileSessionRow) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
        ) {
            Text(session.title, style = MaterialTheme.typography.titleSmall)
            val metadata = listOfNotNull(
                session.profile,
                session.model,
                formatHermesTimestamp(session.lastActive),
                if (session.active) stringResource(R.string.minor_profiles_session_active) else null,
            ).joinToString(" · ")
            if (metadata.isNotBlank()) {
                Text(
                    metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.title != session.id) {
                Text(
                    session.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
