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

package com.hermesgadget.talaria.core.notifications

import com.hermesgadget.talaria.core.network.HermesSideEvent
import com.hermesgadget.talaria.core.network.PromptKind

data class AgentThreadIdentity(
    val watcherId: String,
    val agentName: String,
    val sessionId: String? = null,
)

sealed interface AgentAlert {
    val agentName: String
    val sessionId: String?

    data class PermissionRequired(
        override val agentName: String,
        override val sessionId: String?,
        val notificationKey: String,
        val fingerprint: String,
        val body: String,
        val kind: PromptKind,
    ) : AgentAlert

    data class PermissionExpired(
        override val agentName: String,
        override val sessionId: String?,
        val notificationKey: String,
    ) : AgentAlert

    data class TaskFinished(
        override val agentName: String,
        override val sessionId: String?,
        val fingerprint: String,
        val body: String,
        val failed: Boolean,
        val background: Boolean,
    ) : AgentAlert
}

/** Pure mapping from Hermes sidecar events to user-visible agent alerts. */
object AgentNotificationPolicy {
    fun alert(identity: AgentThreadIdentity, event: HermesSideEvent): AgentAlert? {
        val agentName = identity.agentName.trim().take(MAX_AGENT_NAME_LENGTH).ifBlank { "Hermes agent" }
        val sessionId = event.sessionIdOrNull() ?: identity.sessionId
        return when (event) {
            is HermesSideEvent.Prompt -> {
                val notificationKey = promptKey(identity.watcherId, sessionId, event.requestId)
                AgentAlert.PermissionRequired(
                    agentName = agentName,
                    sessionId = sessionId,
                    notificationKey = notificationKey,
                    fingerprint = listOf(notificationKey, event.kind.name, event.message).joinToString("|"),
                    body = promptBody(event.kind, event.message),
                    kind = event.kind,
                )
            }
            is HermesSideEvent.PromptExpired -> AgentAlert.PermissionExpired(
                agentName = agentName,
                sessionId = sessionId,
                notificationKey = promptKey(identity.watcherId, sessionId, event.requestId),
            )
            is HermesSideEvent.MessageComplete -> {
                val failed = event.status?.lowercase() in FAILED_STATUSES
                val body = event.text.trim().ifBlank {
                    if (failed) "Hermes reported that the task failed." else "The task has finished."
                }
                AgentAlert.TaskFinished(
                    agentName = agentName,
                    sessionId = sessionId,
                    fingerprint = listOf(
                        "message.complete",
                        sessionId.orEmpty(),
                        event.status.orEmpty(),
                        body,
                        event.totalTokens?.toString().orEmpty(),
                    ).joinToString("|"),
                    body = body,
                    failed = failed,
                    background = false,
                )
            }
            is HermesSideEvent.BackgroundComplete -> AgentAlert.TaskFinished(
                agentName = agentName,
                sessionId = sessionId,
                fingerprint = listOf(
                    "background.complete",
                    sessionId.orEmpty(),
                    event.taskId.orEmpty(),
                    event.text,
                ).joinToString("|"),
                body = event.text.trim().ifBlank {
                    if (event.failed) "The background task failed." else "The background task has finished."
                },
                failed = event.failed,
                background = true,
            )
            else -> null
        }
    }

    private fun HermesSideEvent.sessionIdOrNull(): String? = when (this) {
        is HermesSideEvent.Prompt -> sessionId
        is HermesSideEvent.PromptExpired -> sessionId
        is HermesSideEvent.MessageComplete -> sessionId
        is HermesSideEvent.BackgroundComplete -> sessionId
        else -> null
    }

    private fun promptKey(watcherId: String, sessionId: String?, requestId: String?): String =
        requestId?.takeIf { it.isNotBlank() }
            ?: sessionId?.takeIf { it.isNotBlank() }
            ?: watcherId

    private fun promptBody(kind: PromptKind, message: String): String {
        val detail = message.trim().ifBlank { "Open Talaria to continue." }
        return when (kind) {
            PromptKind.APPROVAL -> "Permission required: $detail"
            PromptKind.CLARIFY -> "Hermes has a question: $detail"
            PromptKind.SUDO -> "Administrator permission required: $detail"
            PromptKind.SECRET -> "A secret is required: $detail"
        }
    }

    private val FAILED_STATUSES = setOf("error", "failed", "failure", "cancelled", "canceled")
    private const val MAX_AGENT_NAME_LENGTH = 80
}
