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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.MessagingPlatform
import com.nousresearch.talaria.ui.components.ErrorBox
import com.nousresearch.talaria.ui.components.LoadingBox
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
                                val fields = p.env_vars.ifEmpty {
                                    p.env_keys.map { key ->
                                        com.nousresearch.talaria.domain.model.MessagingEnvVarInfo(key = key)
                                    }
                                }
                                fields.forEach { field ->
                                    val draftKey = "${p.id}:${field.key}"
                                    OutlinedTextField(
                                        value = envDrafts[draftKey].orEmpty(),
                                        onValueChange = { envDrafts[draftKey] = it },
                                        label = {
                                            Text(
                                                (field.prompt ?: field.key) + if (field.required) " *" else "",
                                            )
                                        },
                                        supportingText = {
                                            Text(
                                                field.redacted_value?.let { "Currently $it" }
                                                    ?: field.description.orEmpty(),
                                            )
                                        },
                                        visualTransformation = if (field.is_password) {
                                            PasswordVisualTransformation()
                                        } else {
                                            androidx.compose.ui.text.input.VisualTransformation.None
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                    )
                                    if (field.is_set) {
                                        TextButton(onClick = {
                                            scope.launch {
                                                repo.updateChannel(p.id, null, null, clearEnv = listOf(field.key))
                                                    .onSuccess {
                                                        envDrafts.remove(draftKey)
                                                        testResult = "Cleared ${field.key}"
                                                        reload()
                                                    }
                                                    .onFailure { error = it.message }
                                            }
                                        }) { Text("Clear ${field.key}") }
                                    }
                                }
                                Row {
                                    TextButton(onClick = {
                                        scope.launch {
                                            val values = fields.mapNotNull { field ->
                                                val value = envDrafts["${p.id}:${field.key}"]?.trim().orEmpty()
                                                value.takeIf { it.isNotEmpty() }?.let { field.key to it }
                                            }
                                            val env = buildJsonObject { values.forEach { (key, value) -> put(key, value) } }
                                            repo.updateChannel(p.id, null, env)
                                                .onSuccess {
                                                    values.forEach { (key, _) -> envDrafts.remove("${p.id}:$key") }
                                                    testResult = "Updated ${p.id}"
                                                    reload()
                                                }
                                                .onFailure { error = it.message }
                                        }
                                    }, enabled = fields.any { envDrafts["${p.id}:${it.key}"].orEmpty().isNotBlank() }) {
                                        Text("Save credentials")
                                    }
                                    TextButton(onClick = {
                                        scope.launch {
                                            repo.testChannel(p.id)
                                                .onSuccess { result ->
                                                    val obj = result.jsonObject
                                                    val message = obj["message"]?.jsonPrimitive?.contentOrNull
                                                        ?: obj["state"]?.jsonPrimitive?.contentOrNull
                                                        ?: if (obj["ok"]?.jsonPrimitive?.content == "true") "OK" else result.toString()
                                                    testResult = "${p.name}: $message"
                                                }
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
