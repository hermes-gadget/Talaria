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
import com.nousresearch.talaria.domain.model.CronJob
import com.nousresearch.talaria.domain.model.EnvVarInfo
import com.nousresearch.talaria.domain.model.McpServer
import com.nousresearch.talaria.domain.model.MessagingPlatform
import com.nousresearch.talaria.domain.model.PairingResponse
import com.nousresearch.talaria.domain.model.ProfileInfo
import com.nousresearch.talaria.domain.model.SessionMessage
import com.nousresearch.talaria.domain.model.SessionSummary
import com.nousresearch.talaria.domain.model.SkillInfo
import com.nousresearch.talaria.domain.model.StatusResponse
import com.nousresearch.talaria.domain.model.SystemStats
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

    suspend fun refreshSessions(): Result<List<SessionSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val element = api().getSessions()
            val list = parseSessions(element)
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

    private fun parseSessions(element: JsonElement): List<SessionSummary> = when (element) {
        is JsonArray -> element.map { json.decodeFromJsonElement(it) }
        is JsonObject -> {
            val arr = element["sessions"]?.jsonArray
            arr?.map { json.decodeFromJsonElement(it) } ?: emptyList()
        }
        else -> emptyList()
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

    suspend fun getLogs(file: String, lines: Int): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching { api().getLogs(file = file, lines = lines).lines }
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
