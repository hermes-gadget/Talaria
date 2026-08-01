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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
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
import com.nousresearch.talaria.ui.components.UnsavedChangesGuard
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    // Baseline of the last loaded/saved config, to detect unsaved edits.
    var savedText by remember { mutableStateOf("") }
    var defaultsText by remember { mutableStateOf<String?>(null) }
    var schema by remember { mutableStateOf<ConfigSchemaResponse?>(null) }
    var schemaFailed by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf<String?>(null) }

    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val raw = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            JsonConfig.json.parseToJsonElement(raw).jsonObject // validate
            text = raw
            message = "Imported from file (not saved)"
        }.onFailure { message = "Invalid config file: ${it.message}" }
    }

    fun load() {
        scope.launch {
            repo.getConfig()
                .onSuccess {
                    text = JsonConfig.json.encodeToString(it)
                    savedText = text
                }
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

    val dirty = text != savedText
    UnsavedChangesGuard(hasUnsavedChanges = dirty)

    val categories = remember(schema) {
        schema?.let { s ->
            val fromOrder = s.category_order.filter { it.isNotBlank() }
            if (fromOrder.isNotEmpty()) fromOrder else categoriesFromSchema(s)
        }.orEmpty()
    }

    val useForm = !schemaFailed && schema != null && categories.isNotEmpty()

    val subtitle = buildString {
        append(if (useForm) "Schema-driven editor" else "Raw JSON editor")
        if (dirty) append(" · Unsaved changes")
    }
    ScreenScaffold("Config", subtitle, actions = {
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
                            val currentElement = configObj?.let { configValueAtPath(it, key) }
                            val current = when (currentElement) {
                                is JsonPrimitive -> currentElement.content
                                null -> ""
                                else -> currentElement.toString()
                            }
                            val enumValues = meta?.let { enumOptions(it) }
                            when {
                                !enumValues.isNullOrEmpty() -> {
                                    var expanded by remember(key) { mutableStateOf(false) }
                                    ExposedDropdownMenuBox(
                                        expanded = expanded,
                                        onExpandedChange = { expanded = it },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        OutlinedTextField(
                                            value = current,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text(key) },
                                            supportingText = desc?.let { { Text(it) } },
                                            trailingIcon = {
                                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                            },
                                            modifier = Modifier
                                                .menuAnchor(
                                                    ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                                    enabled = true,
                                                )
                                                .fillMaxWidth(),
                                        )
                                        ExposedDropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                        ) {
                                            enumValues.forEach { option ->
                                                DropdownMenuItem(
                                                    text = { Text(option) },
                                                    onClick = {
                                                        text = updateConfigKey(text, key, option, type)
                                                        expanded = false
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                type == "boolean" || type == "bool" -> {
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
                                                text = updateConfigKey(text, key, on.toString(), "boolean")
                                            },
                                        )
                                    }
                                }
                                else -> {
                                    OutlinedTextField(
                                        value = current,
                                        onValueChange = { newVal ->
                                            text = updateConfigKey(text, key, newVal, type)
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
                            savedText = text
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
                }) { Text("Paste") }
                OutlinedButton(onClick = {
                    importFileLauncher.launch(
                        arrayOf("application/json", "text/plain", "*/*"),
                    )
                }) { Text("Import file") }
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

/**
 * Extracts enum choices from a schema field. Supports `enum: [...]`, `choices: [...]`,
 * and JSON-Schema `oneOf: [{const: ...}, ...]` shapes used by the Hermes config schema.
 */
private fun enumOptions(meta: JsonObject): List<String> {
    fun primitives(arr: JsonArray): List<String> =
        arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    (meta["enum"] as? JsonArray)?.let { return primitives(it) }
    (meta["choices"] as? JsonArray)?.let { return primitives(it) }
    (meta["oneOf"] as? JsonArray)?.let { arr ->
        val consts = arr.mapNotNull { el ->
            (el as? JsonObject)?.get("const")?.let { c ->
                (c as? JsonPrimitive)?.contentOrNull
            }
        }
        if (consts.isNotEmpty()) return consts
    }
    return emptyList()
}

internal fun configValueAtPath(root: JsonObject, path: String): kotlinx.serialization.json.JsonElement? {
    var current: kotlinx.serialization.json.JsonElement = root
    for (part in path.split('.')) {
        current = (current as? JsonObject)?.get(part) ?: return null
    }
    return current
}

internal fun updateConfigKey(text: String, key: String, newVal: String, expectedType: String?): String {
    return runCatching {
        val trimmed = newVal.trim()
        val value = when (expectedType) {
            "boolean", "bool" ->
                JsonPrimitive(trimmed.toBoolean())
            "number", "integer" -> trimmed.toLongOrNull()?.let(::JsonPrimitive)
                ?: trimmed.toDoubleOrNull()?.let(::JsonPrimitive)
                ?: error("Expected a number")
            "list", "array" -> JsonConfig.json.parseToJsonElement(trimmed).also {
                require(it is JsonArray) { "Expected a JSON array" }
            }
            "object" -> JsonConfig.json.parseToJsonElement(trimmed).also {
                require(it is JsonObject) { "Expected a JSON object" }
            }
            else -> JsonPrimitive(newVal)
        }
        val root = JsonConfig.json.parseToJsonElement(text).jsonObject
        JsonConfig.json.encodeToString(setConfigValueAtPath(root, key.split('.'), value))
    }.getOrDefault(text)
}

private fun setConfigValueAtPath(
    root: JsonObject,
    parts: List<String>,
    value: kotlinx.serialization.json.JsonElement,
): JsonObject {
    require(parts.isNotEmpty())
    val out = root.toMutableMap()
    val head = parts.first()
    if (parts.size == 1) {
        out[head] = value
    } else {
        val child = out[head] as? JsonObject ?: JsonObject(emptyMap())
        out[head] = setConfigValueAtPath(child, parts.drop(1), value)
    }
    return JsonObject(out)
}
