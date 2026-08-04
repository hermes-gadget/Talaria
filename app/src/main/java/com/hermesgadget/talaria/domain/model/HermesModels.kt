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

package com.hermesgadget.talaria.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class StatusResponse(
    val version: String? = null,
    val release_date: String? = null,
    val auth_required: Boolean? = null,
    val auth_providers: List<String> = emptyList(),
    val auth_flows: List<String> = emptyList(),
    val gateway: GatewayStatus? = null,
    /** Top-level running flag some dashboard versions emit instead of nesting it. */
    val gateway_running: Boolean? = null,
    val gateway_state: String? = null,
    val gateway_platforms: JsonElement? = null,
    val gateway_pid: Int? = null,
    val active_sessions: Int? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val profile: String? = null,
)

@Serializable
data class AuthProviderInfo(
    val name: String,
    val display_name: String? = null,
    val supports_password: Boolean = false,
)

@Serializable
data class AuthProvidersResponse(val providers: List<AuthProviderInfo> = emptyList())

@Serializable
data class PasswordLoginRequest(
    val provider: String,
    val username: String,
    val password: String,
    val next: String = "",
)

@Serializable
data class PasswordLoginResponse(
    val ok: Boolean = false,
    val next: String? = null,
)

@Serializable
data class GatewayStatus(
    val running: Boolean? = null,
    val pid: Int? = null,
    val state: String? = null,
    val platforms: JsonElement? = null,
)

@Serializable
data class SessionSummary(
    val id: String,
    val title: String? = null,
    val source: String? = null,
    val model: String? = null,
    val message_count: Int? = null,
    val tool_call_count: Int? = null,
    @Serializable(with = FlexiblePrimitiveStringSerializer::class)
    val started_at: String? = null,
    @Serializable(with = FlexiblePrimitiveStringSerializer::class)
    val last_active: String? = null,
    val preview: String? = null,
    val tokens: JsonElement? = null,
    val input_tokens: Long? = null,
    val output_tokens: Long? = null,
    val live: Boolean? = null,
    val is_active: Boolean? = null,
    /** Why the session ended — "session_reset", "compression", "agent_close", etc. Null while live. */
    val end_reason: String? = null,
    @Serializable(with = FlexiblePrimitiveStringSerializer::class)
    val ended_at: String? = null,
)

@Serializable
data class SessionsPage(
    val sessions: List<SessionSummary> = emptyList(),
    val total: Int? = null,
)

@Serializable
data class SessionMessage(
    val role: String? = null,
    @Serializable(with = FlexibleMessageTextSerializer::class)
    val content: String? = null,
    @Serializable(with = FlexiblePrimitiveStringSerializer::class)
    val timestamp: String? = null,
    val tool_calls: JsonElement? = null,
    val name: String? = null,
)

@Serializable
data class SessionMessagesResponse(
    val messages: List<SessionMessage> = emptyList(),
    /** Optional server-side transcript revision, emitted by newer Hermes builds. */
    @Serializable(with = FlexiblePrimitiveStringSerializer::class)
    val revision: String? = null,
    /** Older gateways use this more explicit name for the same revision. */
    @Serializable(with = FlexiblePrimitiveStringSerializer::class)
    val transcript_revision: String? = null,
    val message_count: Int? = null,
    @Serializable(with = FlexiblePrimitiveStringSerializer::class)
    val hash: String? = null,
    @Serializable(with = FlexiblePrimitiveStringSerializer::class)
    val content_hash: String? = null,
)

@Serializable
data class AuthMeResponse(
    val user_id: String? = null,
    val email: String? = null,
    val display_name: String? = null,
    val org_id: String? = null,
    val provider: String? = null,
    val expires_at: Long? = null,
)

@Serializable
data class WsTicketResponse(
    val ticket: String,
    val ttl_seconds: Int? = null,
)

@Serializable
data class EnvVarInfo(
    val key: String? = null,
    val is_set: Boolean? = null,
    val redacted_value: String? = null,
    val description: String? = null,
    val category: String? = null,
    val url: String? = null,
    val advanced: Boolean? = null,
)

@Serializable
data class CronJob(
    val id: String,
    val name: String? = null,
    val prompt: String? = null,
    val schedule: String? = null,
    val state: String? = null,
    val deliver: String? = null,
    @Serializable(with = FlexiblePrimitiveStringSerializer::class)
    val last_run: String? = null,
    @Serializable(with = FlexiblePrimitiveStringSerializer::class)
    val next_run: String? = null,
    val profile: String? = null,
)

@Serializable
data class SkillInfo(
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val enabled: Boolean? = null,
    val provenance: String? = null,
    val usage: Int? = null,
)

@Serializable
data class HubSkill(
    val name: String,
    val description: String? = null,
    val source: String? = null,
    val identifier: String,
    val trust_level: String? = null,
    val repo: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class HubSkillSearchResponse(
    val results: List<HubSkill> = emptyList(),
    val timed_out: List<String> = emptyList(),
)

@Serializable
data class McpServer(
    val name: String,
    val transport: String? = null,
    val url: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val enabled: Boolean? = null,
    val tools: List<String>? = null,
    val env: Map<String, String> = emptyMap(),
    val auth: String? = null,
)

@Serializable
data class McpServersResponse(val servers: List<McpServer> = emptyList())

@Serializable
data class McpOAuthFlow(
    val flow_id: String,
    val server_name: String = "",
    val status: String = "starting",
    val authorization_url: String? = null,
    val error: String? = null,
    val tools: List<McpTool> = emptyList(),
)

@Serializable
data class McpTool(
    val name: String,
    val description: String = "",
)

@Serializable
data class McpCatalogEnvVar(
    val name: String,
    val prompt: String = "",
    val required: Boolean = true,
)

@Serializable
data class McpCatalogEntry(
    val name: String,
    val description: String = "",
    val source: String = "",
    val transport: String = "",
    val auth_type: String = "none",
    val required_env: List<McpCatalogEnvVar> = emptyList(),
    val command: String? = null,
    val args: List<String> = emptyList(),
    val url: String? = null,
    val install_url: String? = null,
    val install_ref: String? = null,
    val bootstrap: List<String> = emptyList(),
    val default_enabled: List<String>? = null,
    val post_install: String = "",
    val needs_install: Boolean = false,
    val installed: Boolean = false,
    val enabled: Boolean = false,
)

@Serializable
data class McpCatalogDiagnostic(
    val name: String = "",
    val kind: String = "",
    val message: String = "",
)

@Serializable
data class McpCatalogResponse(
    val entries: List<McpCatalogEntry> = emptyList(),
    val diagnostics: List<McpCatalogDiagnostic> = emptyList(),
)

@Serializable
data class MessagingPlatform(
    val id: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean? = null,
    val configured: Boolean? = null,
    val state: String? = null,
    val error_message: String? = null,
    val env_keys: List<String> = emptyList(),
    val env_vars: List<MessagingEnvVarInfo> = emptyList(),
    val docs_url: String? = null,
    val gateway_running: Boolean? = null,
)

@Serializable
data class MessagingEnvVarInfo(
    val key: String,
    val prompt: String? = null,
    val description: String? = null,
    val required: Boolean = false,
    val is_password: Boolean = false,
    val is_set: Boolean = false,
    val redacted_value: String? = null,
    val url: String? = null,
    val advanced: Boolean = false,
)

@Serializable
data class MessagingPlatformsResponse(
    val platforms: List<MessagingPlatform> = emptyList(),
)

@Serializable
data class PairingUser(
    val platform: String,
    val user_id: String,
    val user_name: String? = null,
    val request_id: String? = null,
    val age_minutes: Double? = null,
    val code: String? = null,
)

@Serializable
data class PairingResponse(
    val pending: List<PairingUser> = emptyList(),
    val approved: List<PairingUser> = emptyList(),
)

@Serializable
data class WebhookRoute(
    val name: String,
    val description: String? = null,
    val events: List<String> = emptyList(),
    val deliver: String? = null,
    val prompt: String? = null,
    val url: String? = null,
    val enabled: Boolean? = null,
    /** One-time secret echoed by the dashboard on create (may never be sent again). */
    val secret: String? = null,
)

@Serializable
data class WebhooksResponse(
    val enabled: Boolean? = null,
    val base_url: String? = null,
    val subscriptions: List<WebhookRoute> = emptyList(),
)

@Serializable
data class ProfileInfo(
    val name: String,
    val description: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val is_default: Boolean? = null,
    val is_active: Boolean? = null,
    val skill_count: Int? = null,
    val gateway_running: Boolean? = null,
    val description_auto: Boolean? = null,
)

@Serializable
data class ProfilesResponse(val profiles: List<ProfileInfo> = emptyList())

@Serializable
data class ActiveProfileResponse(val active: String? = null)

@Serializable
data class SystemStats(
    val os: String? = null,
    val hostname: String? = null,
    val python: String? = null,
    val python_version: String? = null,
    val hermes_version: String? = null,
    val cpu_percent: Double? = null,
    val memory: JsonElement? = null,
    val disk: JsonElement? = null,
    val uptime: JsonElement? = null,
    val uptime_seconds: Long? = null,
)

@Serializable
data class AnalyticsUsage(
    /** Legacy dashboard shape. */
    val days: Int? = null,
    val total_input_tokens: Long? = null,
    val total_output_tokens: Long? = null,
    val total_cost: Double? = null,
    val session_count: Int? = null,
    val daily: JsonElement? = null,
    val models: JsonElement? = null,
    /** Current Hermes dashboard shape. */
    val period_days: Int? = null,
    val by_model: JsonElement? = null,
    val by_task: JsonElement? = null,
    val totals: AnalyticsTotals? = null,
)

@Serializable
data class AnalyticsTotals(
    val total_input: Long? = null,
    val total_output: Long? = null,
    val total_cache_read: Long? = null,
    val total_reasoning: Long? = null,
    val total_estimated_cost: Double? = null,
    val total_actual_cost: Double? = null,
    val total_sessions: Int? = null,
    val total_api_calls: Long? = null,
)

@Serializable
data class LogLinesResponse(
    val lines: List<String> = emptyList(),
    val file: String? = null,
)

@Serializable
data class OkResponse(val ok: Boolean? = null)

@Serializable
data class ConfigSchemaResponse(
    val fields: JsonObject? = null,
    val category_order: List<String> = emptyList(),
)

data class ChatLine(
    val id: String,
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class ModelInfo(
    val model: String? = null,
    val provider: String? = null,
    val connected: Boolean? = null,
    val base_url: String? = null,
)

@Serializable
data class ModelOption(
    val id: String? = null,
    val name: String? = null,
    val provider: String? = null,
    val label: String? = null,
)

@Serializable
data class ToolsetInfo(
    val name: String,
    val label: String? = null,
    val description: String? = null,
    val active: Boolean? = null,
    val enabled: Boolean? = null,
    val available: Boolean? = null,
    val configured: Boolean? = null,
    val platform: String? = null,
    val tools: List<String> = emptyList(),
)

data class ToolCallUi(
    val id: String,
    val name: String,
    val status: String,
    val argsPreview: String? = null,
    val message: String? = null,
)

data class SlashCommand(
    val command: String,
    val description: String,
    val category: String = "Commands",
    val aliases: List<String> = emptyList(),
    val argumentMode: SlashArgumentMode = SlashArgumentMode.NONE,
)

enum class SlashArgumentMode { NONE, OPTIONS, TEXT, MIXED }

object SlashCommands {
    /**
     * Offline fallback used until the connected Hermes sidecar returns its
     * live `commands.catalog`. The live catalog also includes user quick
     * commands and installed skill commands, so it always replaces this list
     * when available.
     */
    val defaults = listOf(
        SlashCommand("/help", "Show available commands", "Info", aliases = listOf("/commands")),
        SlashCommand("/new", "Start a fresh session", "Session", aliases = listOf("/reset"), argumentMode = SlashArgumentMode.TEXT),
        SlashCommand("/resume", "Resume a saved session", "Session", aliases = listOf("/sessions", "/switch"), argumentMode = SlashArgumentMode.MIXED),
        SlashCommand("/retry", "Retry the last message", "Session"),
        SlashCommand("/undo", "Back up one or more user turns", "Session", argumentMode = SlashArgumentMode.OPTIONS),
        SlashCommand("/title", "Set the current session title", "Session", argumentMode = SlashArgumentMode.TEXT),
        SlashCommand("/branch", "Branch the current session", "Session", aliases = listOf("/fork"), argumentMode = SlashArgumentMode.TEXT),
        SlashCommand("/compress", "Compress conversation context", "Session", aliases = listOf("/compact"), argumentMode = SlashArgumentMode.MIXED),
        SlashCommand("/rollback", "List or restore filesystem checkpoints", "Session", argumentMode = SlashArgumentMode.OPTIONS),
        SlashCommand("/stop", "Stop running work", "Session"),
        SlashCommand("/background", "Run a prompt in the background", "Session", aliases = listOf("/bg", "/btw"), argumentMode = SlashArgumentMode.TEXT),
        SlashCommand("/queue", "Queue a prompt for the next turn", "Session", aliases = listOf("/q"), argumentMode = SlashArgumentMode.TEXT),
        SlashCommand("/steer", "Steer the run after the next tool call", "Session", argumentMode = SlashArgumentMode.TEXT),
        SlashCommand("/agents", "Show active agents and tasks", "Session", aliases = listOf("/tasks")),
        SlashCommand("/goal", "Manage the standing goal", "Session", argumentMode = SlashArgumentMode.MIXED),
        SlashCommand("/subgoal", "Manage goal completion criteria", "Session", argumentMode = SlashArgumentMode.MIXED),
        SlashCommand("/status", "Show session, model, token, and context info", "Info"),
        SlashCommand("/context", "Show detailed context-window usage", "Info", aliases = listOf("/ctx"), argumentMode = SlashArgumentMode.OPTIONS),
        SlashCommand("/usage", "Show token usage and rate limits", "Info", argumentMode = SlashArgumentMode.OPTIONS),
        SlashCommand("/model", "Switch the model for this session", "Configuration", argumentMode = SlashArgumentMode.OPTIONS),
        SlashCommand("/personality", "Set a personality", "Configuration", argumentMode = SlashArgumentMode.OPTIONS),
        SlashCommand("/reasoning", "Manage reasoning effort and display", "Configuration", argumentMode = SlashArgumentMode.OPTIONS),
        SlashCommand("/approvals", "Show or set dangerous-command approval mode", "Configuration", argumentMode = SlashArgumentMode.OPTIONS),
        SlashCommand("/yolo", "Toggle dangerous-command auto-approval", "Configuration"),
        SlashCommand("/fast", "Toggle fast inference mode", "Configuration", argumentMode = SlashArgumentMode.OPTIONS),
        SlashCommand("/tools", "List or toggle tools", "Tools & Skills", argumentMode = SlashArgumentMode.OPTIONS),
        SlashCommand("/skills", "Search, install, or manage skills", "Tools & Skills", argumentMode = SlashArgumentMode.OPTIONS),
        SlashCommand("/memory", "Review pending memory writes", "Tools & Skills", argumentMode = SlashArgumentMode.OPTIONS),
        SlashCommand("/learn", "Learn a reusable skill", "Tools & Skills", argumentMode = SlashArgumentMode.TEXT),
        SlashCommand("/reload", "Reload environment variables", "Tools & Skills"),
        SlashCommand("/reload-mcp", "Reload MCP servers", "Tools & Skills", aliases = listOf("/reload_mcp")),
        SlashCommand("/reload-skills", "Reload installed skills", "Tools & Skills", aliases = listOf("/reload_skills")),
    )

    /** Rank prefix, alias, fuzzy-subsequence, and description matches. */
    fun suggest(
        text: String,
        catalog: List<SlashCommand> = defaults,
        limit: Int = 12,
    ): List<SlashCommand> {
        if (!text.startsWith('/')) return emptyList()
        val token = text.substringBefore(' ').lowercase()
        if (text.contains(' ')) return emptyList()
        val needle = token.removePrefix("/")

        return catalog.asSequence()
            .distinctBy { it.command.lowercase() }
            .mapNotNull { command ->
                val name = command.command.removePrefix("/").lowercase()
                val aliases = command.aliases.map { it.removePrefix("/").lowercase() }
                val score = when {
                    needle.isEmpty() -> 4
                    name == needle -> 0
                    name.startsWith(needle) -> 1
                    aliases.any { it == needle || it.startsWith(needle) } -> 2
                    name.containsSubsequence(needle) -> 3
                    command.description.contains(needle, ignoreCase = true) -> 5
                    else -> return@mapNotNull null
                }
                Triple(score, name.length, command)
            }
            .sortedWith(compareBy<Triple<Int, Int, SlashCommand>> { it.first }.thenBy { it.second }.thenBy { it.third.command })
            .take(limit)
            .map { it.third }
            .toList()
    }

    private fun String.containsSubsequence(needle: String): Boolean {
        if (needle.isEmpty()) return true
        var index = 0
        for (char in this) {
            if (char == needle[index]) index += 1
            if (index == needle.length) return true
        }
        return false
    }
}

@Serializable
data class MemoryState(
    val active: String? = null,
    val providers: List<MemoryProvider> = emptyList(),
    val builtin_files: MemoryBuiltinFiles = MemoryBuiltinFiles(),
)

@Serializable
data class MemoryBuiltinFiles(
    val memory: Long = 0,
    val user: Long = 0,
)

@Serializable
data class MemoryProvider(
    val name: String,
    val description: String? = null,
    val available: Boolean = false,
    val configured: Boolean = false,
    val status: String? = null,
    val setup: MemoryProviderSetup? = null,
)

@Serializable
data class MemoryProviderSetup(
    val pip_dependencies: List<String> = emptyList(),
    val required_env: List<String> = emptyList(),
    val dependencies_installed: Boolean = false,
)

@Serializable
data class CuratorState(
    val enabled: Boolean = false,
    val paused: Boolean = false,
    val interval_hours: Double? = null,
    val last_run_at: String? = null,
    val min_idle_hours: Double? = null,
    val stale_after_days: Int? = null,
    val archive_after_days: Int? = null,
)

@Serializable
data class ActionStatus(
    val name: String = "",
    val running: Boolean = false,
    val exit_code: Int? = null,
    val pid: Int? = null,
    val lines: List<String> = emptyList(),
)
