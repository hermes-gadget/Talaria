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
import com.hermesgadget.talaria.core.util.suspendResult
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.HermesApi
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
import com.hermesgadget.talaria.domain.model.OpsActionResponse
import com.hermesgadget.talaria.domain.model.OpsBackupRequest
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
import okhttp3.ResponseBody

class HermesRepository(
    private val clientFactory: HermesClientFactory,
    private val db: TalariaDatabase,
    private val connectionStore: SecureConnectionStore,
    private val appContext: Context? = null,
) {
    private data class BoundOperation(
        val snapshot: ConnectionSnapshot,
        val api: HermesApi,
    ) {
        val scopeId: String get() = snapshot.scopeId
    }

    private val json = JsonConfig.json
    private fun connId() = connectionStore.activeProfile()?.scopeId() ?: "none"

    /** Capture the destination, REST facade, and cache scope as one operation boundary. */
    private fun captureOperation(): BoundOperation {
        val snapshot = clientFactory.snapshot() ?: ConnectionSnapshot.anonymous()
        return BoundOperation(snapshot = snapshot, api = clientFactory.api(snapshot))
    }

    private suspend fun <T> withBoundOperation(
        block: suspend (BoundOperation) -> T,
    ): Result<T> {
        val operation = captureOperation()
        return withContext(Dispatchers.IO) {
            suspendResult { block(operation) }
        }
    }

    // Read-through cache so flipping between Manage menus is instant. Only the
    // "open once, browse" surfaces route through it; live pollers (Status) and
    // security-sensitive reads (pairing) fetch fresh every time.
    private val cache = ResponseCache(
        maxEntries = 64,
        maxWeight = 8L * 1024L * 1024L,
    )
    /** Last successful server fingerprint per connection/profile/session. */
    private val transcriptFingerprints = ResponseCache(
        maxEntries = 128,
        maxWeight = 256L * 1024L,
    )

    /** Drop all cached responses (call on profile / management-scope change). */
    fun clearCache() {
        cache.clear()
        transcriptFingerprints.clear()
    }

    private fun pruneTranscriptSession(snapshot: ConnectionSnapshot, sessionId: String) {
        transcriptFingerprints.invalidateWhere { key ->
            key.startsWith("${snapshot.connectionId}|") && key.endsWith("|$sessionId")
        }
    }

    private fun pruneTranscriptProfile(profileName: String) {
        transcriptFingerprints.invalidateWhere { key ->
            key.substringAfter('|', missingDelimiterValue = "")
                .substringBefore('|') == profileName
        }
    }

    private fun invalidate(snapshot: ConnectionSnapshot, vararg keys: String) {
        keys.forEach { cache.invalidate("${snapshot.scopeId}:$it") }
    }

    private suspend fun <T> cached(
        key: String,
        ttlMs: Long = DEFAULT_CACHE_TTL_MS,
        fetch: suspend (HermesApi) -> T,
    ): Result<T> {
        val snapshot = clientFactory.snapshot()
        val scope = snapshot?.scopeId ?: "none"
        return cache.readThrough("$scope:$key", ttlMs) {
            withContext(Dispatchers.IO) { fetch(clientFactory.api(snapshot)) }
        }
    }

    private companion object {
        const val DEFAULT_CACHE_TTL_MS = 20_000L
        // Schema/defaults are effectively static for a gateway lifetime.
        const val STATIC_CACHE_TTL_MS = 300_000L
        const val TRANSCRIPT_FINGERPRINT_TTL_MS = 5L * 60L * 1000L
    }

    fun observeSessions(): Flow<List<CachedSessionEntity>> = db.sessions().observeSessions(connId())
    fun observeActivity(): Flow<List<ActivityEventEntity>> = db.activity().observe(connId())

    suspend fun refreshStatus(): Result<StatusResponse> {
        val snapshot = clientFactory.snapshot()
        return withContext(Dispatchers.IO) {
            suspendResult { clientFactory.api(snapshot).getStatus(profile = snapshot?.managementProfile) }
        }
    }

    suspend fun refreshSessions(
        source: String? = null,
        limit: Int = 50,
    ): Result<List<SessionSummary>> {
        val operation = captureOperation()
        val snapshot = operation.snapshot
        return withContext(Dispatchers.IO) {
            suspendResult {
            val (list, complete) = fetchSessionsForReconciliation(
                operation = operation,
                source = source,
                limit = limit,
            )
            val cid = snapshot.scopeId
            val rows = list.map {
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
            }
            val cached = db.sessions().getAll(cid)
            val cachedById = cached.associateBy { it.id }
            val deleteIds = if (source == null && complete) {
                SessionReconciliation.staleSessionIds(
                    cachedIds = cachedById.keys,
                    serverIds = rows.map { it.id }.toSet(),
                ).toList()
            } else {
                emptyList()
            }
            val changedRows = SessionReconciliation.changedRows(cachedById, rows)
            // No Room call at all when the server page is semantically equal.
            if (deleteIds.isNotEmpty() || changedRows.isNotEmpty()) {
                db.sessions().reconcile(cid, deleteIds, changedRows)
            }
            deleteIds.forEach { pruneTranscriptSession(snapshot, it) }
            list
            }
        }
    }

    private suspend fun fetchSessionsForReconciliation(
        operation: BoundOperation,
        source: String?,
        limit: Int,
    ): Pair<List<SessionSummary>, Boolean> {
        val first = fetchSessionsPage(operation, source = source, limit = limit, offset = 0)
        // A filtered list is never authoritative for the entire session cache.
        if (source != null) return first.sessions to false

        val pageLimit = limit.coerceAtLeast(1)
        val total = first.total
        if (total != null && total <= first.sessions.distinctBy { it.id }.size) {
            return first.sessions.distinctBy { it.id } to true
        }

        val all = first.sessions.toMutableList()
        var offset = all.size
        var complete = total == null && first.sessions.size < pageLimit
        while (!complete && (total == null || offset < total)) {
            val requestLimit = if (total == null) {
                pageLimit
            } else {
                minOf(pageLimit, total - offset)
            }
            val page = fetchSessionsPage(
                operation = operation,
                source = source,
                limit = requestLimit,
                offset = offset,
            )
            if (page.sessions.isEmpty()) {
                complete = true
                break
            }
            val previousIds = all.asSequence().map { it.id }.toSet()
            all += page.sessions
            offset += page.sessions.size
            // If an endpoint ignores offset and repeats a full page, do not
            // claim completeness and accidentally prune valid cached rows.
            val madeProgress = page.sessions.any { it.id !in previousIds }
            complete = page.sessions.size < requestLimit ||
                (total != null && all.distinctBy { it.id }.size >= total)
            if (!madeProgress) {
                complete = false
                break
            }
        }
        return all.distinctBy { it.id } to complete
    }

    suspend fun getSessionsPage(
        source: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Result<com.hermesgadget.talaria.domain.model.SessionsPage> {
        val operation = captureOperation()
        return withContext(Dispatchers.IO) {
            suspendResult { fetchSessionsPage(operation, source = source, limit = limit, offset = offset) }
        }
    }

    private suspend fun fetchSessionsPage(
        operation: BoundOperation,
        source: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): com.hermesgadget.talaria.domain.model.SessionsPage {
        val element = operation.api.getSessions(
            limit = limit,
            offset = offset,
            source = source,
            profile = operation.snapshot.managementProfile,
        )
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
                }
                com.hermesgadget.talaria.domain.model.SessionsPage(sessions = sessions, total = total)
            }
            else -> com.hermesgadget.talaria.domain.model.SessionsPage()
        }

    /** Single-session summary (model, tokens, live flag) for the detail header. */
    suspend fun getSession(sessionId: String): Result<SessionSummary> {
        val snapshot = clientFactory.snapshot()
        return withContext(Dispatchers.IO) {
            suspendResult {
                clientFactory.api(snapshot).getSession(sessionId, profile = snapshot?.managementProfile)
            }
        }
    }

    suspend fun loadMessages(sessionId: String): Result<List<SessionMessage>> {
        return loadMessagesSnapshot(sessionId).map { it.messages }
    }

    /**
     * Load one authoritative transcript. A matching fingerprint returns the
     * payload to callers but skips both entity mapping and replaceSessionMessages.
     * A failed request never updates the fingerprint or Room, preserving the
     * last-good cache for the next read.
     */
    suspend fun loadMessagesSnapshot(
        sessionId: String,
        profileName: String? = null,
    ): Result<TranscriptSnapshot> {
        val operation = captureOperation()
        val snapshot = operation.snapshot
        return withContext(Dispatchers.IO) {
            suspendResult {
                val profile = profileName ?: snapshot.managementProfile
                val response = operation.api
                    .getSessionMessages(sessionId, profile = profile)
                val fingerprint = TranscriptFingerprintFactory.from(response)
                val key = "${snapshot.connectionId}|$profile|$sessionId"
                val previous = transcriptFingerprints.peek(
                    key,
                    TRANSCRIPT_FINGERPRINT_TTL_MS,
                ) as? TranscriptFingerprint
                val contentChanged = TranscriptFingerprintFactory.contentChanged(previous, fingerprint)

                if (contentChanged && profile == snapshot.managementProfile) {
                    val cid = snapshot.scopeId
                    val rows = response.messages.mapIndexed { index, message ->
                        CachedMessageEntity(
                            key = "$sessionId-$index",
                            sessionId = sessionId,
                            connectionId = cid,
                            role = message.role,
                            content = message.content,
                            timestamp = message.timestamp,
                            ordinal = index,
                        )
                    }
                    val existing = db.messages().getSessionMessages(cid, sessionId)
                    if (existing != rows) {
                        // The DAO transaction makes the replacement atomic; a
                        // failure leaves the previous transcript observable.
                        db.messages().replaceSessionMessages(cid, sessionId, rows)
                    }
                }
                transcriptFingerprints.put(key, fingerprint, TRANSCRIPT_FINGERPRINT_TTL_MS)
                TranscriptSnapshot(
                    messages = response.messages,
                    fingerprint = fingerprint,
                    contentChanged = contentChanged,
                )
            }
        }
    }

    suspend fun getConfig(): Result<JsonObject> = cached("config") { api -> api.getConfig() }

    suspend fun putConfig(config: JsonObject): Result<Unit> = withBoundOperation { operation ->
        operation.api.putConfig(buildJsonObject { put("config", config) })
        invalidate(operation.snapshot, "config")
        Unit
    }

    suspend fun getEnv(): Result<Map<String, EnvVarInfo>> = cached("env") {
        api -> mergeEnvCatalog(api.getEnv())
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

    suspend fun setEnv(key: String, value: String): Result<Unit> = withBoundOperation { operation ->
        operation.api.putEnv(buildJsonObject {
            put("key", key)
            put("value", value)
        })
        invalidate(operation.snapshot, "env")
        Unit
    }

    suspend fun deleteEnv(key: String): Result<Unit> = withBoundOperation { operation ->
        operation.api.deleteEnv(buildJsonObject { put("key", key) })
        invalidate(operation.snapshot, "env")
        Unit
    }

    suspend fun getLogs(
        file: String,
        lines: Int,
        level: String? = null,
        component: String? = null,
        search: String? = null,
    ): Result<List<String>> = withBoundOperation { operation ->
        val raw = operation.api.getLogs(file = file, lines = lines, level = level, component = component).lines
        val q = search?.trim().orEmpty()
        if (q.isEmpty()) raw else raw.filter { it.contains(q, ignoreCase = true) }
    }

    suspend fun getAnalytics(days: Int): Result<AnalyticsUsage> =
        cached("analytics:$days") { api -> api.getAnalytics(days) }

    suspend fun getCron(): Result<List<CronJob>> = cached("cron") { api -> api.getCronJobs() }

    suspend fun createCron(prompt: String, schedule: String, name: String?, deliver: String): Result<CronJob> =
        withBoundOperation { operation ->
            operation.api.createCronJob(
                buildJsonObject {
                    put("prompt", prompt)
                    put("schedule", schedule)
                    name?.let { put("name", it) }
                    put("deliver", deliver)
                },
            ).also { invalidate(operation.snapshot, "cron") }
        }

    suspend fun pauseCron(id: String) = withBoundOperation { operation ->
        operation.api.pauseCron(id).also { invalidate(operation.snapshot, "cron") }
    }
    suspend fun resumeCron(id: String) = withBoundOperation { operation ->
        operation.api.resumeCron(id).also { invalidate(operation.snapshot, "cron") }
    }
    suspend fun triggerCron(id: String) = withBoundOperation { operation ->
        operation.api.triggerCron(id).also { invalidate(operation.snapshot, "cron") }
    }
    suspend fun deleteCron(id: String) = withBoundOperation { operation ->
        operation.api.deleteCron(id)
        invalidate(operation.snapshot, "cron")
        Unit
    }

    suspend fun getSkills(): Result<List<SkillInfo>> = cached("skills") { api -> api.getSkills() }

    suspend fun toggleSkill(name: String, enabled: Boolean) = withBoundOperation { operation ->
        operation.api.toggleSkill(buildJsonObject {
            put("name", name)
            put("enabled", enabled)
        })
        invalidate(operation.snapshot, "skills")
        Unit
    }

    suspend fun searchSkillHub(query: String) = withBoundOperation { operation ->
        operation.api.searchSkillHub(query.trim()).results
    }

    suspend fun previewHubSkill(identifier: String) = withBoundOperation { operation ->
        operation.api.previewHubSkill(identifier)
    }

    suspend fun scanHubSkill(identifier: String) = withBoundOperation { operation ->
        operation.api.scanHubSkill(identifier)
    }

    suspend fun installHubSkill(identifier: String) = withBoundOperation { operation ->
        val action = awaitAction(
            operation.api.installHubSkill(buildJsonObject { put("identifier", identifier) }),
            operation.api,
        )
        invalidate(operation.snapshot, "skills", "learning_graph")
        action
    }

    suspend fun uninstallHubSkill(name: String) = withBoundOperation { operation ->
        val action = awaitAction(
            operation.api.uninstallHubSkill(buildJsonObject { put("name", name) }),
            operation.api,
        )
        invalidate(operation.snapshot, "skills", "learning_graph")
        action
    }

    suspend fun getMcp(): Result<List<McpServer>> = cached("mcp") { api -> api.getMcpServers().servers }

    suspend fun startMcpOAuth(name: String) = withBoundOperation { operation ->
        operation.api.startMcpOAuth(name)
    }

    suspend fun getMcpOAuthFlow(id: String) = withBoundOperation { operation ->
        operation.api.getMcpOAuthFlow(id)
    }

    suspend fun getMcpCatalog(): Result<List<McpCatalogEntry>> =
        cached("mcp_catalog") { api -> api.getMcpCatalog().entries }

    suspend fun installMcpCatalogEntry(
        name: String,
        env: Map<String, String>,
    ): Result<com.hermesgadget.talaria.domain.model.ActionStatus?> = withBoundOperation { operation ->
            val started = operation.api.installMcpCatalogEntry(buildJsonObject {
                put("name", name)
                put("enable", true)
                put("env", buildJsonObject {
                    env.filterValues(String::isNotBlank).forEach { (key, value) -> put(key, value) }
                })
            }, profile = operation.snapshot.managementProfile)
            val response = started as? JsonObject
            val action = response?.get("action")?.jsonPrimitive?.contentOrNull
            val status = if (action == null) {
                null
            } else {
                var completed: com.hermesgadget.talaria.domain.model.ActionStatus? = null
                for (attempt in 0 until 300) {
                    val current = operation.api.getActionStatus(action)
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
            invalidate(operation.snapshot, "mcp", "mcp_catalog")
            status
    }

    suspend fun getChannels(): Result<List<MessagingPlatform>> =
        cached("channels") { api -> api.getMessagingPlatforms().platforms }

    suspend fun getPairing(): Result<PairingResponse> = withBoundOperation { operation ->
        operation.api.getPairing(profile = operation.snapshot.managementProfile)
    }

    suspend fun approvePairing(
        platform: String,
        requestId: String,
        snapshot: ConnectionSnapshot? = null,
    ): Result<JsonElement> {
        val bound = snapshot ?: clientFactory.snapshot()
        return withContext(Dispatchers.IO) {
            suspendResult {
                clientFactory.api(bound).approvePairing(buildJsonObject {
                    put("platform", platform)
                    put("request_id", requestId)
                    bound?.managementProfile?.let { put("profile", it) }
                }, profile = bound?.managementProfile)
            }
        }
    }

    suspend fun revokePairing(platform: String, userId: String) = withBoundOperation { operation ->
        operation.api.revokePairing(
            buildJsonObject {
                put("platform", platform)
                put("user_id", userId)
            },
            profile = operation.snapshot.managementProfile,
        )
        Unit
    }

    suspend fun getWebhooks(): Result<WebhooksResponse> = cached("webhooks") { api -> api.getWebhooks() }

    /** Enables the webhook platform; may trigger a gateway restart on the host. */
    suspend fun enableWebhooks(): Result<JsonElement> = withBoundOperation { operation ->
        operation.api.enableWebhooks().also { invalidate(operation.snapshot, "webhooks") }
    }

    suspend fun getProfiles(force: Boolean = false): Result<List<ProfileInfo>> {
        if (!force) return cached("profiles") { api -> api.getProfiles().profiles }
        val operation = captureOperation()
        val scope = operation.scopeId
        return withContext(Dispatchers.IO) {
            suspendResult { operation.api.getProfiles().profiles }.onSuccess {
                cache.put("$scope:profiles", it)
            }
        }
    }

    suspend fun getActiveProfileName(): Result<String?> = withBoundOperation { operation ->
        operation.api.getActiveProfile().active
    }

    suspend fun setActiveProfileName(name: String): Result<String?> = withBoundOperation { operation ->
        operation.api.setActiveProfile(buildJsonObject { put("name", name) }).active
            .also { clearCache() }
    }

    suspend fun createProfile(name: String, description: String, cloneFrom: String? = null): Result<Unit> =
        withBoundOperation { operation ->
            operation.api.createProfile(buildJsonObject {
                put("name", name.trim())
                description.trim().takeIf(String::isNotEmpty)?.let { put("description", it) }
                cloneFrom?.trim()?.takeIf(String::isNotEmpty)?.let { put("clone_from", it) }
            })
            invalidate(operation.snapshot, "profiles")
            Unit
        }

    suspend fun renameProfile(name: String, newName: String): Result<Unit> = withBoundOperation { operation ->
        operation.api.renameProfile(name, buildJsonObject { put("new_name", newName.trim()) })
        invalidate(operation.snapshot, "profiles")
        pruneTranscriptProfile(name)
        Unit
    }

    suspend fun deleteProfile(name: String): Result<Unit> = withBoundOperation { operation ->
        operation.api.deleteProfile(name)
        invalidate(operation.snapshot, "profiles")
        pruneTranscriptProfile(name)
        Unit
    }

    suspend fun getProfileSoul(name: String): Result<String> = withBoundOperation { operation ->
        operation.api.getProfileSoul(name).jsonObject["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    suspend fun updateProfileSoul(name: String, content: String): Result<Unit> = withBoundOperation { operation ->
        operation.api.updateProfileSoul(name, buildJsonObject { put("content", content) })
        Unit
    }

    suspend fun updateProfileDescription(name: String, description: String): Result<Unit> =
        withBoundOperation { operation ->
            operation.api.updateProfileDescription(
                name,
                buildJsonObject { put("description", description.trim()) },
            )
            invalidate(operation.snapshot, "profiles")
            Unit
        }

    suspend fun describeProfileAutomatically(name: String): Result<String> = withBoundOperation { operation ->
        val root = operation.api.describeProfileAuto(
            name,
            buildJsonObject { put("overwrite", true) },
        ).jsonObject
        check(root["ok"]?.jsonPrimitive?.booleanOrNull == true) {
            root["reason"]?.jsonPrimitive?.contentOrNull ?: "Hermes could not generate a description"
        }
        root["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            .also { invalidate(operation.snapshot, "profiles") }
    }

    suspend fun renameSession(id: String, title: String) = withBoundOperation { operation ->
        operation.api.patchSession(id, buildJsonObject { put("title", title) })
        Unit
    }

    suspend fun deleteSession(id: String) = withBoundOperation { operation ->
        operation.api.deleteSession(id)
        Unit
    }

    suspend fun searchSessions(query: String): Result<List<SessionSummary>> = withBoundOperation { operation ->
        parseSessions(operation.api.searchSessions(query))
    }

    suspend fun pruneSessions(): Result<JsonElement> = withBoundOperation { operation ->
        operation.api.pruneSessions(buildJsonObject {})
    }

    suspend fun getModelInfo(): Result<ModelInfo> = withBoundOperation { operation ->
        val el = operation.api.getModelInfo()
        runCatching { json.decodeFromJsonElement<ModelInfo>(el) }.getOrElse {
            ModelInfo(model = el.jsonObject["model"]?.toString()?.trim('"'))
        }
    }

    suspend fun getModelOptions(): Result<List<ModelOption>> = withBoundOperation { operation ->
        val el = operation.api.getModelOptions()
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

    data class ModelAssignmentResult(
        val confirmRequired: Boolean = false,
        val confirmMessage: String? = null,
    )

    suspend fun setModel(
        provider: String,
        modelId: String,
        confirmExpensive: Boolean = false,
    ): Result<ModelAssignmentResult> = withBoundOperation { operation ->
            val response = operation.api.setModel(buildJsonObject {
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
            if (!confirmation.confirmRequired) invalidate(operation.snapshot, "model_providers")
            confirmation
    }

    /** Provider-grouped model catalog for the Models screen (roadmap 15.12). */
    suspend fun getModelProviders(): Result<List<ModelProvider>> = cached("model_providers") {
        api ->
        val el = api.getModelOptions()
        when (el) {
            is JsonObject -> el["providers"]?.jsonArray
                ?.mapNotNull { suspendResult { json.decodeFromJsonElement<ModelProvider>(it) }.getOrNull() }
                ?: emptyList()
            else -> emptyList()
        }
    }

    suspend fun getToolsets(): Result<List<ToolsetInfo>> = cached("toolsets") {
        api ->
        val el = api.getToolsets()
        val arr = when (el) {
            is JsonArray -> el
            is JsonObject -> el["toolsets"]?.jsonArray ?: el["items"]?.jsonArray
            else -> null
        }
        arr?.mapNotNull { suspendResult { json.decodeFromJsonElement<ToolsetInfo>(it) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun setToolsetEnabled(name: String, enabled: Boolean): Result<Unit> = withBoundOperation { operation ->
        operation.api.setToolset(name, buildJsonObject { put("enabled", enabled) })
        invalidate(operation.snapshot, "toolsets")
        Unit
    }

    suspend fun getConfigSchema(): Result<ConfigSchemaResponse> =
        cached("config_schema", STATIC_CACHE_TTL_MS) { api -> api.getConfigSchema() }

    suspend fun getConfigDefaults(): Result<JsonElement> =
        cached("config_defaults", STATIC_CACHE_TTL_MS) { api -> api.getConfigDefaults() }

    /**
     * Creates a webhook and returns the created route. The dashboard may echo
     * a one-time secret / final url in the response — surface it to the user.
     */
    suspend fun createWebhook(name: String, prompt: String): Result<WebhookRoute> = withBoundOperation { operation ->
        val element = operation.api.createWebhook(
            buildJsonObject {
                put("name", name)
                put("prompt", prompt)
            },
        )
        val obj = element as? JsonObject
        if (obj == null) {
            invalidate(operation.snapshot, "webhooks")
            return@withBoundOperation WebhookRoute(name)
        }
        val result = WebhookRoute(
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: name,
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
            events = obj["events"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            deliver = obj["deliver"]?.jsonPrimitive?.contentOrNull,
            prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull,
            url = obj["url"]?.jsonPrimitive?.contentOrNull ?: obj["endpoint"]?.jsonPrimitive?.contentOrNull,
            enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull,
            secret = obj["secret"]?.jsonPrimitive?.contentOrNull,
        )
        invalidate(operation.snapshot, "webhooks")
        result
    }

    suspend fun setWebhookEnabled(name: String, enabled: Boolean) = withBoundOperation { operation ->
        operation.api.setWebhookEnabled(name, buildJsonObject { put("enabled", enabled) })
        invalidate(operation.snapshot, "webhooks")
        Unit
    }

    suspend fun deleteWebhook(name: String) = withBoundOperation { operation ->
        operation.api.deleteWebhook(name)
        invalidate(operation.snapshot, "webhooks")
        Unit
    }

    suspend fun addMcpServer(
        name: String,
        command: String,
        url: String = "",
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        auth: String = "none",
        bearerToken: String = "",
    ): Result<Unit> = withBoundOperation { operation ->
            operation.api.addMcpServer(buildJsonObject {
                put("name", name)
                command.takeIf { it.isNotBlank() }?.let { put("command", it) }
                url.takeIf { it.isNotBlank() }?.let { put("url", it) }
                if (args.isNotEmpty()) put("args", JsonArray(args.map(::JsonPrimitive)))
                if (env.isNotEmpty()) put("env", buildJsonObject { env.forEach { (k, v) -> put(k, v) } })
                if (auth != "none") put("auth", auth)
                bearerToken.takeIf { it.isNotBlank() }?.let { put("bearer_token", it) }
            })
            invalidate(operation.snapshot, "mcp")
            Unit
    }

    suspend fun setMcpEnabled(name: String, enabled: Boolean) = withBoundOperation { operation ->
        operation.api.setMcpEnabled(name, buildJsonObject { put("enabled", enabled) })
        invalidate(operation.snapshot, "mcp")
        Unit
    }

    suspend fun deleteMcpServer(name: String) = withBoundOperation { operation ->
        operation.api.deleteMcpServer(name)
        invalidate(operation.snapshot, "mcp")
        Unit
    }

    suspend fun testMcp(name: String): Result<JsonElement> = withBoundOperation { operation ->
        operation.api.testMcp(name)
    }

    suspend fun updateChannel(
        id: String,
        enabled: Boolean?,
        env: JsonObject?,
        clearEnv: List<String> = emptyList(),
    ): Result<Unit> = withBoundOperation { operation ->
                operation.api.updateMessagingPlatform(
                    id,
                    buildJsonObject {
                        if (enabled != null) put("enabled", enabled)
                        if (env != null) put("env", env)
                        if (clearEnv.isNotEmpty()) {
                            put("clear_env", JsonArray(clearEnv.map(::JsonPrimitive)))
                        }
                    },
                )
                invalidate(operation.snapshot, "channels")
                Unit
    }

    suspend fun testChannel(id: String): Result<JsonElement> = withBoundOperation { operation ->
        operation.api.testMessagingPlatform(id)
    }

    suspend fun clearPendingPairing() = withBoundOperation { operation ->
        operation.api.clearPendingPairing()
        Unit
    }

    suspend fun updateCron(id: String, prompt: String, schedule: String) = withBoundOperation { operation ->
            operation.api.updateCronJob(
                id,
                buildJsonObject {
                    put("updates", buildJsonObject {
                        put("prompt", prompt)
                        put("schedule", schedule)
                    })
                },
            )
            invalidate(operation.snapshot, "cron")
            Unit
    }

    suspend fun runDoctor(): Result<JsonElement> = withBoundOperation { operation ->
        operation.api.runDoctor()
    }

    suspend fun runDoctorToCompletion() = withBoundOperation { operation ->
        awaitAction(operation.api.runDoctor(), operation.api)
    }

    suspend fun runSecurityAudit(): Result<JsonElement> = withBoundOperation { operation ->
        operation.api.runSecurityAudit()
    }

    suspend fun runSecurityAuditToCompletion() = withBoundOperation { operation ->
        awaitAction(operation.api.runSecurityAudit(), operation.api)
    }

    suspend fun runBackup(): Result<JsonElement> = withBoundOperation { operation ->
        operation.api.runBackup()
    }

    suspend fun runBackupToCompletion() = withBoundOperation { operation ->
        awaitAction(operation.api.runBackup(), operation.api)
    }

    /** Keep the ops backup request on the same snapshot-bound repository path. */
    suspend fun createOpsBackup(request: OpsBackupRequest = OpsBackupRequest()): OpsActionResponse =
        withBoundOperation { operation -> operation.api.createOpsBackup(request) }.getOrThrow()

    /** Return the streaming body from the same snapshot-bound API facade. */
    suspend fun downloadOpsBackup(archive: String): ResponseBody =
        withBoundOperation { operation -> operation.api.downloadOpsBackup(archive) }.getOrThrow()

    /** Create and download a backup through one captured transport scope. */
    suspend fun createAndDownloadOpsBackup(): Pair<String, ResponseBody> =
        withBoundOperation { operation ->
            val response = operation.api.createOpsBackup(OpsBackupRequest())
            val archive = response.archive?.takeIf { it.isNotBlank() }
                ?: error(response.error ?: "The backup endpoint did not return an archive")
            archive to operation.api.downloadOpsBackup(archive)
        }.getOrThrow()

    suspend fun checkUpdate(): Result<JsonElement> = withBoundOperation { operation ->
        operation.api.checkUpdate()
    }

    suspend fun getPortal(): Result<JsonElement> = cached("portal") { api -> api.getPortal() }

    suspend fun getMemory(): Result<JsonElement> = cached("memory") { api -> api.getMemory() }

    suspend fun getCurator(): Result<JsonElement> = cached("curator") { api -> api.getCurator() }

    suspend fun getMemoryState(): Result<MemoryState> = cached("memory_state") {
        api -> JsonConfig.json.decodeFromJsonElement(MemoryState.serializer(), api.getMemory())
    }

    suspend fun setMemoryProvider(name: String): Result<MemoryState> = withBoundOperation { operation ->
        operation.api.setMemoryProvider(buildJsonObject { put("provider", name) })
        invalidate(operation.snapshot, "memory", "memory_state")
        JsonConfig.json.decodeFromJsonElement(MemoryState.serializer(), operation.api.getMemory())
    }

    suspend fun resetMemory(target: String): Result<MemoryState> = withBoundOperation { operation ->
        require(target in setOf("all", "memory", "user")) { "Invalid memory reset target" }
        operation.api.resetMemory(buildJsonObject { put("target", target) })
        invalidate(operation.snapshot, "memory", "memory_state")
        JsonConfig.json.decodeFromJsonElement(MemoryState.serializer(), operation.api.getMemory())
    }

    suspend fun getCuratorState(): Result<CuratorState> = cached("curator_state") {
        api -> JsonConfig.json.decodeFromJsonElement(CuratorState.serializer(), api.getCurator())
    }

    suspend fun setCuratorPaused(paused: Boolean): Result<CuratorState> = withBoundOperation { operation ->
        operation.api.setCuratorPaused(buildJsonObject { put("paused", paused) })
        invalidate(operation.snapshot, "curator", "curator_state")
        JsonConfig.json.decodeFromJsonElement(CuratorState.serializer(), operation.api.getCurator())
    }

    suspend fun runCuratorNow(): Result<com.hermesgadget.talaria.domain.model.ActionStatus> =
        withBoundOperation { operation ->
            awaitAction(operation.api.runCurator(), operation.api)
        }

    /** Export session messages as markdown for share sheet. */
    suspend fun exportSessionMarkdown(sessionId: String): Result<String> = withBoundOperation { operation ->
            val msgs = operation.api.getSessionMessages(
                sessionId,
                profile = operation.snapshot.managementProfile,
            ).messages
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

    suspend fun getSystemStats(): Result<SystemStats> = cached("system") { api -> api.getSystemStats() }

    // --- Files pane (Desktop parity 15.1) ---

    suspend fun fsDefaultCwd(): Result<FsCwd> = cached("fs_cwd") { api -> api.fsDefaultCwd() }

    /** Directory listing, sorted dirs-first then name; cached briefly per path. */
    suspend fun fsList(path: String): Result<List<FsEntry>> = cached("fs_list:$path", ttlMs = 10_000L) {
        api ->
        val response = api.fsList(path)
        check(response.error.isNullOrBlank()) { "Could not list $path: ${response.error}" }
        response.entries.sortedWith(
            compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() },
        )
    }

    suspend fun fsReadText(path: String): Result<FsTextFile> = withBoundOperation { operation ->
        operation.api.fsReadText(path, profile = operation.snapshot.managementProfile)
    }

    /** Re-read before saving so a remote edit does not silently overwrite a newer file. */
    suspend fun fsWriteText(path: String, content: String, expectedOriginal: String): Result<FsTextFile> =
        withBoundOperation { operation ->
                val current = operation.api.fsReadText(
                    path,
                    profile = operation.snapshot.managementProfile,
                )
                check(!current.binary && !current.truncated) { "This file cannot be safely edited as text" }
                check(current.text == expectedOriginal) {
                    "The file changed on the Hermes host. Reopen it before saving."
                }
                operation.api.fsWriteText(buildJsonObject {
                    put("path", path)
                    put("content", content)
                }, profile = operation.snapshot.managementProfile)
                invalidate(operation.snapshot, "fs_list:${path.substringBeforeLast('/', "/")}")
                operation.api.fsReadText(path, profile = operation.snapshot.managementProfile)
        }

    // --- Learning graph / Starmap (Desktop parity 15.4) ---

    suspend fun getLearningGraph(): Result<LearningGraph> = cached("learning_graph") { api -> api.getLearningGraph() }

    suspend fun getLearningNode(id: String) = withBoundOperation { operation ->
        operation.api.getLearningNode(id, profile = operation.snapshot.managementProfile)
    }

    suspend fun updateLearningNode(id: String, content: String): Result<LearningGraph> =
        withBoundOperation { operation ->
            operation.api.updateLearningNode(buildJsonObject {
                put("id", id)
                put("content", content)
            }, profile = operation.snapshot.managementProfile)
            invalidate(operation.snapshot, "learning_graph", "skills")
            operation.api.getLearningGraph(profile = operation.snapshot.managementProfile)
        }

    suspend fun deleteLearningNode(id: String): Result<LearningGraph> =
        withBoundOperation { operation ->
            operation.api.deleteLearningNode(
                buildJsonObject { put("id", id) },
                profile = operation.snapshot.managementProfile,
            )
            invalidate(operation.snapshot, "learning_graph", "skills")
            operation.api.getLearningGraph(profile = operation.snapshot.managementProfile)
        }

    private suspend fun awaitAction(
        started: JsonElement,
        api: HermesApi,
    ): com.hermesgadget.talaria.domain.model.ActionStatus {
        val name = (started as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            ?: error("Hermes did not return an action name")
        repeat(120) {
            val status = api.getActionStatus(name)
            if (!status.running) return status
            kotlinx.coroutines.delay(1_000)
        }
        error("Hermes action '$name' did not finish within two minutes")
    }

    suspend fun gateway(action: String) = withBoundOperation { operation ->
        val started = when (action) {
            "start" -> operation.api.gatewayStart()
            "stop" -> operation.api.gatewayStop()
            else -> operation.api.gatewayRestart()
        }
        awaitAction(started, operation.api).also {
            invalidate(operation.snapshot, "system", "portal")
        }
    }

    suspend fun recordActivity(
        type: String,
        title: String,
        body: String,
        snapshot: ConnectionSnapshot? = null,
    ) {
        val cid = snapshot?.scopeId ?: connId()
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

    suspend fun pollForNotifications(snapshot: ConnectionSnapshot? = null): SyncSnapshot {
        val bound = snapshot ?: clientFactory.snapshot()
            ?: throw IllegalStateException("No active Hermes connection")
        return withContext(Dispatchers.IO) {
            val boundApi = clientFactory.api(bound)
            val status = boundApi.getStatus(profile = bound.managementProfile)
            val pairing = try {
                boundApi.getPairing(profile = bound.managementProfile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            val cron = try {
                boundApi.getCronJobs(profile = bound.managementProfile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            SyncSnapshot(status, pairing, cron)
        }
    }
}

data class SyncSnapshot(
    val status: StatusResponse,
    val pairing: PairingResponse?,
    val cron: List<CronJob>?,
)
