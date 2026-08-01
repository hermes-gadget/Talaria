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

package com.nousresearch.talaria.core.network

import com.nousresearch.talaria.domain.model.ActiveProfileResponse
import com.nousresearch.talaria.domain.model.AnalyticsUsage
import com.nousresearch.talaria.domain.model.AuthMeResponse
import com.nousresearch.talaria.domain.model.AuthProvidersResponse
import com.nousresearch.talaria.domain.model.ConfigSchemaResponse
import com.nousresearch.talaria.domain.model.CronJob
import com.nousresearch.talaria.domain.model.EnvVarInfo
import com.nousresearch.talaria.domain.model.FsCwd
import com.nousresearch.talaria.domain.model.FsListResponse
import com.nousresearch.talaria.domain.model.FsTextFile
import com.nousresearch.talaria.domain.model.LearningGraph
import com.nousresearch.talaria.domain.model.LearningNodeDetail
import com.nousresearch.talaria.domain.model.ActionStatus
import com.nousresearch.talaria.domain.model.LogLinesResponse
import com.nousresearch.talaria.domain.model.McpServersResponse
import com.nousresearch.talaria.domain.model.McpOAuthFlow
import com.nousresearch.talaria.domain.model.McpCatalogResponse
import com.nousresearch.talaria.domain.model.MessagingPlatformsResponse
import com.nousresearch.talaria.domain.model.OkResponse
import com.nousresearch.talaria.domain.model.PairingResponse
import com.nousresearch.talaria.domain.model.ProfilesResponse
import com.nousresearch.talaria.domain.model.SessionMessagesResponse
import com.nousresearch.talaria.domain.model.SessionSummary
import com.nousresearch.talaria.domain.model.SessionsPage
import com.nousresearch.talaria.domain.model.SkillInfo
import com.nousresearch.talaria.domain.model.HubSkillSearchResponse
import com.nousresearch.talaria.domain.model.StatusResponse
import com.nousresearch.talaria.domain.model.PasswordLoginRequest
import com.nousresearch.talaria.domain.model.PasswordLoginResponse
import com.nousresearch.talaria.domain.model.SystemStats
import com.nousresearch.talaria.domain.model.WebhooksResponse
import com.nousresearch.talaria.domain.model.WsTicketResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

/**
 * Retrofit surface mapped to Hermes Web Dashboard /api/ endpoints.
 * Baseline: Hermes Agent v0.19.1 dashboard API (session token / gated auth).
 */
interface HermesApi {
    @GET("api/status")
    suspend fun getStatus(@Query("profile") profile: String? = null): StatusResponse

    @GET("api/auth/me")
    suspend fun authMe(): AuthMeResponse

    @GET("api/auth/providers")
    suspend fun authProviders(): AuthProvidersResponse

    @POST("api/auth/ws-ticket")
    suspend fun wsTicket(): WsTicketResponse

    @GET("api/sessions")
    suspend fun getSessions(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("order") order: String = "recent",
        @Query("profile") profile: String? = null,
        @Query("source") source: String? = null,
    ): JsonElement

    @GET("api/sessions/{id}")
    suspend fun getSession(@Path("id") id: String, @Query("profile") profile: String? = null): SessionSummary

    @GET("api/sessions/{id}/messages")
    suspend fun getSessionMessages(@Path("id") id: String, @Query("profile") profile: String? = null): SessionMessagesResponse

    @PATCH("api/sessions/{id}")
    suspend fun patchSession(@Path("id") id: String, @Body body: JsonObject, @Query("profile") profile: String? = null): OkResponse

    @DELETE("api/sessions/{id}")
    suspend fun deleteSession(@Path("id") id: String, @Query("profile") profile: String? = null): OkResponse

    @GET("api/sessions/search")
    suspend fun searchSessions(@Query("q") q: String, @Query("profile") profile: String? = null): JsonElement

    @POST("api/sessions/prune")
    suspend fun pruneSessions(@Body body: JsonObject, @Query("profile") profile: String? = null): JsonElement

    @GET("api/config")
    suspend fun getConfig(@Query("profile") profile: String? = null): JsonObject

    @PUT("api/config")
    suspend fun putConfig(@Body body: JsonObject, @Query("profile") profile: String? = null): OkResponse

    @GET("api/config/defaults")
    suspend fun getConfigDefaults(): JsonObject

    @GET("api/config/schema")
    suspend fun getConfigSchema(): ConfigSchemaResponse

    @GET("api/env")
    suspend fun getEnv(@Query("profile") profile: String? = null): Map<String, EnvVarInfo>

    @PUT("api/env")
    suspend fun putEnv(@Body body: JsonObject, @Query("profile") profile: String? = null): OkResponse

    @HTTP(method = "DELETE", path = "api/env", hasBody = true)
    suspend fun deleteEnv(@Body body: JsonObject, @Query("profile") profile: String? = null): OkResponse

    @GET("api/logs")
    suspend fun getLogs(
        @Query("file") file: String = "agent",
        @Query("lines") lines: Int = 100,
        @Query("level") level: String? = null,
        @Query("component") component: String? = null,
    ): LogLinesResponse

    @GET("api/analytics/usage")
    suspend fun getAnalytics(@Query("days") days: Int = 30, @Query("profile") profile: String? = null): AnalyticsUsage

    @GET("api/cron/jobs")
    suspend fun getCronJobs(@Query("profile") profile: String? = null): List<CronJob>

    @POST("api/cron/jobs")
    suspend fun createCronJob(@Body body: JsonObject, @Query("profile") profile: String? = null): CronJob

    @PUT("api/cron/jobs/{id}")
    suspend fun updateCronJob(@Path("id") id: String, @Body body: JsonObject, @Query("profile") profile: String? = null): CronJob

    @POST("api/cron/jobs/{id}/pause")
    suspend fun pauseCron(@Path("id") id: String, @Query("profile") profile: String? = null): CronJob

    @POST("api/cron/jobs/{id}/resume")
    suspend fun resumeCron(@Path("id") id: String, @Query("profile") profile: String? = null): CronJob

    @POST("api/cron/jobs/{id}/trigger")
    suspend fun triggerCron(@Path("id") id: String, @Query("profile") profile: String? = null): CronJob

    @DELETE("api/cron/jobs/{id}")
    suspend fun deleteCron(@Path("id") id: String, @Query("profile") profile: String? = null): OkResponse

    @GET("api/skills")
    suspend fun getSkills(@Query("profile") profile: String? = null): List<SkillInfo>

    @PUT("api/skills/toggle")
    suspend fun toggleSkill(@Body body: JsonObject, @Query("profile") profile: String? = null): OkResponse

    @GET("api/skills/hub/search")
    suspend fun searchSkillHub(
        @Query("q") query: String,
        @Query("limit") limit: Int = 30,
        @Query("profile") profile: String? = null,
    ): HubSkillSearchResponse

    @GET("api/skills/hub/preview")
    suspend fun previewHubSkill(
        @Query("identifier") identifier: String,
        @Query("profile") profile: String? = null,
    ): JsonElement

    @GET("api/skills/hub/scan")
    suspend fun scanHubSkill(
        @Query("identifier") identifier: String,
        @Query("profile") profile: String? = null,
    ): JsonElement

    @POST("api/skills/hub/install")
    suspend fun installHubSkill(@Body body: JsonObject, @Query("profile") profile: String? = null): JsonElement

    @POST("api/skills/hub/uninstall")
    suspend fun uninstallHubSkill(@Body body: JsonObject, @Query("profile") profile: String? = null): JsonElement

    @GET("api/mcp/servers")
    suspend fun getMcpServers(@Query("profile") profile: String? = null): McpServersResponse

    @POST("api/mcp/servers")
    suspend fun addMcpServer(@Body body: JsonObject, @Query("profile") profile: String? = null): JsonElement

    @DELETE("api/mcp/servers/{name}")
    suspend fun deleteMcpServer(@Path("name") name: String, @Query("profile") profile: String? = null): OkResponse

    @PUT("api/mcp/servers/{name}/enabled")
    suspend fun setMcpEnabled(@Path("name") name: String, @Body body: JsonObject, @Query("profile") profile: String? = null): OkResponse

    @POST("api/mcp/servers/{name}/test")
    suspend fun testMcp(@Path("name") name: String, @Query("profile") profile: String? = null): JsonElement

    @POST("api/mcp/servers/{name}/auth")
    suspend fun startMcpOAuth(@Path("name") name: String, @Query("profile") profile: String? = null): McpOAuthFlow

    @GET("api/mcp/oauth/flows/{id}")
    suspend fun getMcpOAuthFlow(@Path("id") id: String): McpOAuthFlow

    @GET("api/mcp/catalog")
    suspend fun getMcpCatalog(@Query("profile") profile: String? = null): McpCatalogResponse

    @POST("api/mcp/catalog/install")
    suspend fun installMcpCatalogEntry(
        @Body body: JsonObject,
        @Query("profile") profile: String? = null,
    ): JsonElement

    @GET("api/messaging/platforms")
    suspend fun getMessagingPlatforms(@Query("profile") profile: String? = null): MessagingPlatformsResponse

    @PUT("api/messaging/platforms/{id}")
    suspend fun updateMessagingPlatform(@Path("id") id: String, @Body body: JsonObject, @Query("profile") profile: String? = null): JsonElement

    @POST("api/messaging/platforms/{id}/test")
    suspend fun testMessagingPlatform(@Path("id") id: String, @Query("profile") profile: String? = null): JsonElement

    @GET("api/pairing")
    suspend fun getPairing(@Query("profile") profile: String? = null): PairingResponse

    @POST("api/pairing/approve")
    suspend fun approvePairing(@Body body: JsonObject, @Query("profile") profile: String? = null): JsonElement

    @POST("api/pairing/revoke")
    suspend fun revokePairing(@Body body: JsonObject, @Query("profile") profile: String? = null): OkResponse

    @POST("api/pairing/clear-pending")
    suspend fun clearPendingPairing(@Query("profile") profile: String? = null): JsonElement

    @GET("api/webhooks")
    suspend fun getWebhooks(): WebhooksResponse

    @POST("api/webhooks/enable")
    suspend fun enableWebhooks(): JsonElement

    @POST("api/webhooks")
    suspend fun createWebhook(@Body body: JsonObject): JsonElement

    @DELETE("api/webhooks/{name}")
    suspend fun deleteWebhook(@Path("name") name: String): OkResponse

    @PUT("api/webhooks/{name}/enabled")
    suspend fun setWebhookEnabled(@Path("name") name: String, @Body body: JsonObject): OkResponse

    @GET("api/profiles")
    suspend fun getProfiles(): ProfilesResponse

    @GET("api/profiles/active")
    suspend fun getActiveProfile(): ActiveProfileResponse

    @POST("api/profiles/active")
    suspend fun setActiveProfile(@Body body: JsonObject): ActiveProfileResponse

    @POST("api/profiles")
    suspend fun createProfile(@Body body: JsonObject): JsonElement

    @PATCH("api/profiles/{name}")
    suspend fun renameProfile(@Path("name") name: String, @Body body: JsonObject): JsonElement

    @DELETE("api/profiles/{name}")
    suspend fun deleteProfile(@Path("name") name: String): JsonElement

    @GET("api/profiles/{name}/soul")
    suspend fun getProfileSoul(@Path("name") name: String): JsonElement

    @PUT("api/profiles/{name}/soul")
    suspend fun updateProfileSoul(@Path("name") name: String, @Body body: JsonObject): JsonElement

    @PUT("api/profiles/{name}/description")
    suspend fun updateProfileDescription(@Path("name") name: String, @Body body: JsonObject): JsonElement

    @POST("api/profiles/{name}/describe-auto")
    suspend fun describeProfileAuto(@Path("name") name: String, @Body body: JsonObject): JsonElement

    @GET("api/system/stats")
    suspend fun getSystemStats(): SystemStats

    @POST("api/gateway/start")
    suspend fun gatewayStart(): JsonElement

    @POST("api/gateway/stop")
    suspend fun gatewayStop(): JsonElement

    @POST("api/gateway/restart")
    suspend fun gatewayRestart(): JsonElement

    @GET("api/portal")
    suspend fun getPortal(): JsonElement

    @GET("api/memory")
    suspend fun getMemory(): JsonElement

    @PUT("api/memory/provider")
    suspend fun setMemoryProvider(@Body body: JsonObject): JsonElement

    @POST("api/memory/reset")
    suspend fun resetMemory(@Body body: JsonObject): JsonElement

    @GET("api/curator")
    suspend fun getCurator(): JsonElement

    @PUT("api/curator/paused")
    suspend fun setCuratorPaused(@Body body: JsonObject): JsonElement

    @POST("api/curator/run")
    suspend fun runCurator(@Body body: JsonObject = JsonObject(emptyMap())): JsonElement

    @GET("api/actions/{name}/status")
    suspend fun getActionStatus(@Path("name") name: String, @Query("lines") lines: Int = 200): ActionStatus

    @POST("api/ops/doctor")
    suspend fun runDoctor(): JsonElement

    @POST("api/ops/security-audit")
    suspend fun runSecurityAudit(): JsonElement

    @POST("api/ops/backup")
    suspend fun runBackup(@Body body: JsonObject = JsonObject(emptyMap())): JsonElement

    @GET("api/hermes/update/check")
    suspend fun checkUpdate(@Query("force") force: Boolean = false): JsonElement

    @GET("api/model/info")
    suspend fun getModelInfo(@Query("profile") profile: String? = null): JsonElement

    @GET("api/model/options")
    suspend fun getModelOptions(@Query("profile") profile: String? = null): JsonElement

    @POST("api/model/set")
    suspend fun setModel(@Body body: JsonObject, @Query("profile") profile: String? = null): JsonElement

    @GET("api/tools/toolsets")
    suspend fun getToolsets(@Query("profile") profile: String? = null): JsonElement

    @PUT("api/tools/toolsets/{name}")
    suspend fun setToolset(
        @Path("name") name: String,
        @Body body: JsonObject,
        @Query("profile") profile: String? = null,
    ): JsonElement

    /** Password-provider login. A successful response mints dashboard session cookies. */
    @POST("auth/password-login")
    suspend fun passwordLogin(@Body body: PasswordLoginRequest): PasswordLoginResponse

    // --- Files pane (Desktop parity 15.1) ---

    @GET("api/fs/default-cwd")
    suspend fun fsDefaultCwd(@Query("profile") profile: String? = null): FsCwd

    @GET("api/fs/list")
    suspend fun fsList(
        @Query("path") path: String,
        @Query("profile") profile: String? = null,
    ): FsListResponse

    @GET("api/fs/read-text")
    suspend fun fsReadText(
        @Query("path") path: String,
        @Query("profile") profile: String? = null,
    ): FsTextFile

    @POST("api/fs/write-text")
    suspend fun fsWriteText(
        @Body body: JsonObject,
        @Query("profile") profile: String? = null,
    ): JsonElement

    @GET("api/fs/git-root")
    suspend fun fsGitRoot(
        @Query("path") path: String,
        @Query("profile") profile: String? = null,
    ): JsonElement

    // --- Learning graph / Starmap (Desktop parity 15.4) ---

    @GET("api/learning/graph")
    suspend fun getLearningGraph(@Query("profile") profile: String? = null): LearningGraph

    @GET("api/learning/node")
    suspend fun getLearningNode(
        @Query("id") id: String,
        @Query("profile") profile: String? = null,
    ): LearningNodeDetail

    @HTTP(method = "DELETE", path = "api/learning/node", hasBody = true)
    suspend fun deleteLearningNode(
        @Body body: JsonObject,
        @Query("profile") profile: String? = null,
    ): JsonElement

    @PUT("api/learning/node")
    suspend fun updateLearningNode(
        @Body body: JsonObject,
        @Query("profile") profile: String? = null,
    ): JsonElement
}
