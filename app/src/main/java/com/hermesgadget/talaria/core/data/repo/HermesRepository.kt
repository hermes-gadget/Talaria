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


package com.hermesgadget.talaria.core.data.repo

import android.content.Context
import com.hermesgadget.talaria.core.data.db.ActivityEventEntity
import com.hermesgadget.talaria.core.data.db.CachedMessageEntity
import com.hermesgadget.talaria.core.data.db.CachedSessionEntity
import com.hermesgadget.talaria.core.data.db.TalariaDatabase
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.network.HermesClientFactory
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.domain.model.AnalyticsUsage
import com.hermesgadget.talaria.domain.model.ConfigSchemaResponse
import com.hermesgadget.talaria.domain.model.CronJob
import com.hermesgadget.talaria.domain.model.EnvVarInfo
import com.hermesgadget.talaria.domain.model.CuratorState
import com.hermesgadget.talaria.domain.model.FsCwd
import com.hermesgadget.talaria.domain.model.FsEntry
import com.hermesgadget.talaria.domain.model.FsTextFile
import com.hermesgadget.talaria.domain.model.LearningGraph
import com.hermesgadget.talaria.domain.model.McpServer
import com.hermesgadget.talaria.domain.model.McpCatalogEntry
import com.hermesgadget.talaria.domain.model.MemoryState
import com.hermesgadget.talaria.domain.model.MessagingPlatform
import com.hermesgadget.talaria.domain.model.ModelInfo
import com.hermesgadget.talaria.domain.model.ModelOption
import com.hermesgadget.talaria.domain.model.ModelProvider
import com.hermesgadget.talaria.domain.model.PairingResponse
import com.hermesgadget.talaria.domain.model.ProfileInfo
import com.hermesgadget.talaria.domain.model.SessionMessage
import com.hermesgadget.talaria.domain.model.SessionSummary
import com.hermesgadget.talaria.domain.model.SkillInfo
import com.hermesgadget.talaria.domain.model.StatusResponse
import com.hermesgadget.talaria.domain.model.SystemStats
import com.hermesgadget.talaria.domain.model.ToolsetInfo
import com.hermesgadget.talaria.domain.model.WebhookRoute
import com.hermesgadget.talaria.domain.model.WebhooksResponse
import com.hermesgadget.talaria.domain.model.scopeId
import com.hermesgadget.talaria.domain.model.effectiveManagementProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class HermesRepository(
    private val clientFactory: HermesClientFactory,
    private val db: TalariaDatabase,
    private val connectionStore: SecureConnectionStore,
    private val appContext: Context? = null,
) {
    private val json = JsonConfig.json
    private fun connId() = connectionStore.activeProfile()?.scopeId() ?: "none"
    private fun api() = clientFactory.api()

    // Read-through cache so flipping between Manage menus is instant. Only the
    // "open once, browse" surfaces route through it; live pollers (Status) and
    // security-sensitive reads (pairing) fetch fresh every time.
    private val cache = ResponseCache()

    /** Drop all cached responses (call on profile / management-scope change). */
    fun clearCache() = cache.clear()

    private fun invalidate(vararg keys: String) {
        val conn = connId()
        keys.forEach { cache.invalidate("$conn:$it") }
    }

    private suspend fun <T> cached(
        key: String,
        ttlMs: Long = DEFAULT_CACHE_TTL_MS,
        fetch: suspend () -> T,
    ): Result<T> = cache.readThrough("${connId()}:$key", ttlMs) {
        withContext(Dispatchers.IO) { fetch() }
    }

    private companion object {
        const val DEFAULT_CACHE_TTL_MS = 20_000L
        // Schema/defaults are effectively static for a gateway lifetime.
        const val STATIC_CACHE_TTL_MS = 300_000L
    }

    fun observeSessions(): Flow<List<CachedSessionEntity>> = db.sessions().observeSessions(connId())
    fun observeActivity(): Flow<List<ActivityEventEntity>> = db.activity().observe(connId())

    suspend fun refreshStatus(): Result<StatusResponse> = withContext(Dispatchers.IO) {
        runCatching { api().getStatus() }
    }

    suspend fun refreshSessions(
        source: String? = null,
        limit: Int = 50,
    ): Result<List<SessionSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val page = fetchSessionsPage(source = source, limit = limit)
            val list = page.sessions
            val cid = connId()
            db.sessions().upsertAll(
                list.map {
                    CachedSessionEntity(
                        id = it.id,
                        connectionId = cid,
                        title = it.title,
                        source = it.source,
                        model = it.model,
                        preview = it.preview,
                        messageCount = it.message_count,
                        lastActive = it.last_active,
                        json = json.encodeToString(it),
                    )
                },
            )
            list
        }
    }

    suspend fun getSessionsPage(
        source: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Result<com.hermesgadget.talaria.domain.model.SessionsPage> = withContext(Dispatchers.IO) {
        runCatching { fetchSessionsPage(source = source, limit = limit, offset = offset) }
    }

    private suspend fun fetchSessionsPage(
        source: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): com.hermesgadget.talaria.domain.model.SessionsPage {
        val element = api().getSessions(limit = limit, offset = offset, source = source)
        return parseSessionsPage(element)
    }

    private fun parseSessions(element: JsonElement): List<SessionSummary> =
        parseSessionsPage(element).sessions

    private fun parseSessionsPage(element: JsonElement): com.hermesgadget.talaria.domain.model.SessionsPage =
        when (element) {
            is JsonArray -> com.hermesgadget.talaria.domain.model.SessionsPage(
                sessions = element.map { json.decodeFromJsonElement(it) },
                total = element.size,
            )
            is JsonObject -> {
                val arr = element["sessions"]?.jsonArray ?: element["results"]?.jsonArray
                val sessions = arr?.mapNotNull {
                    runCatching { json.decodeFromJsonElement<SessionSummary>(it) }.getOrNull()
                } ?: emptyList()
                val total = element["total"]?.let {
                    runCatching { it.toString().trim('"').toInt() }.getOrNull()
                } ?: sessions.size
                com.hermesgadget.talaria.domain.model.SessionsPage(sessions = sessions, total = total)
            }
            else -> com.hermesgadget.talaria.domain.model.SessionsPage()
        }

    /** Single-session summary (model, tokens, live flag) for the detail header. */
    suspend fun getSession(sessionId: String): Result<SessionSummary> = withContext(Dispatchers.IO) {
        runCatching { api().getSession(sessionId) }
    }

    suspend fun loadMessages(sessionId: String): Result<List<SessionMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val msgs = api().getSessionMessages(sessionId).messages
            val cid = connId()
            db.messages().clearSession(cid, sessionId)
            db.messages().upsertAll(
                msgs.mapIndexed { index, m ->
                    CachedMessageEntity(
                        key = "$sessionId-$index",
                        sessionId = sessionId,
                        connectionId = cid,
                        role = m.role,
                        content = m.content,
                        timestamp = m.timestamp,
                        ordinal = index,
                    )
                },
            )
            msgs
        }
    }

    suspend fun getConfig(): Result<JsonObject> = cached("config") { api().getConfig() }

    suspend fun putConfig(config: JsonObject): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api().putConfig(buildJsonObject { put("config", config) })
            invalidate("config")
            Unit
        }
    }

    suspend fun getEnv(): Result<Map<String, EnvVarInfo>> = cached("env") {
        mergeEnvCatalog(api().getEnv())
    }

    /** Merge bundled `env_catalog.json` metadata with live `/api/env` set/redacted state. */
    private fun mergeEnvCatalog(live: Map<String, EnvVarInfo>): Map<String, EnvVarInfo> {
        val ctx = appContext ?: return live
        val catalog = runCatching {
            ctx.assets.open("env_catalog.json").bufferedReader().use { it.readText() }
                .let { json.decodeFromString<EnvCatalogFile>(it) }
        }.getOrNull() ?: return live
        val out = linkedMapOf<String, EnvVarInfo>()
        for (group in catalog.groups) {
            for (entry in group.keys) {
                val existing = live[entry.key]
                out[entry.key] = EnvVarInfo(
                    key = entry.key,
                    is_set = existing?.is_set,
                    redacted_value = existing?.redacted_value,
                    description = entry.description ?: existing?.description,
                    category = group.title,
                    url = entry.url ?: existing?.url,
                    advanced = entry.advanced ?: existing?.advanced,
                )
            }
        }
        // Preserve any live keys not in the catalog.
        live.forEach { (k, v) -> if (k !in out) out[k] = v }
        return out
    }

    @Serializable
    private data class EnvCatalogFile(val groups: List<EnvCatalogGroup> = emptyList())

    @Serializable
    private data class EnvCatalogGroup(
        val id: String? = null,
        val title: String = "General",
        val keys: List<EnvCatalogKey> = emptyList(),
    )

    @Serializable
    private data class EnvCatalogKey(
        val key: String,
        val description: String? = null,
        val url: String? = null,
        val advanced: Boolean? = null,
    )

    suspend fun setEnv(key: String, value: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api().putEnv(buildJsonObject {
                put("key", key)
                put("value", value)
            })
            invalidate("env")
            Unit
        }
    }

    suspend fun deleteEnv(key: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api().deleteEnv(buildJsonObject { put("key", key) })
            invalidate("env")
            Unit
        }
    }

    suspend fun getLogs(
        file: String,
        lines: Int,
        level: String? = null,
        component: String? = null,
        search: String? = null,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = api().getLogs(file = file, lines = lines, level = level, component = component).lines
            val q = search?.trim().orEmpty()
            if (q.isEmpty()) raw else raw.filter { it.contains(q, ignoreCase = true) }
        }
    }

    suspend fun getAnalytics(days: Int): Result<AnalyticsUsage> =
        cached("analytics:$days") { api().getAnalytics(days) }

    suspend fun getCron(): Result<List<CronJob>> = cached("cron") { api().getCronJobs() }

    suspend fun createCron(prompt: String, schedule: String, name: String?, deliver: String): Result<CronJob> =
        withContext(Dispatchers.IO) {
            runCatching {
                api().createCronJob(
                    buildJsonObject {
                        put("prompt", prompt)
                        put("schedule", schedule)
                        name?.let { put("name", it) }
                        put("deliver", deliver)
                    },
                ).also { invalidate("cron") }
            }
        }

    suspend fun pauseCron(id: String) = withContext(Dispatchers.IO) {
        runCatching { api().pauseCron(id) }.also { invalidate("cron") }
    }
    suspend fun resumeCron(id: String) = withContext(Dispatchers.IO) {
        runCatching { api().resumeCron(id) }.also { invalidate("cron") }
    }
    suspend fun triggerCron(id: String) = withContext(Dispatchers.IO) {
        runCatching { api().triggerCron(id) }.also { invalidate("cron") }
    }
    suspend fun deleteCron(id: String) = withContext(Dispatchers.IO) {
        runCatching { api().deleteCron(id); Unit }.also { invalidate("cron") }
    }

    suspend fun getSkills(): Result<List<SkillInfo>> = cached("skills") { api().getSkills() }

    suspend fun toggleSkill(name: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            api().toggleSkill(buildJsonObject {
                put("name", name)
                put("enabled", enabled)
            })
            invalidate("skills")
            Unit
        }
    }

    suspend fun searchSkillHub(query: String) = withContext(Dispatchers.IO) {
        runCatching { api().searchSkillHub(query.trim()).results }
    }

    suspend fun previewHubSkill(identifier: String) = withContext(Dispatchers.IO) {
        runCatching { api().previewHubSkill(identifier) }
    }

    suspend fun scanHubSkill(identifier: String) = withContext(Dispatchers.IO) {
        runCatching { api().scanHubSkill(identifier) }
    }

    suspend fun installHubSkill(identifier: String) = withContext(Dispatchers.IO) {
        runCatching {
            val action = awaitAction(api().installHubSkill(buildJsonObject { put("identifier", identifier) }))
            invalidate("skills", "learning_graph")
            action
        }
    }

    suspend fun uninstallHubSkill(name: String) = withContext(Dispatchers.IO) {
        runCatching {
            val action = awaitAction(api().uninstallHubSkill(buildJsonObject { put("name", name) }))
            invalidate("skills", "learning_graph")
            action
        }
    }

    suspend fun getMcp(): Result<List<McpServer>> = cached("mcp") { api().getMcpServers().servers }

    suspend fun startMcpOAuth(name: String) = withContext(Dispatchers.IO) {
        runCatching { api().startMcpOAuth(name) }
    }

    suspend fun getMcpOAuthFlow(id: String) = withContext(Dispatchers.IO) {
        runCatching { api().getMcpOAuthFlow(id) }
    }

    suspend fun getMcpCatalog(): Result<List<McpCatalogEntry>> =
        cached("mcp_catalog") { api().getMcpCatalog().entries }

    suspend fun installMcpCatalogEntry(
        name: String,
        env: Map<String, String>,
    ): Result<com.hermesgadget.talaria.domain.model.ActionStatus?> = withContext(Dispatchers.IO) {
        runCatching {
            val started = api().installMcpCatalogEntry(buildJsonObject {
                put("name", name)
                put("enable", true)
                put("env", buildJsonObject {
                    env.filterValues(String::isNotBlank).forEach { (key, value) -> put(key, value) }
                })
            })
            val response = started as? JsonObject
            val action = response?.get("action")?.jsonPrimitive?.contentOrNull
            val status = if (action == null) {
                null
            } else {
                var completed: com.hermesgadget.talaria.domain.model.ActionStatus? = null
                for (attempt in 0 until 300) {
                    val current = api().getActionStatus(action)
                    if (!current.running) {
                        completed = current
                        break
                    }
                    kotlinx.coroutines.delay(1_000)
                }
                completed ?: error("Hermes MCP install '$action' did not finish within five minutes")
            }
            check(status?.exit_code in listOf(null, 0)) {
                status?.lines?.lastOrNull() ?: "Hermes MCP install failed"
            }
            invalidate("mcp", "mcp_catalog")
            status
        }
    }

    suspend fun getChannels(): Result<List<MessagingPlatform>> =
        cached("channels") { api().getMessagingPlatforms().platforms }

    suspend fun getPairing(): Result<PairingResponse> = withContext(Dispatchers.IO) {
        runCatching { api().getPairing() }
    }

    suspend fun approvePairing(platform: String, requestId: String) = withContext(Dispatchers.IO) {
        runCatching {
            api().approvePairing(buildJsonObject {
                put("platform", platform)
                put("request_id", requestId)
                connectionStore.activeProfile()?.effectiveManagementProfile()?.let { put("profile", it) }
            })
        }
    }

    suspend fun revokePairing(platform: String, userId: String) = withContext(Dispatchers.IO) {
        runCatching {
            api().revokePairing(buildJsonObject {
                put("platform", platform)
                put("user_id", userId)
                connectionStore.activeProfile()?.effectiveManagementProfile()?.let { put("profile", it) }
            })
            Unit
        }
    }

    suspend fun getWebhooks(): Result<WebhooksResponse> = cached("webhooks") { api().getWebhooks() }

    /** Enables the webhook platform; may trigger a gateway restart on the host. */
    suspend fun enableWebhooks(): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().enableWebhooks() }.also { invalidate("webhooks") }
    }

    suspend fun getProfiles(force: Boolean = false): Result<List<ProfileInfo>> = withContext(Dispatchers.IO) {
        if (force) {
            runCatching { api().getProfiles().profiles }.onSuccess {
                cache.put("${connId()}:profiles", it)
            }
        } else {
            cached("profiles") { api().getProfiles().profiles }
        }
    }

    suspend fun getActiveProfileName(): Result<String?> = withContext(Dispatchers.IO) {
        runCatching { api().getActiveProfile().active }
    }

    suspend fun setActiveProfileName(name: String): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            api().setActiveProfile(buildJsonObject { put("name", name) }).active
        }.also { clearCache() }
    }

    suspend fun createProfile(name: String, description: String, cloneFrom: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                api().createProfile(buildJsonObject {
                    put("name", name.trim())
                    description.trim().takeIf(String::isNotEmpty)?.let { put("description", it) }
                    cloneFrom?.trim()?.takeIf(String::isNotEmpty)?.let { put("clone_from", it) }
                })
                Unit
            }.also { invalidate("profiles") }
        }

    suspend fun renameProfile(name: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api().renameProfile(name, buildJsonObject { put("new_name", newName.trim()) })
            Unit
        }.also { invalidate("profiles") }
    }

    suspend fun deleteProfile(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api().deleteProfile(name); Unit }.also { invalidate("profiles") }
    }

    suspend fun getProfileSoul(name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            api().getProfileSoul(name).jsonObject["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    suspend fun updateProfileSoul(name: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api().updateProfileSoul(name, buildJsonObject { put("content", content) })
            Unit
        }
    }

    suspend fun updateProfileDescription(name: String, description: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                api().updateProfileDescription(
                    name,
                    buildJsonObject { put("description", description.trim()) },
                )
                Unit
            }.also { invalidate("profiles") }
        }

    suspend fun describeProfileAutomatically(name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = api().describeProfileAuto(
                name,
                buildJsonObject { put("overwrite", true) },
            ).jsonObject
            check(root["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                root["reason"]?.jsonPrimitive?.contentOrNull ?: "Hermes could not generate a description"
            }
            root["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.also { invalidate("profiles") }
    }

    suspend fun renameSession(id: String, title: String) = withContext(Dispatchers.IO) {
        runCatching {
            api().patchSession(id, buildJsonObject { put("title", title) })
            Unit
        }
    }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        runCatching { api().deleteSession(id); Unit }
    }

    suspend fun searchSessions(query: String): Result<List<SessionSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val el = api().searchSessions(query)
            parseSessions(el)
        }
    }

    suspend fun pruneSessions(): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().pruneSessions(buildJsonObject {}) }
    }

    suspend fun getModelInfo(): Result<ModelInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val el = api().getModelInfo()
            runCatching { json.decodeFromJsonElement<ModelInfo>(el) }.getOrElse {
                ModelInfo(model = el.jsonObject["model"]?.toString()?.trim('"'))
            }
        }
    }

    suspend fun getModelOptions(): Result<List<ModelOption>> = withContext(Dispatchers.IO) {
        runCatching {
            val el = api().getModelOptions()
            when (el) {
                is JsonArray -> el.mapNotNull {
                    runCatching { json.decodeFromJsonElement<ModelOption>(it) }.getOrNull()
                        ?: ModelOption(id = it.jsonObject["id"]?.toString()?.trim('"'), name = it.toString())
                }
                is JsonObject -> {
                    val arr = el["options"]?.jsonArray ?: el["models"]?.jsonArray
                    arr?.mapNotNull { runCatching { json.decodeFromJsonElement<ModelOption>(it) }.getOrNull() }
                        ?: emptyList()
                }
                else -> emptyList()
            }
        }
    }

    data class ModelAssignmentResult(
        val confirmRequired: Boolean = false,
        val confirmMessage: String? = null,
    )

    suspend fun setModel(
        provider: String,
        modelId: String,
        confirmExpensive: Boolean = false,
    ): Result<ModelAssignmentResult> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api().setModel(buildJsonObject {
                put("scope", "main")
                put("provider", provider)
                put("model", modelId)
                put("confirm_expensive_model", confirmExpensive)
            })
            val obj = response as? JsonObject
            val confirmation = ModelAssignmentResult(
                confirmRequired = obj?.get("confirm_required")?.jsonPrimitive?.booleanOrNull == true,
                confirmMessage = obj?.get("confirm_message")?.jsonPrimitive?.contentOrNull,
            )
            if (!confirmation.confirmRequired) invalidate("model_providers")
            confirmation
        }
    }

    /** Provider-grouped model catalog for the Models screen (roadmap 15.12). */
    suspend fun getModelProviders(): Result<List<ModelProvider>> = cached("model_providers") {
        val el = api().getModelOptions()
        when (el) {
            is JsonObject -> el["providers"]?.jsonArray
                ?.mapNotNull { runCatching { json.decodeFromJsonElement<ModelProvider>(it) }.getOrNull() }
                ?: emptyList()
            else -> emptyList()
        }
    }

    suspend fun getToolsets(): Result<List<ToolsetInfo>> = cached("toolsets") {
        val el = api().getToolsets()
        val arr = when (el) {
            is JsonArray -> el
            is JsonObject -> el["toolsets"]?.jsonArray ?: el["items"]?.jsonArray
            else -> null
        }
        arr?.mapNotNull { runCatching { json.decodeFromJsonElement<ToolsetInfo>(it) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun setToolsetEnabled(name: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api().setToolset(name, buildJsonObject { put("enabled", enabled) })
            invalidate("toolsets")
            Unit
        }
    }

    suspend fun getConfigSchema(): Result<ConfigSchemaResponse> =
        cached("config_schema", STATIC_CACHE_TTL_MS) { api().getConfigSchema() }

    suspend fun getConfigDefaults(): Result<JsonElement> =
        cached("config_defaults", STATIC_CACHE_TTL_MS) { api().getConfigDefaults() }

    /**
     * Creates a webhook and returns the created route. The dashboard may echo
     * a one-time secret / final url in the response — surface it to the user.
     */
    suspend fun createWebhook(name: String, prompt: String): Result<WebhookRoute> = withContext(Dispatchers.IO) {
        runCatching {
            val element = api().createWebhook(
                buildJsonObject {
                    put("name", name)
                    put("prompt", prompt)
                },
            )
            val obj = element as? JsonObject ?: return@runCatching WebhookRoute(name)
            WebhookRoute(
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: name,
                description = obj["description"]?.jsonPrimitive?.contentOrNull,
                events = obj["events"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                deliver = obj["deliver"]?.jsonPrimitive?.contentOrNull,
                prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull,
                url = obj["url"]?.jsonPrimitive?.contentOrNull ?: obj["endpoint"]?.jsonPrimitive?.contentOrNull,
                enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull,
                secret = obj["secret"]?.jsonPrimitive?.contentOrNull,
            )
        }.also { invalidate("webhooks") }
    }

    suspend fun setWebhookEnabled(name: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            api().setWebhookEnabled(name, buildJsonObject { put("enabled", enabled) })
            invalidate("webhooks")
            Unit
        }
    }

    suspend fun deleteWebhook(name: String) = withContext(Dispatchers.IO) {
        runCatching { api().deleteWebhook(name); Unit }.also { invalidate("webhooks") }
    }

    suspend fun addMcpServer(
        name: String,
        command: String,
        url: String = "",
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        auth: String = "none",
        bearerToken: String = "",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api().addMcpServer(buildJsonObject {
                put("name", name)
                command.takeIf { it.isNotBlank() }?.let { put("command", it) }
                url.takeIf { it.isNotBlank() }?.let { put("url", it) }
                if (args.isNotEmpty()) put("args", JsonArray(args.map(::JsonPrimitive)))
                if (env.isNotEmpty()) put("env", buildJsonObject { env.forEach { (k, v) -> put(k, v) } })
                if (auth != "none") put("auth", auth)
                bearerToken.takeIf { it.isNotBlank() }?.let { put("bearer_token", it) }
            })
            invalidate("mcp")
            Unit
        }
    }

    suspend fun setMcpEnabled(name: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            api().setMcpEnabled(name, buildJsonObject { put("enabled", enabled) })
            invalidate("mcp")
            Unit
        }
    }

    suspend fun deleteMcpServer(name: String) = withContext(Dispatchers.IO) {
        runCatching { api().deleteMcpServer(name); Unit }.also { invalidate("mcp") }
    }

    suspend fun testMcp(name: String): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().testMcp(name) }
    }

    suspend fun updateChannel(
        id: String,
        enabled: Boolean?,
        env: JsonObject?,
        clearEnv: List<String> = emptyList(),
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                api().updateMessagingPlatform(
                    id,
                    buildJsonObject {
                        if (enabled != null) put("enabled", enabled)
                        if (env != null) put("env", env)
                        if (clearEnv.isNotEmpty()) {
                            put("clear_env", JsonArray(clearEnv.map(::JsonPrimitive)))
                        }
                    },
                )
                invalidate("channels")
                Unit
            }
        }

    suspend fun testChannel(id: String): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().testMessagingPlatform(id) }
    }

    suspend fun clearPendingPairing() = withContext(Dispatchers.IO) {
        runCatching { api().clearPendingPairing(); Unit }
    }

    suspend fun updateCron(id: String, prompt: String, schedule: String) = withContext(Dispatchers.IO) {
        runCatching {
            api().updateCronJob(
                id,
                buildJsonObject {
                    put("updates", buildJsonObject {
                        put("prompt", prompt)
                        put("schedule", schedule)
                    })
                },
            )
            invalidate("cron")
            Unit
        }
    }

    suspend fun runDoctor(): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().runDoctor() }
    }

    suspend fun runDoctorToCompletion() = withContext(Dispatchers.IO) {
        runCatching { awaitAction(api().runDoctor()) }
    }

    suspend fun runSecurityAudit(): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().runSecurityAudit() }
    }

    suspend fun runSecurityAuditToCompletion() = withContext(Dispatchers.IO) {
        runCatching { awaitAction(api().runSecurityAudit()) }
    }

    suspend fun runBackup(): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().runBackup() }
    }

    suspend fun runBackupToCompletion() = withContext(Dispatchers.IO) {
        runCatching { awaitAction(api().runBackup()) }
    }

    suspend fun checkUpdate(): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().checkUpdate() }
    }

    suspend fun getPortal(): Result<JsonElement> = cached("portal") { api().getPortal() }

    suspend fun getMemory(): Result<JsonElement> = cached("memory") { api().getMemory() }

    suspend fun getCurator(): Result<JsonElement> = cached("curator") { api().getCurator() }

    suspend fun getMemoryState(): Result<MemoryState> = cached("memory_state") {
        JsonConfig.json.decodeFromJsonElement(MemoryState.serializer(), api().getMemory())
    }

    suspend fun setMemoryProvider(name: String): Result<MemoryState> = withContext(Dispatchers.IO) {
        runCatching {
            api().setMemoryProvider(buildJsonObject { put("provider", name) })
            invalidate("memory", "memory_state")
            JsonConfig.json.decodeFromJsonElement(MemoryState.serializer(), api().getMemory())
        }
    }

    suspend fun resetMemory(target: String): Result<MemoryState> = withContext(Dispatchers.IO) {
        runCatching {
            require(target in setOf("all", "memory", "user")) { "Invalid memory reset target" }
            api().resetMemory(buildJsonObject { put("target", target) })
            invalidate("memory", "memory_state")
            JsonConfig.json.decodeFromJsonElement(MemoryState.serializer(), api().getMemory())
        }
    }

    suspend fun getCuratorState(): Result<CuratorState> = cached("curator_state") {
        JsonConfig.json.decodeFromJsonElement(CuratorState.serializer(), api().getCurator())
    }

    suspend fun setCuratorPaused(paused: Boolean): Result<CuratorState> = withContext(Dispatchers.IO) {
        runCatching {
            api().setCuratorPaused(buildJsonObject { put("paused", paused) })
            invalidate("curator", "curator_state")
            JsonConfig.json.decodeFromJsonElement(CuratorState.serializer(), api().getCurator())
        }
    }

    suspend fun runCuratorNow(): Result<com.hermesgadget.talaria.domain.model.ActionStatus> =
        withContext(Dispatchers.IO) {
            runCatching { awaitAction(api().runCurator()) }
        }

    /** Export session messages as markdown for share sheet. */
    suspend fun exportSessionMarkdown(sessionId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val msgs = api().getSessionMessages(sessionId).messages
            buildString {
                appendLine("# Session $sessionId")
                appendLine()
                msgs.forEach { m ->
                    appendLine("## ${m.role ?: "message"}")
                    appendLine(m.content.orEmpty())
                    if (m.tool_calls != null) {
                        appendLine()
                        appendLine("```json")
                        appendLine(m.tool_calls.toString())
                        appendLine("```")
                    }
                    appendLine()
                }
            }
        }
    }

    suspend fun getSystemStats(): Result<SystemStats> = cached("system") { api().getSystemStats() }

    // --- Files pane (Desktop parity 15.1) ---

    suspend fun fsDefaultCwd(): Result<FsCwd> = cached("fs_cwd") { api().fsDefaultCwd() }

    /** Directory listing, sorted dirs-first then name; cached briefly per path. */
    suspend fun fsList(path: String): Result<List<FsEntry>> = cached("fs_list:$path", ttlMs = 10_000L) {
        val response = api().fsList(path)
        check(response.error.isNullOrBlank()) { "Could not list $path: ${response.error}" }
        response.entries.sortedWith(
            compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() },
        )
    }

    suspend fun fsReadText(path: String): Result<FsTextFile> = withContext(Dispatchers.IO) {
        runCatching { api().fsReadText(path) }
    }

    /** Re-read before saving so a remote edit does not silently overwrite a newer file. */
    suspend fun fsWriteText(path: String, content: String, expectedOriginal: String): Result<FsTextFile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val current = api().fsReadText(path)
                check(!current.binary && !current.truncated) { "This file cannot be safely edited as text" }
                check(current.text == expectedOriginal) {
                    "The file changed on the Hermes host. Reopen it before saving."
                }
                api().fsWriteText(buildJsonObject {
                    put("path", path)
                    put("content", content)
                })
                invalidate("fs_list:${path.substringBeforeLast('/', "/")}")
                api().fsReadText(path)
            }
        }

    // --- Learning graph / Starmap (Desktop parity 15.4) ---

    suspend fun getLearningGraph(): Result<LearningGraph> = cached("learning_graph") { api().getLearningGraph() }

    suspend fun getLearningNode(id: String) = withContext(Dispatchers.IO) {
        runCatching { api().getLearningNode(id) }
    }

    suspend fun updateLearningNode(id: String, content: String): Result<LearningGraph> = withContext(Dispatchers.IO) {
        runCatching {
            api().updateLearningNode(buildJsonObject {
                put("id", id)
                put("content", content)
            })
            invalidate("learning_graph", "skills")
            api().getLearningGraph()
        }
    }

    suspend fun deleteLearningNode(id: String): Result<LearningGraph> = withContext(Dispatchers.IO) {
        runCatching {
            api().deleteLearningNode(buildJsonObject { put("id", id) })
            invalidate("learning_graph", "skills")
            api().getLearningGraph()
        }
    }

    private suspend fun awaitAction(
        started: JsonElement,
    ): com.hermesgadget.talaria.domain.model.ActionStatus {
        val name = (started as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            ?: error("Hermes did not return an action name")
        repeat(120) {
            val status = api().getActionStatus(name)
            if (!status.running) return status
            kotlinx.coroutines.delay(1_000)
        }
        error("Hermes action '$name' did not finish within two minutes")
    }

    suspend fun gateway(action: String) = withContext(Dispatchers.IO) {
        runCatching {
            val started = when (action) {
                "start" -> api().gatewayStart()
                "stop" -> api().gatewayStop()
                else -> api().gatewayRestart()
            }
            awaitAction(started)
        }.also { invalidate("system", "portal") }
    }

    suspend fun recordActivity(type: String, title: String, body: String) {
        val cid = connId()
        db.activity().insert(
            ActivityEventEntity(
                connectionId = cid,
                type = type,
                title = title,
                body = body,
            ),
        )
        db.activity().trim(cid, 500)
    }

    suspend fun pollForNotifications(): SyncSnapshot = withContext(Dispatchers.IO) {
        val status = api().getStatus()
        val pairing = runCatching { api().getPairing() }.getOrNull()
        val cron = runCatching { api().getCronJobs() }.getOrNull()
        SyncSnapshot(status, pairing, cron)
    }
}

data class SyncSnapshot(
    val status: StatusResponse,
    val pairing: PairingResponse?,
    val cron: List<CronJob>?,
)
