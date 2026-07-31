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

package com.nousresearch.talaria.domain.model

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
    val gateway: GatewayStatus? = null,
    val active_sessions: Int? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val profile: String? = null,
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
    val started_at: String? = null,
    val last_active: String? = null,
    val preview: String? = null,
    val tokens: JsonElement? = null,
    val live: Boolean? = null,
)

@Serializable
data class SessionsPage(
    val sessions: List<SessionSummary> = emptyList(),
    val total: Int? = null,
)

@Serializable
data class SessionMessage(
    val role: String? = null,
    val content: String? = null,
    val timestamp: String? = null,
    val tool_calls: JsonElement? = null,
    val name: String? = null,
)

@Serializable
data class SessionMessagesResponse(
    val messages: List<SessionMessage> = emptyList(),
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
    val last_run: String? = null,
    val next_run: String? = null,
    val profile: String? = null,
)

@Serializable
data class SkillInfo(
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val enabled: Boolean? = null,
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
)

@Serializable
data class McpServersResponse(val servers: List<McpServer> = emptyList())

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
    val docs_url: String? = null,
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
    val is_default: Boolean? = null,
    val is_active: Boolean? = null,
    val skill_count: Int? = null,
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
    val hermes_version: String? = null,
    val cpu_percent: Double? = null,
    val memory: JsonElement? = null,
    val disk: JsonElement? = null,
    val uptime: JsonElement? = null,
)

@Serializable
data class AnalyticsUsage(
    val days: Int? = null,
    val total_input_tokens: Long? = null,
    val total_output_tokens: Long? = null,
    val total_cost: Double? = null,
    val session_count: Int? = null,
    val daily: JsonElement? = null,
    val models: JsonElement? = null,
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
    val configured: Boolean? = null,
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
)

object SlashCommands {
    val defaults = listOf(
        SlashCommand("/help", "Show TUI help"),
        SlashCommand("/model", "Open model picker / set model"),
        SlashCommand("/reload", "Reload .env / API keys"),
        SlashCommand("/clear", "Clear the conversation view"),
        SlashCommand("/compact", "Compact context"),
        SlashCommand("/stop", "Stop the current generation"),
        SlashCommand("/new", "Start a fresh session"),
    )
}
