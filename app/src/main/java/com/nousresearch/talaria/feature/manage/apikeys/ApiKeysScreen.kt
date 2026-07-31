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


package com.nousresearch.talaria.feature.manage.apikeys

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.EnvVarInfo
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier

@Composable
fun ApiKeysScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    var vars by remember { mutableStateOf<Map<String, EnvVarInfo>>(emptyMap()) }
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun reload() = scope.launch {
        repo.getEnv().onSuccess { vars = it }.onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { reload() }
    ScreenScaffold("API Keys", "Hermes .env — values stay on your server") {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(key, { key = it }, label = { Text("KEY") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value, { value = it }, label = { Text("value") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            scope.launch {
                repo.setEnv(key, value).onSuccess { key = ""; value = ""; reload() }
                    .onFailure { error = it.message }
            }
        }) { Text("Set") }
        LazyColumn {
            items(vars.entries.toList(), key = { it.key }) { (k, info) ->
                Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(k, style = MaterialTheme.typography.titleLarge)
                        Text(info.redacted_value ?: if (info.is_set == true) "••••" else "unset")
                        Text(info.description ?: "", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            scope.launch { repo.deleteEnv(k); reload() }
                        }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
