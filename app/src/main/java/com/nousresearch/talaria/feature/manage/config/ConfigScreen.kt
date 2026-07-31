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
package com.nousresearch.talaria.feature.manage.config

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.core.network.JsonConfig
import com.nousresearch.talaria.domain.model.ConfigSchemaResponse
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun ConfigScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var defaultsText by remember { mutableStateOf<String?>(null) }
    var schema by remember { mutableStateOf<ConfigSchemaResponse?>(null) }
    var schemaFailed by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            repo.getConfig()
                .onSuccess { text = JsonConfig.json.encodeToString(it) }
                .onFailure { message = it.message }
            repo.getConfigDefaults()
                .onSuccess { defaultsText = JsonConfig.json.encodeToString(it) }
                .onFailure { /* optional */ }
            repo.getConfigSchema()
                .onSuccess {
                    schema = it
                    schemaFailed = false
                    selectedCategory = it.category_order.firstOrNull()
                        ?: categoriesFromSchema(it).firstOrNull()
                }
                .onFailure {
                    schema = null
                    schemaFailed = true
                }
        }
    }

    LaunchedEffect(Unit) { load() }

    val categories = remember(schema) {
        schema?.let { s ->
            val fromOrder = s.category_order.filter { it.isNotBlank() }
            if (fromOrder.isNotEmpty()) fromOrder else categoriesFromSchema(s)
        }.orEmpty()
    }

    val useForm = !schemaFailed && schema != null && categories.isNotEmpty()

    ScreenScaffold("Config", if (useForm) "Schema-driven editor" else "Raw JSON editor", actions = {
        TextButton(onClick = { load() }) { Text("Reload") }
    }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (useForm) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    FilterChip(
                        selected = selectedCategory == "__json__",
                        onClick = { selectedCategory = "__json__" },
                        label = { Text("JSON") },
                    )
                }
            }

            if (!useForm || selectedCategory == "__json__") {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp),
                    minLines = 16,
                    label = { Text("config.json") },
                )
            } else {
                val cat = selectedCategory
                val fields = schema?.fields
                if (cat != null && fields != null) {
                    val keys = fieldKeysForCategory(fields, cat)
                    if (keys.isEmpty()) {
                        Text("No fields in $cat", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val configObj = runCatching {
                            JsonConfig.json.parseToJsonElement(text).jsonObject
                        }.getOrNull()
                        keys.forEach { key ->
                            val meta = fields[key]?.jsonObject
                            val type = meta?.get("type")?.jsonPrimitive?.contentOrNull
                            val desc = meta?.get("description")?.jsonPrimitive?.contentOrNull
                            val current = configObj?.get(key)?.toString()?.trim('"') ?: ""
                            when (type) {
                                "boolean", "bool" -> {
                                    val checked = current.equals("true", ignoreCase = true)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(key, style = MaterialTheme.typography.titleSmall)
                                            desc?.let {
                                                Text(it, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        androidx.compose.material3.Switch(
                                            checked = checked,
                                            onCheckedChange = { on ->
                                                text = updateConfigKey(text, key, on.toString())
                                            },
                                        )
                                    }
                                }
                                else -> {
                                    OutlinedTextField(
                                        value = current,
                                        onValueChange = { newVal ->
                                            text = updateConfigKey(text, key, newVal)
                                        },
                                        label = { Text(key) },
                                        supportingText = desc?.let { { Text(it) } },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            message?.let {
                Text(it, color = MaterialTheme.colorScheme.secondary)
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    scope.launch {
                        runCatching {
                            val obj = JsonConfig.json.parseToJsonElement(text).jsonObject
                            repo.putConfig(obj).getOrThrow()
                            message = "Saved"
                        }.onFailure { message = it.message }
                    }
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    defaultsText?.let {
                        text = it
                        message = "Reset to defaults (not saved)"
                    } ?: run { message = "Defaults unavailable" }
                }) { Text("Reset") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, text)
                        putExtra(Intent.EXTRA_TITLE, "hermes-config.json")
                    }
                    context.startActivity(Intent.createChooser(send, "Export config"))
                }) { Text("Export") }
                OutlinedButton(onClick = {
                    importText = text
                }) { Text("Import") }
            }

            importText?.let {
                OutlinedTextField(
                    value = importText ?: "",
                    onValueChange = { importText = it },
                    label = { Text("Paste config JSON") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    minLines = 6,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val pasted = importText.orEmpty()
                        runCatching {
                            JsonConfig.json.parseToJsonElement(pasted).jsonObject
                            text = pasted
                            importText = null
                            message = "Imported (not saved)"
                        }.onFailure { message = "Invalid JSON: ${it.message}" }
                    }) { Text("Apply import") }
                    TextButton(onClick = { importText = null }) { Text("Cancel") }
                }
            }
        }
    }
}

private fun categoriesFromSchema(schema: ConfigSchemaResponse): List<String> {
    val fields = schema.fields ?: return emptyList()
    return fields.values.mapNotNull { el ->
        el.jsonObject["category"]?.jsonPrimitive?.contentOrNull
    }.distinct()
}

private fun fieldKeysForCategory(fields: JsonObject, category: String): List<String> {
    return fields.entries.mapNotNull { (key, el) ->
        val cat = el.jsonObject["category"]?.jsonPrimitive?.contentOrNull
        if (cat == category) key else null
    }.sorted()
}

private fun updateConfigKey(text: String, key: String, newVal: String): String {
    return runCatching {
        val obj = JsonConfig.json.parseToJsonElement(text).jsonObject.toMutableMap()
        val trimmed = newVal.trim()
        obj[key] = when {
            trimmed.equals("true", true) || trimmed.equals("false", true) ->
                JsonPrimitive(trimmed.toBoolean())
            trimmed.toLongOrNull() != null -> JsonPrimitive(trimmed.toLong())
            trimmed.toDoubleOrNull() != null -> JsonPrimitive(trimmed.toDouble())
            else -> JsonPrimitive(newVal)
        }
        JsonConfig.json.encodeToString(JsonObject(obj))
    }.getOrDefault(text)
}
