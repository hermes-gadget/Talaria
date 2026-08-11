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
package com.hermesgadget.talaria.feature.manage.config

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.domain.model.ConfigSchemaResponse
import com.hermesgadget.talaria.ui.components.CollapsibleSection
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import com.hermesgadget.talaria.ui.components.UnsavedChangesGuard
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editorState by remember { mutableStateOf(ConfigEditorState()) }
    var pendingSaves by remember { mutableIntStateOf(0) }
    var defaultsText by remember { mutableStateOf<String?>(null) }
    var schema by remember { mutableStateOf<ConfigSchemaResponse?>(null) }
    var schemaFailed by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var importGeneration by remember { mutableLongStateOf(0L) }
    var fieldDrafts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var fieldErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var configModel by remember { mutableStateOf<JsonObject?>(null) }
    var configModelRevision by remember { mutableLongStateOf(0L) }
    var modelJob by remember { mutableStateOf<Job?>(null) }
    var serializing by remember { mutableStateOf(false) }
    val saveCoordinator = remember(repo) {
        ConfigSaveCoordinator(
            putConfig = { config -> repo.putConfig(config) },
            getConfig = { repo.getConfig() },
        )
    }

    fun replaceConfigText(newText: String) {
        val edited = editorState.edit(newText)
        if (edited === editorState) return
        editorState = edited
        fieldDrafts = emptyMap()
        fieldErrors = emptyMap()
        val revision = ++configModelRevision
        modelJob?.cancel()
        serializing = true
        modelJob = scope.launch {
            delay(CONFIG_MODEL_DEBOUNCE_MILLIS)
            val parsed = withContext(Dispatchers.Default) {
                runCatching {
                    JsonConfig.json.parseToJsonElement(newText.removePrefix("\uFEFF")).jsonObject
                }.getOrNull()
            }
            if (revision != configModelRevision) return@launch
            configModel = parsed
            serializing = false
        }
    }

    fun updateSchemaField(key: String, newValue: String, expectedType: String?) {
        // Keep the user's display text even when it is not yet a valid typed
        // value (for example "-" while entering a negative number).
        fieldDrafts = fieldDrafts + (key to newValue)
        val value = runCatching { parseConfigDraft(newValue, expectedType) }
            .getOrElse { error ->
                fieldErrors = fieldErrors +
                    (key to (error.message ?: "Invalid value"))
                return
            }
        val currentModel = configModel
        if (currentModel == null) {
            fieldErrors = fieldErrors + (key to "Current config is not ready")
            return
        }
        val updatedModel = runCatching {
            setConfigValueAtPath(currentModel, key.split('.'), value)
        }
            .getOrElse {
                fieldErrors = fieldErrors +
                    (key to "Current config is not valid JSON")
                return
            }
        fieldErrors = fieldErrors - key
        val revision = ++configModelRevision
        modelJob?.cancel()
        configModel = updatedModel
        serializing = true
        modelJob = scope.launch {
            delay(CONFIG_MODEL_DEBOUNCE_MILLIS)
            val updatedText = withContext(Dispatchers.Default) {
                JsonConfig.json.encodeToString(updatedModel)
            }
            if (revision != configModelRevision) return@launch
            editorState = editorState.edit(updatedText)
            serializing = false
        }
    }

    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importJob?.cancel()
        val generation = importGeneration + 1
        importGeneration = generation
        importing = true
        importJob = scope.launch {
            try {
                val raw = readAndValidateConfigImport(context.contentResolver, uri)
                if (generation != importGeneration) return@launch
                replaceConfigText(raw)
                editorState = editorState.withMessage("Imported from file (not saved)")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == importGeneration) {
                    editorState = editorState.withMessage("Invalid config file: ${error.message}")
                }
            } finally {
                if (generation == importGeneration) importing = false
            }
        }
    }

    fun load() {
        scope.launch {
            val configResult = repo.getConfig()
            val config = configResult.getOrNull()
            if (config != null) {
                val loadedText = withContext(Dispatchers.Default) {
                    JsonConfig.json.encodeToString(config)
                }
                editorState = editorState.loaded(loadedText)
                configModel = config
                configModelRevision += 1
                modelJob?.cancel()
                modelJob = null
                serializing = false
                fieldDrafts = emptyMap()
                fieldErrors = emptyMap()
            } else {
                editorState = editorState.withMessage(configResult.exceptionOrNull()?.message)
            }
            val defaultsResult = repo.getConfigDefaults()
            if (defaultsResult.isSuccess) {
                defaultsText = withContext(Dispatchers.Default) {
                    JsonConfig.json.encodeToString(defaultsResult.getOrThrow())
                }
            }
            val schemaResult = repo.getConfigSchema()
            schemaResult.onSuccess {
                schema = it
                schemaFailed = false
                selectedCategory = it.category_order.firstOrNull()
                    ?: categoriesFromSchema(it).firstOrNull()
            }.onFailure {
                schema = null
                schemaFailed = true
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    val text = editorState.text
    val savedText = editorState.savedText
    val message = editorState.message
    val dirty = editorState.isDirty || fieldErrors.isNotEmpty()
    UnsavedChangesGuard(hasUnsavedChanges = dirty)

    val categories = remember(schema) {
        schema?.let { s ->
            val fromOrder = s.category_order.filter { it.isNotBlank() }
            if (fromOrder.isNotEmpty()) fromOrder else categoriesFromSchema(s)
        }.orEmpty()
    }

    val useForm = !schemaFailed && schema != null && categories.isNotEmpty()
    val hasFieldErrors = fieldErrors.isNotEmpty()

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
            CollapsibleSection(title = stringResource(R.string.declutter_config_editor)) {
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
                        onValueChange = ::replaceConfigText,
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
                            val configObj = configModel
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
                                val draft = fieldDrafts[key] ?: current
                                val fieldError = fieldErrors[key]
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
                                                value = draft,
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
                                                            updateSchemaField(key, option, type)
                                                            expanded = false
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    type == "boolean" || type == "bool" -> {
                                        val checked = draft.equals("true", ignoreCase = true)
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
                                                    updateSchemaField(key, on.toString(), "boolean")
                                                },
                                            )
                                        }
                                        fieldError?.let {
                                            Text(it, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    else -> {
                                        OutlinedTextField(
                                            value = draft,
                                            onValueChange = { newVal ->
                                                updateSchemaField(key, newVal, type)
                                            },
                                            label = { Text(key) },
                                            isError = fieldError != null,
                                            supportingText = if (desc != null || fieldError != null) {
                                                {
                                                    desc?.let { Text(it) }
                                                    fieldError?.let {
                                                        Text(it, color = MaterialTheme.colorScheme.error)
                                                    }
                                                }
                                            } else {
                                                null
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
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
                Button(
                    enabled = !importing && !hasFieldErrors && !serializing && text != savedText,
                    onClick = {
                        val request = ConfigSaveRequest(
                            text = editorState.text,
                            draftGeneration = editorState.draftGeneration,
                        )
                        pendingSaves += 1
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.Default) {
                                    saveCoordinator.save(request)
                                }
                                editorState = editorState.applySaveResult(result)
                            } catch (error: CancellationException) {
                                throw error
                            } finally {
                                pendingSaves = (pendingSaves - 1).coerceAtLeast(0)
                            }
                        }
                    },
                ) { Text(if (pendingSaves > 0) "Saving…" else "Save") }
            }

            CollapsibleSection(
                title = stringResource(R.string.declutter_config_import_export),
                collapsible = true,
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = {
                        defaultsText?.let {
                            replaceConfigText(it)
                            editorState = editorState.withMessage("Reset to defaults (not saved)")
                        } ?: run { editorState = editorState.withMessage("Defaults unavailable") }
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
                    OutlinedButton(
                        enabled = !importing,
                        onClick = {
                            importFileLauncher.launch(
                                arrayOf("application/json", "text/plain", "*/*"),
                            )
                        },
                    ) { Text("Import file") }
                }

                if (importing) {
                    Text("Reading import…", color = MaterialTheme.colorScheme.secondary)
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
                            importJob?.cancel()
                            val generation = importGeneration + 1
                            importGeneration = generation
                            importing = true
                            importJob = scope.launch {
                                val validation = withContext(Dispatchers.Default) {
                                    runCatching {
                                        JsonConfig.json.parseToJsonElement(pasted).jsonObject
                                    }
                                }
                                if (generation != importGeneration) return@launch
                                validation.onSuccess {
                                    replaceConfigText(pasted)
                                    importText = null
                                    editorState = editorState.withMessage("Imported (not saved)")
                                }.onFailure {
                                    editorState = editorState.withMessage("Invalid JSON: ${it.message}")
                                }
                                importing = false
                            }
                        }) { Text("Apply import") }
                        TextButton(onClick = { importText = null }) { Text("Cancel") }
                    }
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

/** Parse only a bounded SAF document, keeping all blocking work off Compose's main dispatcher. */
private suspend fun readAndValidateConfigImport(
    resolver: android.content.ContentResolver,
    uri: android.net.Uri,
): String {
    val raw = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { readBoundedUtf8(it, MAX_CONFIG_IMPORT_BYTES) }
            ?: error("Could not open the selected file")
    }
    return withContext(Dispatchers.Default) {
        JsonConfig.json.parseToJsonElement(raw.removePrefix("\uFEFF")).jsonObject
        raw
    }
}

private fun readBoundedUtf8(input: InputStream, maxBytes: Long): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (total > maxBytes - count) {
            error("The selected config is larger than ${maxBytes / (1024 * 1024)} MB")
        }
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}

internal fun parseConfigDraft(newVal: String, expectedType: String?): JsonElement {
    val trimmed = newVal.trim()
    return when (expectedType?.lowercase()) {
        "boolean", "bool" -> when (trimmed.lowercase()) {
            "true" -> JsonPrimitive(true)
            "false" -> JsonPrimitive(false)
            else -> error("Expected true or false")
        }
        "number" -> trimmed.toLongOrNull()?.let(::JsonPrimitive)
            ?: trimmed.toDoubleOrNull()?.let(::JsonPrimitive)
            ?: error("Expected a number")
        "integer" -> trimmed.toLongOrNull()?.let(::JsonPrimitive)
            ?: error("Expected a whole number")
        "list", "array" -> JsonConfig.json.parseToJsonElement(trimmed).also {
            require(it is JsonArray) { "Expected a JSON array" }
        }
        "object" -> JsonConfig.json.parseToJsonElement(trimmed).also {
            require(it is JsonObject) { "Expected a JSON object" }
        }
        else -> JsonPrimitive(newVal)
    }
}

/** Single source of truth for applying a typed field edit to the config text. */
internal fun applyConfigEdit(text: String, key: String, value: kotlinx.serialization.json.JsonElement): String {
    val root = JsonConfig.json.parseToJsonElement(text).jsonObject
    return JsonConfig.json.encodeToString(setConfigValueAtPath(root, key.split('.'), value))
}

internal fun setConfigValueAtPath(
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

private const val MAX_CONFIG_IMPORT_BYTES = 10L * 1024 * 1024
private const val CONFIG_MODEL_DEBOUNCE_MILLIS = 120L
