/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.manage.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.domain.model.ActionStatus
import com.hermesgadget.talaria.domain.model.HubSkill
import com.hermesgadget.talaria.domain.model.HubUpdateResponse
import com.hermesgadget.talaria.domain.model.SkillContentResponse
import com.hermesgadget.talaria.domain.model.SkillInfo
import com.hermesgadget.talaria.domain.model.SkillWriteResponse
import com.hermesgadget.talaria.domain.model.ToolsetInfo
import com.hermesgadget.talaria.domain.model.effectiveManagementProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.hermesgadget.talaria.core.util.suspendResult

interface SkillsGateway {
    suspend fun skills(): Result<List<SkillInfo>>
    suspend fun toolsets(): Result<List<ToolsetInfo>>
    suspend fun getToolsetConfig(name: String): Result<JsonElement>
    suspend fun putToolsetEnv(name: String, body: JsonObject): Result<JsonElement>
    suspend fun putToolsetModel(name: String, body: JsonObject): Result<JsonElement>
    suspend fun getToolsetModels(name: String): Result<JsonElement>
    suspend fun putToolsetProvider(name: String, body: JsonObject): Result<JsonElement>
    suspend fun runToolsetPostSetup(name: String, body: JsonObject): Result<JsonElement>
    suspend fun toggleSkill(name: String, enabled: Boolean): Result<Unit>
    suspend fun setToolset(name: String, enabled: Boolean): Result<Unit>
    suspend fun searchHub(query: String): Result<List<HubSkill>>
    suspend fun previewHub(identifier: String): Result<JsonElement>
    suspend fun scanHub(identifier: String): Result<JsonElement>
    suspend fun installHub(identifier: String): Result<ActionStatus>
    suspend fun uninstallHub(name: String): Result<ActionStatus>
    suspend fun getContent(name: String): Result<SkillContentResponse>
    suspend fun putContent(name: String, content: String): Result<SkillWriteResponse>
    suspend fun updateHub(): Result<HubUpdateResponse>
}

class HermesSkillsGateway(
    private val api: HermesApi,
    private val repo: HermesRepository,
    private val profileProvider: () -> String? = {
        TalariaApp.instance.container.connectionStore.activeProfile()?.effectiveManagementProfile()
    },
) : SkillsGateway {
    private fun profile(): String? = profileProvider()?.takeIf { it.isNotBlank() }

    override suspend fun skills(): Result<List<SkillInfo>> = repo.getSkills()
    override suspend fun toolsets(): Result<List<ToolsetInfo>> = repo.getToolsets()
    override suspend fun getToolsetConfig(name: String): Result<JsonElement> = suspendResult {
        api.getToolsetConfig(name, profile())
    }
    override suspend fun putToolsetEnv(name: String, body: JsonObject): Result<JsonElement> = suspendResult {
        api.putToolsetEnv(name, body, profile())
    }
    override suspend fun putToolsetModel(name: String, body: JsonObject): Result<JsonElement> = suspendResult {
        api.putToolsetModel(name, body, profile())
    }
    override suspend fun getToolsetModels(name: String): Result<JsonElement> = suspendResult {
        api.getToolsetModels(name, profile())
    }
    override suspend fun putToolsetProvider(name: String, body: JsonObject): Result<JsonElement> = suspendResult {
        api.putToolsetProvider(name, body, profile())
    }
    override suspend fun runToolsetPostSetup(name: String, body: JsonObject): Result<JsonElement> = suspendResult {
        api.runToolsetPostSetup(name, body, profile())
    }
    override suspend fun toggleSkill(name: String, enabled: Boolean): Result<Unit> = repo.toggleSkill(name, enabled)
    override suspend fun setToolset(name: String, enabled: Boolean): Result<Unit> = repo.setToolsetEnabled(name, enabled)
    override suspend fun searchHub(query: String): Result<List<HubSkill>> = repo.searchSkillHub(query)
    override suspend fun previewHub(identifier: String): Result<JsonElement> = repo.previewHubSkill(identifier)
    override suspend fun scanHub(identifier: String): Result<JsonElement> = repo.scanHubSkill(identifier)
    override suspend fun installHub(identifier: String): Result<ActionStatus> = repo.installHubSkill(identifier)
    override suspend fun uninstallHub(name: String): Result<ActionStatus> = repo.uninstallHubSkill(name)

    override suspend fun getContent(name: String): Result<SkillContentResponse> = suspendResult {
        parseSkillContentResponse(api.getSkillContentRaw(name, profile()))
    }

    override suspend fun putContent(name: String, content: String): Result<SkillWriteResponse> = suspendResult {
        val response = parseSkillWriteResponse(
            api.putSkillContentRaw(
                buildJsonObject {
                    put("name", name)
                    put("content", content)
                    profile()?.let { put("profile", it) }
                },
                profile(),
            ),
        )
        if (!response.ok) error(response.error ?: response.message ?: "Skill save failed")
        response
    }

    override suspend fun updateHub(): Result<HubUpdateResponse> = suspendResult {
        parseHubUpdateResponse(
            api.updateSkillsHubRaw(
                buildJsonObject { profile()?.let { put("profile", it) } },
                profile(),
            ),
        )
    }
}

data class SkillContentFields(
    val name: String,
    val description: String,
    val body: String,
)

internal fun clearSubmittedToolsetDrafts(
    currentDrafts: Map<String, String>,
    submitted: Map<String, String>,
    succeeded: Boolean,
): Map<String, String> {
    if (!succeeded) return currentDrafts
    return currentDrafts.filter { (key, value) -> submitted[key] != value }
}

internal fun shouldCloseSkillEditorAfterSave(
    submitted: SkillContentFields,
    current: SkillContentFields,
    succeeded: Boolean,
): Boolean = succeeded && submitted == current

sealed interface SkillEditorState {
    data object Closed : SkillEditorState
    data class Loading(val name: String) : SkillEditorState
    data class Ready(
        val targetName: String,
        val fields: SkillContentFields,
        val path: String? = null,
        val validationError: String? = null,
    ) : SkillEditorState
    data class Error(val name: String, val message: String) : SkillEditorState
}

data class SkillsContent(
    val skills: List<SkillInfo> = emptyList(),
    val toolsets: List<ToolsetInfo> = emptyList(),
    val hubResults: List<HubSkill> = emptyList(),
    val hubDetail: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val editor: SkillEditorState = SkillEditorState.Closed,
    val toolsetConfig: ToolsetConfigState = ToolsetConfigState.Closed,
)

data class ToolsetEnvField(
    val key: String,
    val prompt: String? = null,
    val url: String? = null,
    val defaultValue: String? = null,
    val isSet: Boolean = false,
)

data class ToolsetProviderConfig(
    val name: String,
    val badge: String? = null,
    val tag: String? = null,
    val envFields: List<ToolsetEnvField> = emptyList(),
    val postSetup: String? = null,
    val requiresNousAuth: Boolean = false,
    val isActive: Boolean = false,
    val status: String? = null,
)

data class ToolsetConfigData(
    val name: String,
    val hasCategory: Boolean,
    val providers: List<ToolsetProviderConfig> = emptyList(),
    val activeProvider: String? = null,
)

data class ToolsetModelOption(
    val id: String,
    val display: String,
    val speed: String? = null,
    val strengths: String? = null,
    val price: String? = null,
)

data class ToolsetModels(
    val name: String,
    val hasModels: Boolean,
    val provider: String? = null,
    val current: String? = null,
    val default: String? = null,
    val options: List<ToolsetModelOption> = emptyList(),
)

sealed interface ToolsetConfigState {
    data object Closed : ToolsetConfigState
    data class Loading(val name: String) : ToolsetConfigState
    data class Ready(
        val config: ToolsetConfigData,
        val models: ToolsetModels,
    ) : ToolsetConfigState
    data class Error(val name: String, val message: String) : ToolsetConfigState
}

sealed interface SkillsUiState {
    data object Loading : SkillsUiState
    data class Content(val value: SkillsContent) : SkillsUiState
    data class Failure(val message: String, val previous: SkillsContent? = null) : SkillsUiState
}

class SkillsViewModel(
    private val gateway: SkillsGateway,
) : ViewModel() {
    private val _ui = MutableStateFlow<SkillsUiState>(SkillsUiState.Loading)
    val ui: StateFlow<SkillsUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        _ui.value = SkillsUiState.Loading
        viewModelScope.launch {
            suspendResult {
                val skills = gateway.skills().getOrThrow()
                val toolsets = gateway.toolsets().getOrThrow()
                skills to toolsets
            }.onSuccess { (skills, toolsets) ->
                _ui.value = SkillsUiState.Content(SkillsContent(skills = skills, toolsets = toolsets))
            }.onFailure { error -> _ui.value = SkillsUiState.Failure(error.message ?: "Could not load skills") }
        }
    }

    fun toggleSkill(name: String, enabled: Boolean) {
        mutate("Could not update skill") { gateway.toggleSkill(name, enabled) }
    }

    fun setToolset(name: String, enabled: Boolean) {
        mutate("Could not update toolset") { gateway.setToolset(name, enabled) }
    }

    fun getToolsetConfig(name: String) {
        updateContent {
            it.copy(
                busy = true,
                message = null,
                toolsetConfig = ToolsetConfigState.Loading(name),
            )
        }
        loadToolsetConfig(name)
    }

    fun closeToolsetConfig() = updateContent { it.copy(toolsetConfig = ToolsetConfigState.Closed) }

    fun putToolsetProvider(name: String, provider: String) {
        runToolsetAction(name, "Could not update toolset") {
            gateway.putToolsetProvider(
                name,
                buildJsonObject { put("provider", provider) },
            )
        }
    }

    fun putToolsetEnv(
        name: String,
        values: Map<String, String>,
        onResult: (Boolean) -> Unit = {},
    ) {
        val env = buildJsonObject {
            values.forEach { (key, value) -> put(key, value) }
        }
        runToolsetAction(
            name = name,
            errorFallback = "Could not update toolset",
            onResult = onResult,
        ) {
            gateway.putToolsetEnv(name, buildJsonObject { put("env", env) })
        }
    }

    fun putToolsetModel(name: String, model: String, provider: String? = null) {
        runToolsetAction(name, "Could not update toolset") {
            gateway.putToolsetModel(
                name,
                buildJsonObject {
                    put("model", model)
                    provider?.takeIf { it.isNotBlank() }?.let { put("provider", it) }
                },
            )
        }
    }

    fun runToolsetPostSetup(name: String, key: String) {
        setBusy(true)
        viewModelScope.launch {
            gateway.runToolsetPostSetup(
                name,
                buildJsonObject { put("key", key) },
            ).fold(
                onSuccess = { updateContent { it.copy(busy = false) } },
                onFailure = { error ->
                    updateContent {
                        it.copy(busy = false, message = error.message ?: "Could not update toolset")
                    }
                },
            )
        }
    }

    fun searchHub(query: String) {
        if (query.isBlank()) return
        setBusy(true)
        viewModelScope.launch {
            gateway.searchHub(query).fold(
                onSuccess = { results -> updateContent { it.copy(hubResults = results, hubDetail = null, busy = false) } },
                onFailure = { error -> updateContent { it.copy(busy = false, message = error.message) } },
            )
        }
    }

    fun previewHub(identifier: String) = runHubAction {
        gateway.previewHub(identifier).map { result ->
            val obj = result as? JsonObject
            obj?.get("skill_md")?.jsonPrimitive?.contentOrNull ?: result.toString().take(6_000)
        }
    }

    fun scanHub(identifier: String) = runHubAction { gateway.scanHub(identifier).map { it.toString().take(6_000) } }

    fun installHub(identifier: String) = runHubAction {
        gateway.installHub(identifier).map { it.lines.lastOrNull() ?: "Installed $identifier" }
    }

    fun uninstallHub(name: String) = runHubAction {
        gateway.uninstallHub(name).map { it.lines.lastOrNull() ?: "Uninstalled $name" }
    }

    fun updateHub() = runHubAction {
        gateway.updateHub().map { response ->
            if (response.ok) "Skill Hub update started${response.pid?.let { " (pid $it)" }.orEmpty()}" else "Skill Hub update failed"
        }
    }

    fun openEditor(name: String) {
        updateContent { it.copy(editor = SkillEditorState.Loading(name), message = null) }
        viewModelScope.launch {
            gateway.getContent(name).fold(
                onSuccess = { response ->
                    updateContent {
                        it.copy(
                            busy = false,
                            editor = SkillEditorState.Ready(
                                targetName = response.name.ifBlank { name },
                                fields = parseSkillContent(response.content, response.name.ifBlank { name }),
                                path = response.path,
                            ),
                        )
                    }
                },
                onFailure = { error -> updateContent { it.copy(editor = SkillEditorState.Error(name, error.message ?: "Could not load skill content")) } },
            )
        }
    }

    fun closeEditor() = updateContent { it.copy(editor = SkillEditorState.Closed) }

    fun saveContent(
        targetName: String,
        fields: SkillContentFields,
        onResult: (Boolean) -> Unit = {},
    ) {
        val validation = validateSkillContent(fields)
        if (validation != null) {
            updateContent {
                it.copy(editor = SkillEditorState.Ready(targetName, fields, validationError = validation))
            }
            onResult(false)
            return
        }
        updateContent { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            gateway.putContent(targetName, buildSkillContent(fields)).fold(
                onSuccess = { response ->
                    updateContent {
                        it.copy(
                            busy = false,
                            message = response.message ?: "Skill saved",
                        )
                    }
                    onResult(true)
                    refreshSkillsOnly()
                },
                onFailure = { error ->
                    updateContent { it.copy(busy = false, message = error.message ?: "Could not save skill") }
                    onResult(false)
                },
            )
        }
    }

    private fun runHubAction(action: suspend () -> Result<String>) {
        setBusy(true)
        viewModelScope.launch {
            action().fold(
                onSuccess = { result -> updateContent { it.copy(busy = false, message = result) }; refreshSkillsOnly() },
                onFailure = { error -> updateContent { it.copy(busy = false, message = error.message ?: "Skill Hub action failed") } },
            )
        }
    }

    private fun runToolsetAction(
        name: String,
        errorFallback: String,
        onResult: (Boolean) -> Unit = {},
        action: suspend () -> Result<JsonElement>,
    ) {
        setBusy(true)
        viewModelScope.launch {
            action().fold(
                onSuccess = {
                    onResult(true)
                    loadToolsetConfig(name)
                },
                onFailure = { error ->
                    onResult(false)
                    updateContent {
                        it.copy(busy = false, message = error.message ?: errorFallback)
                    }
                },
            )
        }
    }

    private fun loadToolsetConfig(name: String) {
        viewModelScope.launch {
            suspendResult {
                val config = parseToolsetConfig(gateway.getToolsetConfig(name).getOrThrow())
                    .let { parsed -> if (parsed.name.isBlank()) parsed.copy(name = name) else parsed }
                val models = gateway.getToolsetModels(name)
                    .getOrNull()
                    ?.let(::parseToolsetModels)
                    ?.let { parsed -> if (parsed.name.isBlank()) parsed.copy(name = name) else parsed }
                    ?: ToolsetModels(name = name, hasModels = false)
                config to models
            }.fold(
                onSuccess = { (config, models) ->
                    updateContent {
                        it.copy(
                            busy = false,
                            message = null,
                            toolsetConfig = ToolsetConfigState.Ready(config, models),
                        )
                    }
                },
                onFailure = { error ->
                    updateContent {
                        it.copy(
                            busy = false,
                            message = error.message,
                            toolsetConfig = ToolsetConfigState.Error(
                                name,
                                error.message ?: "Could not load skills",
                            ),
                        )
                    }
                },
            )
        }
    }

    private fun mutate(message: String, action: suspend () -> Result<Unit>) {
        setBusy(true)
        viewModelScope.launch {
            action().fold(
                onSuccess = { refresh() },
                onFailure = { error -> updateContent { it.copy(busy = false, message = error.message ?: message) } },
            )
        }
    }

    private fun refreshSkillsOnly() {
        viewModelScope.launch {
            gateway.skills().onSuccess { skills -> updateContent { it.copy(skills = skills) } }
        }
    }

    private fun setBusy(busy: Boolean) = updateContent { it.copy(busy = busy, message = null) }

    private fun updateContent(block: (SkillsContent) -> SkillsContent) {
        _ui.update { state ->
            when (state) {
                SkillsUiState.Loading -> SkillsUiState.Content(block(SkillsContent()))
                is SkillsUiState.Content -> SkillsUiState.Content(block(state.value))
                is SkillsUiState.Failure -> state.previous?.let { SkillsUiState.Content(block(it)) } ?: state
            }
        }
    }

    companion object {
        fun factory(
            gateway: SkillsGateway = HermesSkillsGateway(
                api = TalariaApp.instance.container.clientFactory.apiForActive(),
                repo = TalariaApp.instance.container.hermesRepository,
            ),
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SkillsViewModel(gateway) as T
        }
    }
}

internal fun parseSkillContent(content: String, fallbackName: String): SkillContentFields {
    val lines = content.replace("\r\n", "\n").split('\n')
    if (lines.firstOrNull()?.trim() != "---") {
        return SkillContentFields(fallbackName, "", content.trim())
    }
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }.takeIf { it >= 0 }?.plus(1)
        ?: return SkillContentFields(fallbackName, "", content.trim())
    val metadata = lines.subList(1, end).mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) null else line.substring(0, separator).trim() to unquoteYaml(line.substring(separator + 1).trim())
    }.toMap()
    val body = lines.drop(end + 1).joinToString("\n").trimStart('\n')
    return SkillContentFields(
        name = metadata["name"].orEmpty().ifBlank { fallbackName },
        description = metadata["description"].orEmpty(),
        body = body.trimEnd(),
    )
}

internal fun validateSkillContent(fields: SkillContentFields): String? = when {
    fields.name.trim().isBlank() -> "Name is required"
    !SKILL_NAME.matches(fields.name.trim()) -> "Name may contain letters, numbers, '.', '_' and '-'"
    fields.description.trim().isBlank() -> "Description is required"
    fields.body.trim().isBlank() -> "Body is required"
    else -> null
}

internal fun buildSkillContent(fields: SkillContentFields): String =
    "---\n" +
        "name: ${yamlScalar(fields.name.trim())}\n" +
        "description: ${yamlScalar(fields.description.trim())}\n" +
        "---\n\n" +
        fields.body.trimEnd() + "\n"

private fun yamlScalar(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")}\""

private fun unquoteYaml(value: String): String {
    val trimmed = value.trim()
    if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
        return trimmed.substring(1, trimmed.length - 1)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
    return trimmed.removeSurrounding("'")
}

private val SKILL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

private fun parseSkillContentResponse(root: JsonElement): SkillContentResponse {
    val obj = root as? JsonObject ?: error("Invalid skill content response")
    val name = obj["name"].asString() ?: error("Missing skill name")
    return SkillContentResponse(name, obj["content"].asString() ?: "", obj["path"].asString())
}

private fun parseSkillWriteResponse(root: JsonElement): SkillWriteResponse {
    val obj = root as? JsonObject
        ?: return SkillWriteResponse(
            ok = false,
            error = "Invalid skill write response",
        )
    val explicitSuccess = obj["ok"].asBoolean() ?: obj["success"].asBoolean()
    return SkillWriteResponse(
        ok = explicitSuccess ?: false,
        message = obj["message"].asString(),
        path = obj["path"].asString(),
        error = obj["error"].asString(),
    )
}

private fun parseHubUpdateResponse(root: JsonElement): HubUpdateResponse {
    val obj = root as? JsonObject ?: return HubUpdateResponse()
    return HubUpdateResponse(
        ok = obj["ok"].asBoolean() ?: false,
        pid = obj["pid"].asString()?.toIntOrNull(),
        name = obj["name"].asString(),
    )
}

internal fun parseToolsetConfig(root: JsonElement): ToolsetConfigData {
    val obj = root as? JsonObject ?: error("Could not load skills")
    val providers = (obj["providers"] as? JsonArray).orEmpty().mapNotNull { element ->
        val provider = element as? JsonObject ?: return@mapNotNull null
        val name = provider["name"].asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val envFields = (provider["env_vars"] ?: provider["envVars"])
            .asJsonArray()
            .mapNotNull { fieldElement ->
                val field = fieldElement as? JsonObject ?: return@mapNotNull null
                val key = field["key"].asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ToolsetEnvField(
                    key = key,
                    prompt = field["prompt"].asString(),
                    url = field["url"].asString(),
                    defaultValue = field["default"].asString(),
                    isSet = field["is_set"].asBoolean() ?: field["isSet"].asBoolean() ?: false,
                )
            }
        ToolsetProviderConfig(
            name = name,
            badge = provider["badge"].asString(),
            tag = provider["tag"].asString(),
            envFields = envFields,
            postSetup = provider["post_setup"].asString() ?: provider["postSetup"].asString(),
            requiresNousAuth = provider["requires_nous_auth"].asBoolean()
                ?: provider["requiresNousAuth"].asBoolean()
                ?: false,
            isActive = provider["is_active"].asBoolean()
                ?: provider["isActive"].asBoolean()
                ?: false,
            status = provider["status"].asString(),
        )
    }
    return ToolsetConfigData(
        name = obj["name"].asString().orEmpty(),
        hasCategory = obj["has_category"].asBoolean()
            ?: obj["hasCategory"].asBoolean()
            ?: providers.isNotEmpty(),
        providers = providers,
        activeProvider = obj["active_provider"].asString() ?: obj["activeProvider"].asString(),
    )
}

internal fun parseToolsetModels(root: JsonElement): ToolsetModels {
    val obj = root as? JsonObject ?: error("Could not load skills")
    val options = (obj["models"] as? JsonArray).orEmpty().mapNotNull { element ->
        when (element) {
            is JsonObject -> {
                val id = element["id"].asString()
                    ?: element["model"].asString()
                    ?: return@mapNotNull null
                ToolsetModelOption(
                    id = id,
                    display = element["display"].asString()
                        ?: element["name"].asString()
                        ?: id,
                    speed = element["speed"].asString(),
                    strengths = element["strengths"].asString(),
                    price = element["price"].asString(),
                )
            }
            is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                ToolsetModelOption(id = it, display = it)
            }
            else -> null
        }
    }
    return ToolsetModels(
        name = obj["name"].asString().orEmpty(),
        hasModels = obj["has_models"].asBoolean()
            ?: obj["hasModels"].asBoolean()
            ?: options.isNotEmpty(),
        provider = obj["provider"].asString(),
        current = obj["current"].asString(),
        default = obj["default"].asString(),
        options = options,
    )
}

private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

private fun JsonElement?.asJsonArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
