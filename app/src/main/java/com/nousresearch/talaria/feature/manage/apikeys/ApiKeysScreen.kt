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

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.EnvVarInfo
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch

@Composable
fun ApiKeysScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    val context = LocalContext.current
    var vars by remember { mutableStateOf<Map<String, EnvVarInfo>>(emptyMap()) }
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var tip by remember { mutableStateOf<String?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reload() = scope.launch {
        repo.getEnv().onSuccess { vars = it }.onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { reload() }

    val grouped = remember(vars, showAdvanced) {
        vars.entries
            .filter { showAdvanced || it.value.advanced != true }
            .groupBy { it.value.category?.ifBlank { null } ?: "General" }
            .toSortedMap()
    }

    ScreenScaffold("API Keys", "Catalog + redacted .env — values stay on your server") {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        tip?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        OutlinedTextField(key, { key = it }, label = { Text("KEY") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value,
            { value = it },
            label = { Text("value") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Button(onClick = {
                scope.launch {
                    repo.setEnv(key, value).onSuccess {
                        key = ""
                        value = ""
                        tip = "Saved. Send /reload in Chat (or start a new session) for some keys."
                        reload()
                    }.onFailure { error = it.message }
                }
            }) { Text("Set") }
            Text("Show advanced", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = showAdvanced, onCheckedChange = { showAdvanced = it })
        }
        LazyColumn {
            grouped.forEach { (category, entries) ->
                item(key = "cat-$category") {
                    Text(
                        category,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(entries, key = { it.key }) { (k, info) ->
                    Surface(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    k,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (info.advanced == true) {
                                    Text(
                                        "advanced",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                            Text(
                                info.redacted_value ?: if (info.is_set == true) "••••••••" else "unset",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            info.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            info.url?.takeIf { it.isNotBlank() }?.let { url ->
                                TextButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }) { Text("Signup / docs") }
                            }
                            TextButton(onClick = {
                                key = k
                                tip = "Enter a new value above, then Set. Or Delete to clear."
                            }) { Text("Edit") }
                            TextButton(onClick = {
                                scope.launch { repo.deleteEnv(k); reload() }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
