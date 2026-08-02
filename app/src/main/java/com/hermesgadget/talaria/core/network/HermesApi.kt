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

package com.hermesgadget.talaria.core.network

import com.hermesgadget.talaria.domain.model.ActiveProfileResponse
import com.hermesgadget.talaria.domain.model.AnalyticsUsage
import com.hermesgadget.talaria.domain.model.AuthMeResponse
import com.hermesgadget.talaria.domain.model.AuthProvidersResponse
import com.hermesgadget.talaria.domain.model.ConfigSchemaResponse
import com.hermesgadget.talaria.domain.model.CronJob
import com.hermesgadget.talaria.domain.model.EnvVarInfo
import com.hermesgadget.talaria.domain.model.FsCwd
import com.hermesgadget.talaria.domain.model.FsDataUrl
import com.hermesgadget.talaria.domain.model.FsListResponse
import com.hermesgadget.talaria.domain.model.FsTextFile
import com.hermesgadget.talaria.domain.model.GitBaseBranchesResponse
import com.hermesgadget.talaria.domain.model.GitBranchSwitchRequest
import com.hermesgadget.talaria.domain.model.GitBranchSwitchResponse
import com.hermesgadget.talaria.domain.model.GitBranchesResponse
import com.hermesgadget.talaria.domain.model.GitCommitRequest
import com.hermesgadget.talaria.domain.model.GitDiffResponse
import com.hermesgadget.talaria.domain.model.GitFileRequest
import com.hermesgadget.talaria.domain.model.GitPathRequest
import com.hermesgadget.talaria.domain.model.GitReviewListResponse
import com.hermesgadget.talaria.domain.model.GitRevParseResponse
import com.hermesgadget.talaria.domain.model.GitStatus
import com.hermesgadget.talaria.domain.model.GitWorktreeAddRequest
import com.hermesgadget.talaria.domain.model.GitWorktreeRemoveRequest
import com.hermesgadget.talaria.domain.model.GitWorktreesResponse
import com.hermesgadget.talaria.domain.model.LearningGraph
import com.hermesgadget.talaria.domain.model.LearningNodeDetail
import com.hermesgadget.talaria.domain.model.ActionStatus
import com.hermesgadget.talaria.domain.model.LogLinesResponse
import com.hermesgadget.talaria.domain.model.McpServersResponse
import com.hermesgadget.talaria.domain.model.McpOAuthFlow
import com.hermesgadget.talaria.domain.model.McpCatalogResponse
import com.hermesgadget.talaria.domain.model.MessagingPlatformsResponse
import com.hermesgadget.talaria.domain.model.OkResponse
import com.hermesgadget.talaria.domain.model.OpsActionResponse
import com.hermesgadget.talaria.domain.model.OpsBackupRequest
import com.hermesgadget.talaria.domain.model.OpsDebugShareRequest
import com.hermesgadget.talaria.domain.model.OpsDebugShareResponse
import com.hermesgadget.talaria.domain.model.OpsHookCreateRequest
import com.hermesgadget.talaria.domain.model.OpsHookDeleteRequest
import com.hermesgadget.talaria.domain.model.OpsHooksResponse
import com.hermesgadget.talaria.domain.model.OpsImportRequest
import com.hermesgadget.talaria.domain.model.OpsRawConfigResponse
import com.hermesgadget.talaria.domain.model.OpsRawConfigUpdate
import com.hermesgadget.talaria.domain.model.PairingResponse
import com.hermesgadget.talaria.domain.model.ProfilesResponse
import com.hermesgadget.talaria.domain.model.SessionMessagesResponse
import com.hermesgadget.talaria.domain.model.SessionSummary
import com.hermesgadget.talaria.domain.model.SessionsPage
import com.hermesgadget.talaria.domain.model.SkillInfo
import com.hermesgadget.talaria.domain.model.HubSkillSearchResponse
import com.hermesgadget.talaria.domain.model.StatusResponse
import com.hermesgadget.talaria.domain.model.PasswordLoginRequest
import com.hermesgadget.talaria.domain.model.PasswordLoginResponse
import com.hermesgadget.talaria.domain.model.SystemStats
import com.hermesgadget.talaria.domain.model.WebhooksResponse
import com.hermesgadget.talaria.domain.model.WsTicketResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Streaming

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

    /** Raw cron surface for dashboard v0.19.1, whose schedule shape is not CronJob.schedule. */
    @GET("api/cron/jobs")
    suspend fun getCronJobsRaw(@Query("profile") profile: String? = null): JsonElement

    @POST("api/cron/jobs")
    suspend fun createCronJobRaw(@Body body: JsonObject, @Query("profile") profile: String? = null): JsonElement

    @PUT("api/cron/jobs/{id}")
    suspend fun updateCronJobRaw(
        @Path("id") id: String,
        @Body body: JsonObject,
        @Query("profile") profile: String? = null,
    ): JsonElement

    @POST("api/cron/jobs/{id}/pause")
    suspend fun pauseCronRaw(@Path("id") id: String, @Query("profile") profile: String? = null): JsonElement

    @POST("api/cron/jobs/{id}/resume")
    suspend fun resumeCronRaw(@Path("id") id: String, @Query("profile") profile: String? = null): JsonElement

    @POST("api/cron/jobs/{id}/trigger")
    suspend fun triggerCronRaw(@Path("id") id: String, @Query("profile") profile: String? = null): JsonElement

    @DELETE("api/cron/jobs/{id}")
    suspend fun deleteCronRaw(@Path("id") id: String, @Query("profile") profile: String? = null): JsonElement

    @GET("api/cron/jobs/{job_id}/runs")
    suspend fun getCronJobRunsRaw(
        @Path("job_id") jobId: String,
        @Query("limit") limit: Int = 50,
        @Query("profile") profile: String? = null,
    ): JsonElement

    @GET("api/cron/delivery-targets")
    suspend fun getCronDeliveryTargetsRaw(@Query("profile") profile: String? = null): JsonElement

    @GET("api/cron/blueprints")
    suspend fun getCronBlueprintsRaw(@Query("profile") profile: String? = null): JsonElement

    @POST("api/cron/blueprints/instantiate")
    suspend fun instantiateCronBlueprintRaw(
        @Body body: JsonObject,
        @Query("profile") profile: String? = null,
    ): JsonElement

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

    @GET("api/skills/content")
    suspend fun getSkillContentRaw(
        @Query("name") name: String,
        @Query("profile") profile: String? = null,
    ): JsonElement

    @PUT("api/skills/content")
    suspend fun putSkillContentRaw(@Body body: JsonObject, @Query("profile") profile: String? = null): JsonElement

    @POST("api/skills/hub/update")
    suspend fun updateSkillsHubRaw(@Body body: JsonObject, @Query("profile") profile: String? = null): JsonElement

    @GET("api/sessions/stats")
    suspend fun getSessionStatsRaw(@Query("profile") profile: String? = null): JsonElement

    @POST("api/sessions/bulk-delete")
    suspend fun bulkDeleteSessionsRaw(@Body body: JsonObject): JsonElement

    @DELETE("api/sessions/empty")
    suspend fun deleteEmptySessionsRaw(@Query("profile") profile: String? = null): JsonElement

    @GET("api/sessions/empty/count")
    suspend fun getEmptySessionCountRaw(@Query("profile") profile: String? = null): JsonElement

    @POST("api/sessions/import")
    suspend fun importSessionsRaw(@Body body: JsonObject): JsonElement

    @GET("api/sessions/{session_id}/latest-descendant")
    suspend fun getLatestDescendantRaw(
        @Path("session_id") sessionId: String,
        @Query("profile") profile: String? = null,
    ): JsonElement

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

    @GET("api/fs/read-data-url")
    suspend fun fsReadDataUrl(
        @Query("path") path: String,
        @Query("profile") profile: String? = null,
    ): FsDataUrl

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

    // --- Git review (Desktop parity §1.5 / §9.8) ---

    @GET("api/git/status")
    suspend fun gitStatus(@Query("path") path: String): GitStatus

    @GET("api/git/worktrees")
    suspend fun gitWorktrees(@Query("path") path: String): GitWorktreesResponse

    @GET("api/git/branches")
    suspend fun gitBranches(@Query("path") path: String): GitBranchesResponse

    @GET("api/git/base-branches")
    suspend fun gitBaseBranches(@Query("path") path: String): GitBaseBranchesResponse

    @GET("api/git/review/list")
    suspend fun gitReviewList(
        @Query("path") path: String,
        @Query("scope") scope: String = "uncommitted",
        @Query("base") base: String? = null,
    ): GitReviewListResponse

    @GET("api/git/review/diff")
    suspend fun gitReviewDiff(
        @Query("path") path: String,
        @Query("file") file: String,
        @Query("scope") scope: String = "uncommitted",
        @Query("base") base: String? = null,
        @Query("staged") staged: Boolean = false,
    ): GitDiffResponse

    @GET("api/git/file-diff")
    suspend fun gitFileDiff(
        @Query("path") path: String,
        @Query("file") file: String,
    ): GitDiffResponse

    @GET("api/git/review/commit-context")
    suspend fun gitCommitContext(@Query("path") path: String): JsonElement

    @GET("api/git/review/rev-parse")
    suspend fun gitRevParse(
        @Query("path") path: String,
        @Query("ref") ref: String? = null,
    ): GitRevParseResponse

    @GET("api/git/review/ship-info")
    suspend fun gitShipInfo(@Query("path") path: String): JsonElement

    @POST("api/git/review/stage")
    suspend fun gitStage(@Body body: GitFileRequest): JsonElement

    @POST("api/git/review/unstage")
    suspend fun gitUnstage(@Body body: GitFileRequest): JsonElement

    @POST("api/git/review/revert")
    suspend fun gitRevert(@Body body: GitFileRequest): JsonElement

    @POST("api/git/review/commit")
    suspend fun gitCommit(@Body body: GitCommitRequest): JsonElement

    @POST("api/git/review/push")
    suspend fun gitPush(@Body body: GitPathRequest): JsonElement

    @POST("api/git/review/create-pr")
    suspend fun gitCreatePr(@Body body: GitPathRequest): JsonElement

    @POST("api/git/worktree/add")
    suspend fun gitWorktreeAdd(@Body body: GitWorktreeAddRequest): JsonElement

    @POST("api/git/worktree/remove")
    suspend fun gitWorktreeRemove(@Body body: GitWorktreeRemoveRequest): JsonElement

    @POST("api/git/branch/switch")
    suspend fun gitBranchSwitch(@Body body: GitBranchSwitchRequest): GitBranchSwitchResponse

    // --- Ops upgrades (Talaria feature/ops-system; Hermes v0.19.1) ---

    @POST("api/ops/import")
    suspend fun importOps(
        @Body body: OpsImportRequest,
    ): OpsActionResponse

    @Multipart
    @POST("api/ops/import-upload")
    suspend fun importOpsUpload(
        @Part file: MultipartBody.Part,
        @Part("force") force: RequestBody,
    ): OpsActionResponse

    @POST("api/ops/backup")
    suspend fun createOpsBackup(
        @Body body: OpsBackupRequest = OpsBackupRequest(),
    ): OpsActionResponse

    @Streaming
    @GET("api/ops/backup/download")
    suspend fun downloadOpsBackup(
        @Query("archive") archive: String,
    ): ResponseBody

    @GET("api/ops/hooks")
    suspend fun getOpsHooks(): OpsHooksResponse

    @POST("api/ops/hooks")
    suspend fun createOpsHook(
        @Body body: OpsHookCreateRequest,
    ): OpsActionResponse

    @HTTP(method = "DELETE", path = "api/ops/hooks", hasBody = true)
    suspend fun deleteOpsHook(
        @Body body: OpsHookDeleteRequest,
    ): OpsActionResponse

    @POST("api/ops/debug-share")
    suspend fun createOpsDebugShare(
        @Body body: OpsDebugShareRequest = OpsDebugShareRequest(),
    ): OpsDebugShareResponse

    @GET("api/config/raw")
    suspend fun getOpsRawConfig(
        @Query("profile") profile: String? = null,
    ): OpsRawConfigResponse

    @PUT("api/config/raw")
    suspend fun putOpsRawConfig(
        @Body body: OpsRawConfigUpdate,
        @Query("profile") profile: String? = null,
    ): OpsActionResponse

    // --- Provider onboarding ---

    /** Newer gateways expose this catalog; older v0.19.x uses model/options. */
    @GET("api/providers")
    suspend fun getProviders(): JsonElement

    @GET("api/providers/custom-endpoints")
    suspend fun getCustomEndpoints(): JsonElement

    /** Hermes v0.19.1 calls this operation POST (the dashboard docs say upsert). */
    @POST("api/providers/custom-endpoints")
    suspend fun upsertCustomEndpoint(@Body body: JsonObject): JsonElement

    @POST("api/providers/custom-endpoints/validate")
    suspend fun validateCustomEndpoint(@Body body: JsonObject): JsonElement

    @POST("api/providers/custom-endpoints/{endpoint_id}/activate")
    suspend fun activateCustomEndpoint(@Path("endpoint_id") endpointId: String): JsonElement

    @DELETE("api/providers/custom-endpoints/{endpoint_id}")
    suspend fun deleteCustomEndpoint(@Path("endpoint_id") endpointId: String): JsonElement

    @POST("api/providers/validate")
    suspend fun validateProvider(@Body body: JsonObject): JsonElement

    @GET("api/credentials/pool")
    suspend fun getCredentialPool(): JsonElement

    @POST("api/credentials/pool")
    suspend fun addCredentialPoolEntry(@Body body: JsonObject): JsonElement

    @DELETE("api/credentials/pool/{provider}/{index}")
    suspend fun deleteCredentialPoolEntry(
        @Path("provider") provider: String,
        @Path("index") index: Int,
    ): JsonElement

    /** Read-only provider OAuth catalog; individual flow actions are user initiated. */
    @GET("api/providers/oauth")
    suspend fun getProviderOAuth(): JsonElement

    @POST("api/providers/oauth/{provider_id}/start")
    suspend fun startProviderOAuth(@Path("provider_id") providerId: String): JsonElement

    @POST("api/providers/oauth/{provider_id}/submit")
    suspend fun submitProviderOAuth(
        @Path("provider_id") providerId: String,
        @Body body: JsonObject,
    ): JsonElement

    @GET("api/providers/oauth/{provider_id}/poll/{session_id}")
    suspend fun pollProviderOAuth(
        @Path("provider_id") providerId: String,
        @Path("session_id") sessionId: String,
    ): JsonElement

    // --- Multi-profile streaming ---

    /** Explicit registry call; unlike the legacy method this is named by its use. */
    @GET("api/profiles")
    suspend fun getProfilesForMultiProfile(): ProfilesResponse

    /**
     * Fetch one Hermes management profile without relying on the active-profile
     * interceptor. The endpoint returns either a sessions array or an envelope.
     */
    @GET("api/sessions")
    suspend fun getSessionsForProfile(
        @Query("profile") profile: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("order") order: String = "recent",
    ): JsonElement
}
