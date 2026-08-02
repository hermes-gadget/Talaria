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

package com.hermesgadget.talaria.feature.manage.memory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal const val MEMORY_CONFIG_SURFACE = "declared"
internal const val MEMORY_OAUTH_POLL_INTERVAL_MS = 1_500L
internal const val MEMORY_OAUTH_TIMEOUT_MS = 120_000L
internal const val MEMORY_ERROR_CONFIG_INVALID = "memory_error_config_invalid"
internal const val MEMORY_ERROR_OAUTH_STATUS_INVALID = "memory_error_oauth_status_invalid"
internal const val MEMORY_ERROR_OAUTH_TIMEOUT = "memory_error_oauth_timeout"
internal const val MEMORY_ERROR_SETUP_FAILED = "memory_error_setup_failed"

enum class MemoryProviderFieldKind {
    TEXT,
    SECRET,
    SELECT,
    BOOL,
    NUMBER,
    JSON,
}

data class MemoryProviderFieldOption(
    val value: String,
    val label: String,
    val description: String = "",
)

data class MemoryProviderConfigField(
    val key: String,
    val label: String,
    val kind: MemoryProviderFieldKind,
    val description: String = "",
    val info: String = "",
    val placeholder: String = "",
    val inline: Boolean = false,
    val group: String = "",
    val required: Boolean = false,
    val value: String = "",
    val isSet: Boolean = false,
    val options: List<MemoryProviderFieldOption> = emptyList(),
    val url: String = "",
)

data class MemoryProviderConfig(
    val name: String,
    val label: String,
    val docsUrl: String = "",
    val fields: List<MemoryProviderConfigField> = emptyList(),
    val setupDependenciesInstalled: Boolean? = null,
)

data class MemoryProviderOAuthStatus(
    val auth: String? = null,
    val connected: Boolean = false,
    val detail: String = "",
    val state: String = "idle",
)

data class MemoryProviderConfigUiState(
    val config: MemoryProviderConfig? = null,
    val values: Map<String, String> = emptyMap(),
    val savedValues: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

data class MemoryProviderOAuthUiState(
    val supported: Boolean? = null,
    val status: MemoryProviderOAuthStatus? = null,
    val loading: Boolean = false,
    val starting: Boolean = false,
    val error: String? = null,
)

internal fun parseMemoryProviderConfig(root: JsonElement): MemoryProviderConfig {
    val obj = root as? JsonObject ?: error(MEMORY_ERROR_CONFIG_INVALID)
    val fields = (obj["fields"] as? JsonArray)
        ?.mapNotNull(::parseMemoryProviderConfigField)
        .orEmpty()
    return MemoryProviderConfig(
        name = obj.stringValue("name").ifBlank { "memory" },
        label = obj.stringValue("label").ifBlank {
            obj.stringValue("name").replace('-', ' ').replace('_', ' ').titleCase()
        },
        docsUrl = obj.stringValue("docs_url").ifBlank { obj.stringValue("docsUrl") },
        fields = fields,
        setupDependenciesInstalled = (obj["setup"] as? JsonObject)
            ?.booleanValue("dependencies_installed"),
    )
}

private fun parseMemoryProviderConfigField(element: JsonElement): MemoryProviderConfigField? {
    val obj = element as? JsonObject ?: return null
    val key = obj.stringValue("key").trim()
    if (key.isEmpty()) return null

    val kind = when (obj.stringValue("kind").lowercase()) {
        "secret", "password" -> MemoryProviderFieldKind.SECRET
        "select", "choice", "enum" -> MemoryProviderFieldKind.SELECT
        "bool", "boolean", "toggle" -> MemoryProviderFieldKind.BOOL
        "number", "integer", "float", "double" -> MemoryProviderFieldKind.NUMBER
        "json", "object", "array" -> MemoryProviderFieldKind.JSON
        else -> MemoryProviderFieldKind.TEXT
    }

    val options = (obj["options"] as? JsonArray)
        ?.mapNotNull { option ->
            val optionObject = option as? JsonObject ?: return@mapNotNull null
            val value = optionObject.stringValue("value")
            if (value.isEmpty()) return@mapNotNull null
            MemoryProviderFieldOption(
                value = value,
                label = optionObject.stringValue("label").ifBlank { value },
                description = optionObject.stringValue("description"),
            )
        }
        .orEmpty()

    return MemoryProviderConfigField(
        key = key,
        label = obj.stringValue("label").ifBlank { key.replace('_', ' ').replace('-', ' ').titleCase() },
        kind = kind,
        description = obj.stringValue("description"),
        info = obj.stringValue("info"),
        placeholder = obj.stringValue("placeholder"),
        inline = obj.booleanValue("inline") ?: false,
        group = obj.stringValue("group"),
        required = obj.booleanValue("required") ?: false,
        value = if (kind == MemoryProviderFieldKind.SECRET) "" else obj.stringValue("value"),
        isSet = obj.booleanValue("is_set") ?: obj.booleanValue("isSet") ?: false,
        options = options,
        url = obj.stringValue("url"),
    )
}

internal fun parseMemoryProviderOAuthStatus(root: JsonElement): MemoryProviderOAuthStatus {
    val obj = root as? JsonObject ?: error(MEMORY_ERROR_OAUTH_STATUS_INVALID)
    val connected = obj.booleanValue("connected") ?: false
    val state = obj.stringValue("state").ifBlank {
        if (connected) "connected" else "idle"
    }
    return MemoryProviderOAuthStatus(
        auth = obj.stringValue("auth").ifBlank { "" }.ifEmpty { null },
        connected = connected,
        detail = obj.stringValue("detail").ifBlank {
            obj.stringValue("message").ifBlank { obj.stringValue("error") }
        },
        state = state,
    )
}

private fun JsonObject.stringValue(key: String): String {
    val value = this[key] ?: return ""
    return when (value) {
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        else -> value.toString()
    }
}

private fun JsonObject.booleanValue(key: String): Boolean? {
    val value = this[key] as? JsonPrimitive ?: return null
    return value.booleanOrNull ?: when (value.contentOrNull?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

private fun String.titleCase(): String = split(' ', '-', '_')
    .filter(String::isNotBlank)
    .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
