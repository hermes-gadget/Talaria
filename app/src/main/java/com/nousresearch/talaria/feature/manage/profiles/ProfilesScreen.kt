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
package com.nousresearch.talaria.feature.manage.profiles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.ProfileInfo
import com.nousresearch.talaria.ui.components.ErrorBox
import com.nousresearch.talaria.ui.components.LoadingBox
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch

@Composable
fun ProfilesScreen(onShortcut: ((String) -> Unit)? = null) {
    val container = TalariaApp.instance.container
    val repo = container.hermesRepository
    val connectionStore = container.connectionStore
    var list by remember { mutableStateOf<List<ProfileInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() = scope.launch {
        repo.getProfiles()
            .onSuccess {
                list = it
                error = null
            }
            .onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { reload() }

    fun switchActive(name: String) {
        scope.launch {
            connectionStore.setManagementProfile(name)
            repo.setActiveProfileName(name)
                .onFailure { message = it.message }
            container.clientFactory.invalidate()
            message = if (name.isBlank()) "Switched to default" else "Managing $name"
            reload()
        }
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
                if (onShortcut != null) {
                    Row(modifier = Modifier.padding(bottom = 8.dp)) {
                        OutlinedButton(onClick = { onShortcut("status") }) { Text("Status") }
                        OutlinedButton(onClick = { onShortcut("sessions") }) { Text("Sessions") }
                        OutlinedButton(onClick = { onShortcut("config") }) { Text("Config") }
                    }
                }
                OutlinedButton(onClick = { switchActive("") }) {
                    Text("Use default (clear management profile)")
                }
                LazyColumn {
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
                                    "model=${p.model ?: "—"} skills=${p.skill_count ?: 0} " +
                                        "active=${p.is_active} default=${p.is_default}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(onClick = { switchActive(p.name) }) {
                                    Text(if (p.is_active == true) "Active · switch local" else "Switch active")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
