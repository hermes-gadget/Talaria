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

package com.hermesgadget.talaria.feature.chat

import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.domain.model.ChatLine
import com.hermesgadget.talaria.domain.model.SessionMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** The destructive or session-mutating action currently represented by the chat UI. */
enum class ChatSessionActionKind {
    REWIND,
    COMPACT,
    RENAME,
    EDIT,
}

data class ChatMessageTarget(
    val tabId: String,
    val sessionId: String,
    val messageCount: Int,
    val role: String,
    val text: String,
)

/** Confirmation/editor dialogs owned by the chat ViewModel rather than local screen state. */
sealed interface ChatSessionDialog {
    data class Rewind(
        val tabId: String,
        val sessionId: String,
        val messageCount: Int,
        val preview: String,
    ) : ChatSessionDialog

    data class Compact(
        val tabId: String,
        val sessionId: String,
    ) : ChatSessionDialog

    data class EditTitle(
        val sessionId: String,
        val tabId: String?,
        val initialTitle: String,
    ) : ChatSessionDialog

    data class MessageActions(val target: ChatMessageTarget) : ChatSessionDialog

    data class EditMessage(val target: ChatMessageTarget) : ChatSessionDialog
}

sealed interface ChatSessionActionState {
    data object Idle : ChatSessionActionState

    data class Running(val kind: ChatSessionActionKind) : ChatSessionActionState

    data class Success(
        val kind: ChatSessionActionKind,
        val message: String,
    ) : ChatSessionActionState

    data class Failure(
        val kind: ChatSessionActionKind,
        val message: String,
    ) : ChatSessionActionState
}

data class ChatSessionControlsState(
    val dialog: ChatSessionDialog? = null,
    val action: ChatSessionActionState = ChatSessionActionState.Idle,
)

/**
 * Pure state transitions for session actions. Keeping the confirmation flow here makes
 * the ViewModel contract unit-testable without opening Android sockets or a Compose host.
 */
internal object ChatSessionControlsReducer {
    fun requestRewind(
        state: ChatSessionControlsState,
        tabId: String,
        sessionId: String,
        messageCount: Int,
        preview: String,
    ): ChatSessionControlsState = state.copy(
        dialog = ChatSessionDialog.Rewind(
            tabId = tabId,
            sessionId = sessionId,
            messageCount = messageCount,
            preview = preview,
        ),
        action = ChatSessionActionState.Idle,
    )

    fun requestCompact(
        state: ChatSessionControlsState,
        tabId: String,
        sessionId: String,
    ): ChatSessionControlsState = state.copy(
        dialog = ChatSessionDialog.Compact(tabId, sessionId),
        action = ChatSessionActionState.Idle,
    )

    fun requestTitleEdit(
        state: ChatSessionControlsState,
        sessionId: String,
        tabId: String?,
        initialTitle: String,
    ): ChatSessionControlsState = state.copy(
        dialog = ChatSessionDialog.EditTitle(sessionId, tabId, initialTitle),
        action = ChatSessionActionState.Idle,
    )

    fun requestMessageActions(
        state: ChatSessionControlsState,
        target: ChatMessageTarget,
    ): ChatSessionControlsState = state.copy(
        dialog = ChatSessionDialog.MessageActions(target),
        action = ChatSessionActionState.Idle,
    )

    fun requestMessageEdit(
        state: ChatSessionControlsState,
        target: ChatMessageTarget,
    ): ChatSessionControlsState = state.copy(
        dialog = ChatSessionDialog.EditMessage(target),
        action = ChatSessionActionState.Idle,
    )

    fun dismissDialog(state: ChatSessionControlsState): ChatSessionControlsState =
        state.copy(dialog = null)

    fun begin(
        state: ChatSessionControlsState,
        kind: ChatSessionActionKind,
    ): ChatSessionControlsState = state.copy(
        dialog = null,
        action = ChatSessionActionState.Running(kind),
    )

    fun succeed(
        state: ChatSessionControlsState,
        kind: ChatSessionActionKind,
        message: String,
    ): ChatSessionControlsState = state.copy(
        dialog = null,
        action = ChatSessionActionState.Success(kind, message),
    )

    fun fail(
        state: ChatSessionControlsState,
        kind: ChatSessionActionKind,
        message: String,
    ): ChatSessionControlsState = state.copy(
        dialog = null,
        action = ChatSessionActionState.Failure(kind, message),
    )
}

/** Extracts branch lineage from the raw sessions response without widening SessionSummary. */
internal fun parseSessionBranchOrigins(root: JsonElement): Map<String, String> {
    val rows = when (root) {
        is JsonArray -> root
        is JsonObject -> root["sessions"]?.jsonArray
            ?: root["results"]?.jsonArray
            ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return rows.mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val parent = obj["parent_session_id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        id to parent
    }.toMap()
}

/** REST session messages are the authoritative post-compaction transcript when returned. */
internal fun parseSessionActionMessages(root: JsonObject, sessionId: String): List<ChatLine>? {
    val messages = root["messages"] as? JsonArray ?: return null
    return messages.mapIndexedNotNull { index, element ->
        runCatching { JsonConfig.json.decodeFromJsonElement<SessionMessage>(element) }
            .getOrNull()
            ?.let { message ->
                val text = message.content.orEmpty()
                if (text.isBlank()) null else ChatLine(
                    id = "$sessionId-$index",
                    role = message.role ?: "assistant",
                    text = text,
                )
            }
    }
}

/** Maps a visible reading-transcript row back to the backend history prefix length. */
internal fun branchMessageCount(line: ChatLine, displayedIndex: Int): Int =
    (line.id.substringAfterLast('-').toIntOrNull()?.plus(1) ?: displayedIndex + 1).coerceAtLeast(1)

/** Editing a prompt retains the history prefix before that prompt in the child chat. */
internal fun editedMessageBranchCount(target: ChatMessageTarget): Int =
    (target.messageCount - 1).coerceAtLeast(0)
