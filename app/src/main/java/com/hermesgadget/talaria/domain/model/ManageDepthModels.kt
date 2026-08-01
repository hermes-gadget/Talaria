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

/** UI-facing cron job shape. The dashboard's schedule is an object on v0.19.1. */
data class ManageCronJob(
    val id: String,
    val name: String? = null,
    val prompt: String? = null,
    val scheduleExpression: String? = null,
    val scheduleDisplay: String? = null,
    val state: String? = null,
    val enabled: Boolean? = null,
    val deliver: String? = null,
    val lastRunAt: String? = null,
    val nextRunAt: String? = null,
    val lastStatus: String? = null,
    val lastError: String? = null,
    val lastDeliveryError: String? = null,
    val profile: String? = null,
    val isActive: Boolean? = null,
)

data class CronRun(
    val id: String,
    val status: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val output: String? = null,
    val error: String? = null,
    val preview: String? = null,
    val endReason: String? = null,
    val model: String? = null,
    val messageCount: Int? = null,
    val toolCallCount: Int? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val isActive: Boolean? = null,
    val raw: String = "",
)

data class CronDeliveryTarget(
    val id: String,
    val name: String,
    val homeTargetSet: Boolean = false,
    val homeEnvVar: String? = null,
)

data class AutomationBlueprint(
    val key: String,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val fields: List<AutomationBlueprintField> = emptyList(),
    val schedule: String? = null,
    val scheduleHuman: String? = null,
    val command: String? = null,
    val appUrl: String? = null,
)

data class AutomationBlueprintField(
    val name: String,
    val type: String = "text",
    val label: String? = null,
    val defaultValue: String? = null,
    val options: List<String> = emptyList(),
    val optional: Boolean = false,
    val strict: Boolean = false,
    val help: String? = null,
)

data class SessionStats(
    val total: Int = 0,
    val activeStore: Int = 0,
    val archived: Int = 0,
    val messages: Int = 0,
    val bySource: Map<String, Int> = emptyMap(),
)

data class BulkDeleteSessionsResponse(
    val ok: Boolean = false,
    val deleted: Int = 0,
)

data class EmptySessionCount(val count: Int = 0)

data class EmptySessionsDeleteResponse(
    val ok: Boolean = false,
    val deleted: Int = 0,
)

data class LatestDescendantResponse(
    val requestedSessionId: String,
    val sessionId: String,
    val path: List<String> = emptyList(),
    val changed: Boolean = false,
)

data class SessionImportResponse(
    val ok: Boolean = false,
    val imported: Int = 0,
    val skipped: Int = 0,
    val detached: Int = 0,
    val importedIds: List<String> = emptyList(),
    val skippedIds: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)

data class SkillContentResponse(
    val name: String,
    val content: String,
    val path: String? = null,
)

data class SkillWriteResponse(
    val ok: Boolean = false,
    val message: String? = null,
    val path: String? = null,
    val error: String? = null,
)

data class HubUpdateResponse(
    val ok: Boolean = false,
    val pid: Int? = null,
    val name: String? = null,
)
