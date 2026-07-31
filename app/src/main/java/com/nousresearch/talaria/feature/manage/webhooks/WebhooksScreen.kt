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
package com.nousresearch.talaria.feature.manage.webhooks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.WebhookRoute
import com.nousresearch.talaria.ui.components.ErrorBox
import com.nousresearch.talaria.ui.components.LoadingBox
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch

@Composable
fun WebhooksScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    var list by remember { mutableStateOf<List<WebhookRoute>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun reload() = scope.launch {
        repo.getWebhooks()
            .onSuccess {
                list = it
                error = null
            }
            .onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { reload() }

    ScreenScaffold("Webhooks", "Dynamic subscriptions", actions = {
        TextButton(onClick = { reload() }) { Text("Refresh") }
    }) {
        when {
            list == null && error == null -> LoadingBox()
            error != null && list == null -> ErrorBox(error!!, onRetry = { reload() })
            else -> {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    scope.launch {
                        repo.createWebhook(name.trim(), prompt.trim())
                            .onSuccess {
                                name = ""
                                prompt = ""
                                reload()
                            }
                            .onFailure { error = it.message }
                    }
                }) { Text("Create") }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                LazyColumn {
                    items(list.orEmpty(), key = { it.name }) { w ->
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
                                        Text(w.name, style = MaterialTheme.typography.titleLarge)
                                        Text(w.description ?: "")
                                        Text(w.url ?: "", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "events=${w.events.joinToString()}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Switch(
                                        checked = w.enabled == true,
                                        onCheckedChange = { enabled ->
                                            scope.launch {
                                                repo.setWebhookEnabled(w.name, enabled)
                                                    .onSuccess { reload() }
                                                    .onFailure { error = it.message }
                                            }
                                        },
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        repo.deleteWebhook(w.name)
                                            .onSuccess { reload() }
                                            .onFailure { error = it.message }
                                    }
                                }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
}
