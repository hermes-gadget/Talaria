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
package com.nousresearch.talaria.feature.manage.channels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.MessagingPlatform
import com.nousresearch.talaria.ui.components.ErrorBox
import com.nousresearch.talaria.ui.components.LoadingBox
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun ChannelsScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    var list by remember { mutableStateOf<List<MessagingPlatform>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val envDrafts = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()

    fun reload() = scope.launch {
        repo.getChannels()
            .onSuccess {
                list = it
                error = null
            }
            .onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { reload() }

    ScreenScaffold("Channels", "Messaging platforms", actions = {
        TextButton(onClick = { reload() }) { Text("Refresh") }
    }) {
        when {
            list == null && error == null -> LoadingBox()
            error != null && list == null -> ErrorBox(error!!, onRetry = { reload() })
            else -> {
                testResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                LazyColumn {
                    items(list.orEmpty(), key = { it.id }) { p ->
                        Surface(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.titleLarge)
                                        Text(p.description ?: "")
                                        Text(
                                            "state=${p.state} configured=${p.configured}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        p.error_message?.let {
                                            Text(it, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    Switch(
                                        checked = p.enabled == true,
                                        onCheckedChange = { enabled ->
                                            scope.launch {
                                                repo.updateChannel(p.id, enabled, null)
                                                    .onSuccess { reload() }
                                                    .onFailure { error = it.message }
                                            }
                                        },
                                    )
                                }
                                OutlinedTextField(
                                    value = envDrafts[p.id].orEmpty(),
                                    onValueChange = { envDrafts[p.id] = it },
                                    label = { Text("Env KEY=value (optional)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                Row {
                                    TextButton(onClick = {
                                        scope.launch {
                                            val draft = envDrafts[p.id].orEmpty().trim()
                                            val env = if (draft.contains("=")) {
                                                val k = draft.substringBefore("=").trim()
                                                val v = draft.substringAfter("=").trim()
                                                buildJsonObject { put(k, v) }
                                            } else {
                                                null
                                            }
                                            repo.updateChannel(p.id, null, env)
                                                .onSuccess {
                                                    testResult = "Updated ${p.id}"
                                                    reload()
                                                }
                                                .onFailure { error = it.message }
                                        }
                                    }) { Text("Save env") }
                                    TextButton(onClick = {
                                        scope.launch {
                                            repo.testChannel(p.id)
                                                .onSuccess { testResult = "${p.name}: $it" }
                                                .onFailure { testResult = "${p.name}: ${it.message}" }
                                        }
                                    }) { Text("Test") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
