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

import com.hermesgadget.talaria.domain.model.ChatLine
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionControlsTest {
    @Test
    fun rewindConfirmationDoesNotStartUntilConfirmed() {
        val requested = ChatSessionControlsReducer.requestRewind(
            state = ChatSessionControlsState(),
            tabId = "tab-1",
            sessionId = "runtime-parent",
            messageCount = 2,
            preview = "Keep the first answer",
        )

        val dialog = requested.dialog as ChatSessionDialog.Rewind
        assertEquals(2, dialog.messageCount)
        assertEquals(ChatSessionActionState.Idle, requested.action)

        val running = ChatSessionControlsReducer.begin(requested, ChatSessionActionKind.REWIND)
        assertNull(running.dialog)
        assertEquals(
            ChatSessionActionState.Running(ChatSessionActionKind.REWIND),
            running.action,
        )
    }

    @Test
    fun cancellingCompactLeavesNoPendingAction() {
        val requested = ChatSessionControlsReducer.requestCompact(
            state = ChatSessionControlsState(),
            tabId = "tab-1",
            sessionId = "runtime-parent",
        )

        val cancelled = ChatSessionControlsReducer.dismissDialog(requested)
        assertNull(cancelled.dialog)
        assertEquals(ChatSessionActionState.Idle, cancelled.action)
    }

    @Test
    fun titleEditorUsesTheSameConfirmThenRunTransition() {
        val requested = ChatSessionControlsReducer.requestTitleEdit(
            state = ChatSessionControlsState(),
            sessionId = "stored-parent",
            tabId = "tab-1",
            initialTitle = "Old title",
        )

        assertEquals("Old title", (requested.dialog as ChatSessionDialog.EditTitle).initialTitle)
        assertEquals(ChatSessionActionState.Idle, requested.action)

        val running = ChatSessionControlsReducer.begin(requested, ChatSessionActionKind.RENAME)
        assertNull(running.dialog)
        assertEquals(ChatSessionActionState.Running(ChatSessionActionKind.RENAME), running.action)
    }

    @Test
    fun successfulBranchTransitionCarriesCompletionNotice() {
        val running = ChatSessionControlsReducer.begin(
            ChatSessionControlsReducer.requestRewind(
                ChatSessionControlsState(),
                tabId = "tab-1",
                sessionId = "runtime-parent",
                messageCount = 1,
                preview = "Hello",
            ),
            ChatSessionActionKind.REWIND,
        )

        val completed = ChatSessionControlsReducer.succeed(
            running,
            ChatSessionActionKind.REWIND,
            "Opened branch",
        )
        assertTrue(completed.action is ChatSessionActionState.Success)
        assertEquals("Opened branch", (completed.action as ChatSessionActionState.Success).message)
    }

    @Test
    fun branchOriginProjectionKeepsOptionalLineage() {
        val root = Json.parseToJsonElement(
            """{"sessions":[{"id":"child","parent_session_id":"parent"},{"id":"root"}]}""",
        )

        assertEquals(mapOf("child" to "parent"), parseSessionBranchOrigins(root))
    }

    @Test
    fun malformedLineageContainersAndRowsAreIgnored() {
        assertEquals(emptyMap<String, String>(), parseSessionBranchOrigins(Json.parseToJsonElement("{\"sessions\":{}}")))
        assertEquals(
            mapOf("valid" to "parent"),
            parseSessionBranchOrigins(
                Json.parseToJsonElement(
                    """{"sessions":[{"id":{}},{"id":"valid","parent_session_id":"parent"},{"id":"bad","parent_session_id":null}]}""",
                ),
            ),
        )
    }

    @Test
    fun messageCountUsesPersistedMessageIndexWhenAvailable() {
        assertEquals(3, branchMessageCount(ChatLine("session-2", "assistant", "answer"), 0))
        assertEquals(2, branchMessageCount(ChatLine("optimistic", "user", "question"), 1))
    }

    @Test
    fun editingBranchesBeforeTheSelectedUserPrompt() {
        val first = ChatMessageTarget("tab", "session", 1, "user", "first")
        val later = first.copy(messageCount = 4)

        assertEquals(0, editedMessageBranchCount(first))
        assertEquals(3, editedMessageBranchCount(later))
    }

    @Test
    fun messageActionsCanTransitionIntoAnEditDialog() {
        val target = ChatMessageTarget("tab", "session", 2, "user", "old prompt")
        val actions = ChatSessionControlsReducer.requestMessageActions(
            ChatSessionControlsState(),
            target,
        )

        val edit = ChatSessionControlsReducer.requestMessageEdit(actions, target)
        assertEquals(target, (edit.dialog as ChatSessionDialog.EditMessage).target)
    }

    @Test
    fun parseSessionActionMessagesExcludesToolAndSystemRoles() {
        val root = Json.parseToJsonElement(
            """
            {
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "hi there"},
                    {"role": "tool", "content": "edit_file done"},
                    {"role": "system", "content": "system note"}
                ]
            }
            """,
        )
        val lines = parseSessionActionMessages(root as kotlinx.serialization.json.JsonObject, "s1")
        assertEquals(2, lines!!.size)
        assertEquals("user", lines[0].role)
        assertEquals("hello", lines[0].text)
        assertEquals("assistant", lines[1].role)
        assertEquals("hi there", lines[1].text)
    }
}
