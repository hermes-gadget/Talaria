/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.manage.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.domain.model.AutomationBlueprint
import com.hermesgadget.talaria.domain.model.AutomationBlueprintField
import com.hermesgadget.talaria.domain.model.CronDeliveryTarget
import com.hermesgadget.talaria.domain.model.CronRun
import com.hermesgadget.talaria.domain.model.ManageCronJob
import com.hermesgadget.talaria.domain.model.effectiveManagementProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class CronSnapshot(
    val jobs: List<ManageCronJob>,
    val deliveryTargets: List<CronDeliveryTarget>,
    val blueprints: List<AutomationBlueprint>,
)

interface CronGateway {
    suspend fun load(): CronSnapshot
    suspend fun loadRuns(jobId: String, limit: Int = 50): List<CronRun>
    suspend fun create(prompt: String, schedule: String, name: String?, deliver: String)
    suspend fun update(jobId: String, prompt: String, schedule: String, deliver: String)
    suspend fun pause(jobId: String)
    suspend fun resume(jobId: String)
    suspend fun trigger(jobId: String)
    suspend fun delete(jobId: String)
    suspend fun instantiate(blueprint: String, values: Map<String, String>)
}

class HermesCronGateway(
    private val api: HermesApi,
    private val profileProvider: () -> String? = {
        TalariaApp.instance.container.connectionStore.activeProfile()?.effectiveManagementProfile()
    },
) : CronGateway {
    private fun profile(): String? = profileProvider()?.takeIf { it.isNotBlank() }

    override suspend fun load(): CronSnapshot {
        val profile = profile()
        return CronSnapshot(
            jobs = parseCronJobs(api.getCronJobsRaw(profile)),
            deliveryTargets = parseDeliveryTargets(api.getCronDeliveryTargetsRaw(profile)),
            blueprints = parseBlueprints(api.getCronBlueprintsRaw(profile)),
        )
    }

    override suspend fun loadRuns(jobId: String, limit: Int): List<CronRun> =
        parseCronRuns(api.getCronJobRunsRaw(jobId, limit, profile()))

    override suspend fun create(prompt: String, schedule: String, name: String?, deliver: String) {
        api.createCronJobRaw(
            buildJsonObject {
                put("prompt", prompt)
                put("schedule", schedule)
                name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
                put("deliver", deliver)
            },
            profile(),
        )
    }

    override suspend fun update(jobId: String, prompt: String, schedule: String, deliver: String) {
        api.updateCronJobRaw(
            jobId,
            buildJsonObject {
                put(
                    "updates",
                    buildJsonObject {
                        put("prompt", prompt)
                        put("schedule", schedule)
                        put("deliver", deliver)
                    },
                )
            },
            profile(),
        )
    }

    override suspend fun pause(jobId: String) {
        api.pauseCronRaw(jobId, profile())
    }

    override suspend fun resume(jobId: String) {
        api.resumeCronRaw(jobId, profile())
    }

    override suspend fun trigger(jobId: String) {
        api.triggerCronRaw(jobId, profile())
    }

    override suspend fun delete(jobId: String) {
        api.deleteCronRaw(jobId, profile())
    }

    override suspend fun instantiate(blueprint: String, values: Map<String, String>) {
        api.instantiateCronBlueprintRaw(
            buildJsonObject {
                put("blueprint", blueprint)
                put(
                    "values",
                    buildJsonObject { values.forEach { (key, value) -> put(key, value) } },
                )
            },
            profile(),
        )
    }
}

sealed interface CronUiState {
    data object Loading : CronUiState

    data class Content(
        val jobs: List<ManageCronJob> = emptyList(),
        val deliveryTargets: List<CronDeliveryTarget> = emptyList(),
        val blueprints: List<AutomationBlueprint> = emptyList(),
        val runs: Map<String, List<CronRun>> = emptyMap(),
        val expandedJobs: Set<String> = emptySet(),
        val busyJobId: String? = null,
        val busy: Boolean = false,
        val message: String? = null,
    ) : CronUiState

    data class Failure(val message: String, val previous: Content? = null) : CronUiState
}

class CronViewModel(
    private val gateway: CronGateway,
) : ViewModel() {
    private val _ui = MutableStateFlow<CronUiState>(CronUiState.Loading)
    val ui: StateFlow<CronUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _ui.value = CronUiState.Loading
        viewModelScope.launch {
            runCatching { gateway.load() }
                .onSuccess { snapshot ->
                    _ui.value = CronUiState.Content(
                        jobs = snapshot.jobs,
                        deliveryTargets = snapshot.deliveryTargets.ifEmpty { listOf(localTarget) },
                        blueprints = snapshot.blueprints,
                    )
                }
                .onFailure { error -> _ui.value = CronUiState.Failure(error.message ?: "Could not load cron") }
        }
    }

    fun toggleRuns(jobId: String) {
        val state = _ui.value as? CronUiState.Content ?: return
        if (jobId in state.expandedJobs) {
            _ui.value = state.copy(expandedJobs = state.expandedJobs - jobId)
            return
        }
        if (state.runs.containsKey(jobId)) {
            _ui.value = state.copy(expandedJobs = state.expandedJobs + jobId)
            return
        }
        _ui.value = state.copy(expandedJobs = state.expandedJobs + jobId, busyJobId = jobId, message = null)
        viewModelScope.launch {
            runCatching { gateway.loadRuns(jobId) }
                .onSuccess { runs ->
                    _ui.update { current ->
                        val content = current as? CronUiState.Content ?: return@update current
                        content.copy(runs = content.runs + (jobId to runs), busyJobId = null)
                    }
                }
                .onFailure { error ->
                    _ui.update { current ->
                        val content = current as? CronUiState.Content ?: return@update current
                        content.copy(busyJobId = null, message = error.message ?: "Could not load run history")
                    }
                }
        }
    }

    fun create(prompt: String, schedule: String, name: String?, deliver: String) {
        if (prompt.isBlank() || schedule.isBlank()) {
            setMessage("Prompt and schedule are required")
            return
        }
        mutate("Could not create cron job") {
            gateway.create(prompt.trim(), schedule.trim(), name?.trim(), deliver)
        }
    }

    fun update(jobId: String, prompt: String, schedule: String, deliver: String) {
        if (prompt.isBlank() || schedule.isBlank()) {
            setMessage("Prompt and schedule are required")
            return
        }
        mutate("Could not save cron job") {
            gateway.update(jobId, prompt.trim(), schedule.trim(), deliver)
        }
    }

    fun pause(jobId: String) = mutate("Could not pause cron job") { gateway.pause(jobId) }
    fun resume(jobId: String) = mutate("Could not resume cron job") { gateway.resume(jobId) }
    fun trigger(jobId: String) = mutate("Could not run cron job") { gateway.trigger(jobId) }
    fun delete(jobId: String) = mutate("Could not delete cron job") { gateway.delete(jobId) }

    fun instantiateBlueprint(blueprint: String, values: Map<String, String>) {
        mutate("Could not instantiate blueprint") {
            gateway.instantiate(blueprint, values)
        }
    }

    fun clearMessage() {
        _ui.update { current ->
            (current as? CronUiState.Content)?.copy(message = null) ?: current
        }
    }

    private fun setMessage(message: String) {
        _ui.update { current ->
            (current as? CronUiState.Content)?.copy(message = message) ?: current
        }
    }

    private fun mutate(failureMessage: String, action: suspend () -> Unit) {
        val state = _ui.value as? CronUiState.Content ?: return
        _ui.value = state.copy(busy = true, message = null)
        viewModelScope.launch {
            runCatching { action() }
                .onSuccess {
                    runCatching { gateway.load() }
                        .onSuccess { snapshot ->
                            _ui.value = CronUiState.Content(
                                jobs = snapshot.jobs,
                                deliveryTargets = snapshot.deliveryTargets.ifEmpty { listOf(localTarget) },
                                blueprints = snapshot.blueprints,
                            )
                        }
                        .onFailure { error ->
                            _ui.value = CronUiState.Failure(error.message ?: "Could not refresh cron")
                        }
                }
                .onFailure { error ->
                    _ui.update { current ->
                        val content = current as? CronUiState.Content ?: return@update current
                        content.copy(busy = false, message = error.message ?: failureMessage)
                    }
                }
        }
    }

    companion object {
        private val localTarget = CronDeliveryTarget("local", "Local (save only)", homeTargetSet = true)

        fun factory(gateway: CronGateway = HermesCronGateway(TalariaApp.instance.container.clientFactory.api())) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CronViewModel(gateway) as T
            }
    }
}

internal fun parseCronJobs(root: JsonElement): List<ManageCronJob> =
    elementsFrom(root, "jobs").mapNotNull { it.asObject()?.let(::parseCronJob) }

internal fun parseCronJob(obj: JsonObject): ManageCronJob? {
    val id = obj.string("id") ?: return null
    val schedule = obj["schedule"]
    val scheduleObject = schedule?.asObject()
    val state = obj.string("state") ?: obj.string("status")
    return ManageCronJob(
        id = id,
        name = obj.string("name") ?: obj.string("title"),
        prompt = obj.string("prompt") ?: obj.string("command"),
        scheduleExpression = scheduleObject?.string("expr")
            ?: scheduleObject?.string("expression")
            ?: schedule?.asString(),
        scheduleDisplay = scheduleObject?.string("display")
            ?: scheduleObject?.string("human")
            ?: obj.string("schedule_display"),
        state = state,
        enabled = obj.boolean("enabled") ?: (state?.lowercase() !in setOf("paused", "disabled")),
        deliver = obj.string("deliver") ?: obj.string("delivery_target") ?: obj.string("deliveryTarget"),
        lastRunAt = obj.string("last_run") ?: obj.string("last_run_at") ?: obj.string("lastRunAt"),
        nextRunAt = obj.string("next_run") ?: obj.string("next_run_at") ?: obj.string("nextRunAt"),
        lastStatus = obj.string("last_status") ?: obj.string("lastStatus"),
        lastError = obj.string("last_error") ?: obj.string("lastError"),
        lastDeliveryError = obj.string("last_delivery_error") ?: obj.string("lastDeliveryError"),
        profile = obj.string("profile"),
        isActive = obj.boolean("is_active") ?: obj.boolean("active"),
    )
}

internal fun parseCronRuns(root: JsonElement): List<CronRun> =
    elementsFrom(root, "runs").mapNotNull { element ->
        val obj = element.asObject() ?: return@mapNotNull null
        val id = obj.string("id") ?: return@mapNotNull null
        val endReason = obj.string("end_reason") ?: obj.string("endReason")
        val active = obj.boolean("is_active") ?: obj.boolean("active") ?: false
        val status = obj.string("status") ?: obj.string("run_status") ?: when {
            active -> "running"
            !endReason.isNullOrBlank() -> endReason
            else -> "completed"
        }
        CronRun(
            id = id,
            status = status,
            startedAt = obj.string("started_at") ?: obj.string("start_time") ?: obj.string("startedAt"),
            endedAt = obj.string("ended_at") ?: obj.string("end_time") ?: obj.string("endedAt"),
            output = obj.string("output")
                ?: obj.string("result")
                ?: obj.string("response")
                ?: obj.string("preview"),
            error = obj.string("error") ?: obj.string("last_error"),
            preview = obj.string("preview") ?: obj.string("title"),
            endReason = endReason,
            model = obj.string("model"),
            messageCount = obj.int("message_count") ?: obj.int("messageCount"),
            toolCallCount = obj.int("tool_call_count") ?: obj.int("toolCallCount"),
            inputTokens = obj.long("input_tokens") ?: obj.long("inputTokens"),
            outputTokens = obj.long("output_tokens") ?: obj.long("outputTokens"),
            isActive = active,
            raw = obj.toString(),
        ).let { run ->
            if (run.output == null && run.preview == null && run.error == null) {
                run.copy(output = endReason)
            } else {
                run
            }
        }
    }

internal fun parseDeliveryTargets(root: JsonElement): List<CronDeliveryTarget> =
    elementsFrom(root, "targets", "delivery_targets").mapNotNull { element ->
        val obj = element.asObject() ?: return@mapNotNull null
        val id = obj.string("id") ?: obj.string("name") ?: return@mapNotNull null
        CronDeliveryTarget(
            id = id,
            name = obj.string("name") ?: id,
            homeTargetSet = obj.boolean("home_target_set") ?: obj.boolean("homeTargetSet") ?: false,
            homeEnvVar = obj.string("home_env_var") ?: obj.string("homeEnvVar"),
        )
    }

internal fun parseBlueprints(root: JsonElement): List<AutomationBlueprint> =
    elementsFrom(root, "blueprints").mapNotNull { element ->
        val obj = element.asObject() ?: return@mapNotNull null
        val key = obj.string("key") ?: obj.string("name") ?: return@mapNotNull null
        val fields = elementsFrom(obj["fields"] ?: JsonNull).mapNotNull fieldLoop@{ fieldElement ->
            val field = fieldElement.asObject() ?: return@fieldLoop null
            val name = field.string("name") ?: return@fieldLoop null
            AutomationBlueprintField(
                name = name,
                type = field.string("type") ?: "text",
                label = field.string("label"),
                defaultValue = field["default"]?.asString() ?: field.string("defaultValue"),
                options = (field["options"]?.asArray() ?: emptyList()).mapNotNull { it.asString() },
                optional = field.boolean("optional") ?: false,
                strict = field.boolean("strict") ?: false,
                help = field.string("help"),
            )
        }
        AutomationBlueprint(
            key = key,
            title = obj.string("title") ?: key,
            description = obj.string("description"),
            category = obj.string("category"),
            tags = (obj["tags"]?.asArray() ?: emptyList()).mapNotNull { it.asString() },
            fields = fields,
            schedule = obj.string("schedule"),
            scheduleHuman = obj.string("scheduleHuman") ?: obj.string("schedule_human"),
            command = obj.string("command"),
            appUrl = obj.string("appUrl") ?: obj.string("app_url"),
        )
    }

internal fun initialBlueprintValues(blueprint: AutomationBlueprint): Map<String, String> =
    blueprint.fields.mapNotNull { field ->
        field.defaultValue?.let { field.name to it }
    }.toMap()

private fun elementsFrom(root: JsonElement, vararg keys: String): List<JsonElement> {
    root.asArray()?.let { return it }
    val objectRoot = root.asObject() ?: return emptyList()
    keys.forEach { key -> objectRoot[key]?.asArray()?.let { return it } }
    return emptyList()
}

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
private fun JsonElement.asArray(): JsonArray? = this as? JsonArray
private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonObject.string(key: String): String? = this[key]?.asString()
private fun JsonObject.boolean(key: String): Boolean? = this[key]?.let { element ->
    (element as? JsonPrimitive)?.booleanOrNull
}
private fun JsonObject.int(key: String): Int? = this[key]?.asString()?.toIntOrNull()
private fun JsonObject.long(key: String): Long? = this[key]?.asString()?.toLongOrNull()
