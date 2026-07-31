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


package com.nousresearch.talaria.core.data.repo

import com.nousresearch.talaria.core.data.db.ActivityEventEntity
import com.nousresearch.talaria.core.data.db.CachedMessageEntity
import com.nousresearch.talaria.core.data.db.CachedSessionEntity
import com.nousresearch.talaria.core.data.db.TalariaDatabase
import com.nousresearch.talaria.core.data.prefs.SecureConnectionStore
import com.nousresearch.talaria.core.network.HermesClientFactory
import com.nousresearch.talaria.core.network.JsonConfig
import com.nousresearch.talaria.domain.model.AnalyticsUsage
import com.nousresearch.talaria.domain.model.ConfigSchemaResponse
import com.nousresearch.talaria.domain.model.CronJob
import com.nousresearch.talaria.domain.model.EnvVarInfo
import com.nousresearch.talaria.domain.model.McpServer
import com.nousresearch.talaria.domain.model.MessagingPlatform
import com.nousresearch.talaria.domain.model.ModelInfo
import com.nousresearch.talaria.domain.model.ModelOption
import com.nousresearch.talaria.domain.model.PairingResponse
import com.nousresearch.talaria.domain.model.ProfileInfo
import com.nousresearch.talaria.domain.model.SessionMessage
import com.nousresearch.talaria.domain.model.SessionSummary
import com.nousresearch.talaria.domain.model.SkillInfo
import com.nousresearch.talaria.domain.model.StatusResponse
import com.nousresearch.talaria.domain.model.SystemStats
import com.nousresearch.talaria.domain.model.ToolsetInfo
import com.nousresearch.talaria.domain.model.WebhookRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class HermesRepository(
    private val clientFactory: HermesClientFactory,
    private val db: TalariaDatabase,
    private val connectionStore: SecureConnectionStore,
) {
    private val json = JsonConfig.json
    private fun connId() = connectionStore.activeProfile()?.id ?: "none"
    private fun api() = clientFactory.api()

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
    ): Result<com.nousresearch.talaria.domain.model.SessionsPage> = withContext(Dispatchers.IO) {
        runCatching { fetchSessionsPage(source = source, limit = limit, offset = offset) }
    }

    private suspend fun fetchSessionsPage(
        source: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): com.nousresearch.talaria.domain.model.SessionsPage {
        val element = api().getSessions(limit = limit, offset = offset, source = source)
        return parseSessionsPage(element)
    }

    private fun parseSessions(element: JsonElement): List<SessionSummary> =
        parseSessionsPage(element).sessions

    private fun parseSessionsPage(element: JsonElement): com.nousresearch.talaria.domain.model.SessionsPage =
        when (element) {
            is JsonArray -> com.nousresearch.talaria.domain.model.SessionsPage(
                sessions = element.map { json.decodeFromJsonElement(it) },
                total = element.size,
            )
            is JsonObject -> {
                val arr = element["sessions"]?.jsonArray
                val sessions = arr?.map { json.decodeFromJsonElement<SessionSummary>(it) } ?: emptyList()
                val total = element["total"]?.let {
                    runCatching { it.toString().trim('"').toInt() }.getOrNull()
                } ?: sessions.size
                com.nousresearch.talaria.domain.model.SessionsPage(sessions = sessions, total = total)
            }
            else -> com.nousresearch.talaria.domain.model.SessionsPage()
        }

    suspend fun loadMessages(sessionId: String): Result<List<SessionMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val msgs = api().getSessionMessages(sessionId).messages
            val cid = connId()
            db.messages().clearSession(sessionId)
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

    suspend fun getConfig(): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching { api().getConfig() }
    }

    suspend fun putConfig(config: JsonObject): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api().putConfig(buildJsonObject { put("config", config) })
            Unit
        }
    }

    suspend fun getEnv(): Result<Map<String, EnvVarInfo>> = withContext(Dispatchers.IO) {
        runCatching { api().getEnv() }
    }

    suspend fun setEnv(key: String, value: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api().putEnv(buildJsonObject {
                put("key", key)
                put("value", value)
            })
            Unit
        }
    }

    suspend fun deleteEnv(key: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api().deleteEnv(buildJsonObject { put("key", key) })
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

    suspend fun getAnalytics(days: Int): Result<AnalyticsUsage> = withContext(Dispatchers.IO) {
        runCatching { api().getAnalytics(days) }
    }

    suspend fun getCron(): Result<List<CronJob>> = withContext(Dispatchers.IO) {
        runCatching { api().getCronJobs() }
    }

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
                )
            }
        }

    suspend fun pauseCron(id: String) = withContext(Dispatchers.IO) { runCatching { api().pauseCron(id) } }
    suspend fun resumeCron(id: String) = withContext(Dispatchers.IO) { runCatching { api().resumeCron(id) } }
    suspend fun triggerCron(id: String) = withContext(Dispatchers.IO) { runCatching { api().triggerCron(id) } }
    suspend fun deleteCron(id: String) = withContext(Dispatchers.IO) { runCatching { api().deleteCron(id); Unit } }

    suspend fun getSkills(): Result<List<SkillInfo>> = withContext(Dispatchers.IO) {
        runCatching { api().getSkills() }
    }

    suspend fun toggleSkill(name: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            api().toggleSkill(buildJsonObject {
                put("name", name)
                put("enabled", enabled)
            })
            Unit
        }
    }

    suspend fun getMcp(): Result<List<McpServer>> = withContext(Dispatchers.IO) {
        runCatching { api().getMcpServers().servers }
    }

    suspend fun getChannels(): Result<List<MessagingPlatform>> = withContext(Dispatchers.IO) {
        runCatching { api().getMessagingPlatforms().platforms }
    }

    suspend fun getPairing(): Result<PairingResponse> = withContext(Dispatchers.IO) {
        runCatching { api().getPairing() }
    }

    suspend fun approvePairing(platform: String, code: String) = withContext(Dispatchers.IO) {
        runCatching {
            api().approvePairing(buildJsonObject {
                put("platform", platform)
                put("code", code)
            })
        }
    }

    suspend fun revokePairing(platform: String, userId: String) = withContext(Dispatchers.IO) {
        runCatching {
            api().revokePairing(buildJsonObject {
                put("platform", platform)
                put("user_id", userId)
            })
            Unit
        }
    }

    suspend fun getWebhooks(): Result<List<WebhookRoute>> = withContext(Dispatchers.IO) {
        runCatching { api().getWebhooks().subscriptions }
    }

    suspend fun getProfiles(): Result<List<ProfileInfo>> = withContext(Dispatchers.IO) {
        runCatching { api().getProfiles().profiles }
    }

    suspend fun getActiveProfileName(): Result<String?> = withContext(Dispatchers.IO) {
        runCatching { api().getActiveProfile().active }
    }

    suspend fun setActiveProfileName(name: String): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            api().setActiveProfile(buildJsonObject { put("active", name) }).active
        }
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

    suspend fun setModel(modelId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api().setModel(buildJsonObject { put("model", modelId) })
            Unit
        }
    }

    suspend fun getToolsets(): Result<List<ToolsetInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val el = api().getToolsets()
            val arr = when (el) {
                is JsonArray -> el
                is JsonObject -> el["toolsets"]?.jsonArray ?: el["items"]?.jsonArray
                else -> null
            }
            arr?.mapNotNull { runCatching { json.decodeFromJsonElement<ToolsetInfo>(it) }.getOrNull() }
                ?: emptyList()
        }
    }

    suspend fun getConfigSchema(): Result<ConfigSchemaResponse> = withContext(Dispatchers.IO) {
        runCatching { api().getConfigSchema() }
    }

    suspend fun getConfigDefaults(): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().getConfigDefaults() }
    }

    suspend fun createWebhook(name: String, prompt: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api().createWebhook(buildJsonObject {
                put("name", name)
                put("prompt", prompt)
            })
            Unit
        }
    }

    suspend fun setWebhookEnabled(name: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            api().setWebhookEnabled(name, buildJsonObject { put("enabled", enabled) })
            Unit
        }
    }

    suspend fun deleteWebhook(name: String) = withContext(Dispatchers.IO) {
        runCatching { api().deleteWebhook(name); Unit }
    }

    suspend fun addMcpServer(name: String, command: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api().addMcpServer(buildJsonObject {
                put("name", name)
                put("command", command)
            })
            Unit
        }
    }

    suspend fun setMcpEnabled(name: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            api().setMcpEnabled(name, buildJsonObject { put("enabled", enabled) })
            Unit
        }
    }

    suspend fun deleteMcpServer(name: String) = withContext(Dispatchers.IO) {
        runCatching { api().deleteMcpServer(name); Unit }
    }

    suspend fun testMcp(name: String): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().testMcp(name) }
    }

    suspend fun updateChannel(id: String, enabled: Boolean?, env: JsonObject?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                api().updateMessagingPlatform(
                    id,
                    buildJsonObject {
                        if (enabled != null) put("enabled", enabled)
                        if (env != null) put("env", env)
                    },
                )
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
                    put("prompt", prompt)
                    put("schedule", schedule)
                },
            )
            Unit
        }
    }

    suspend fun runDoctor(): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().runDoctor() }
    }

    suspend fun runSecurityAudit(): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().runSecurityAudit() }
    }

    suspend fun runBackup(): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().runBackup() }
    }

    suspend fun checkUpdate(): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().checkUpdate() }
    }

    suspend fun getPortal(): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().getPortal() }
    }

    suspend fun getMemory(): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().getMemory() }
    }

    suspend fun getCurator(): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api().getCurator() }
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

    suspend fun getSystemStats(): Result<SystemStats> = withContext(Dispatchers.IO) {
        runCatching { api().getSystemStats() }
    }

    suspend fun gateway(action: String) = withContext(Dispatchers.IO) {
        runCatching {
            when (action) {
                "start" -> api().gatewayStart()
                "stop" -> api().gatewayStop()
                else -> api().gatewayRestart()
            }
        }
    }

    suspend fun recordActivity(type: String, title: String, body: String) {
        db.activity().insert(
            ActivityEventEntity(
                connectionId = connId(),
                type = type,
                title = title,
                body = body,
            ),
        )
    }

    suspend fun pollForNotifications(): SyncSnapshot = withContext(Dispatchers.IO) {
        val status = api().getStatus()
        val pairing = runCatching { api().getPairing() }.getOrNull()
        val cron = runCatching { api().getCronJobs() }.getOrNull().orEmpty()
        SyncSnapshot(status, pairing, cron)
    }
}

data class SyncSnapshot(
    val status: StatusResponse,
    val pairing: PairingResponse?,
    val cron: List<CronJob>,
)
