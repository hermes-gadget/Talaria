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
package com.hermesgadget.talaria.network

import com.hermesgadget.talaria.core.network.HermesSideEvent
import com.hermesgadget.talaria.core.network.SidecarFrameParser
import com.hermesgadget.talaria.core.network.ToolCallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarFrameParserTest {

    /** Real /api/ws + /api/events shape: JSON-RPC `event` envelope, type in params. */
    @Test
    fun parsesSessionInfoFromEventEnvelope() {
        val frame = """
            {"jsonrpc":"2.0","method":"event","params":{
              "type":"session.info","session_id":"aa41e954",
              "payload":{"model":"deepseek-v4-flash","provider":"deepseek",
                "reasoning_effort":"medium","approval_mode":"manual","yolo":false,"fast":false}}}
        """.trimIndent()
        val event = SidecarFrameParser.parse(frame)
        assertTrue(event is HermesSideEvent.SessionInfo)
        event as HermesSideEvent.SessionInfo
        assertEquals("deepseek-v4-flash", event.model)
        assertEquals("deepseek", event.provider)
        assertEquals("medium", event.reasoningEffort)
        assertEquals("manual", event.approvalMode)
        assertEquals(false, event.yolo)
    }

    /** The envelope's real type must win over the outer "event" method name. */
    @Test
    fun eventEnvelopeTypeIsNotClobbered() {
        val frame = """{"jsonrpc":"2.0","method":"event","params":{"type":"sessions.changed","session_id":"","payload":{}}}"""
        val event = SidecarFrameParser.parse(frame)
        assertTrue(event is HermesSideEvent.SessionsChanged)
        assertEquals("", (event as HermesSideEvent.SessionsChanged).sessionId)
    }

    @Test
    fun parsesSessionEndedAndEventGap() {
        val ended = SidecarFrameParser.parse(
            """{"method":"event","params":{"type":"session.ended","session_id":"s1","payload":{"reason":"agent_close"}}}""",
        ) as HermesSideEvent.SessionEnded
        assertEquals("s1", ended.sessionId)
        assertEquals("agent_close", ended.reason)

        val gap = SidecarFrameParser.parse(
            """{"type":"event.gap","session_id":"s1","payload":{"reason":"missed sequence"}}""",
        ) as HermesSideEvent.EventGap
        assertEquals("s1", gap.sessionId)
        assertEquals("missed sequence", gap.reason)
    }

    @Test
    fun parsesGatewayReadyAsRaw() {
        val frame = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""
        val event = SidecarFrameParser.parse(frame)
        assertEquals("gateway.ready", (event as HermesSideEvent.Raw).type)
    }

    @Test
    fun parsesFlatToolFrame() {
        val frame = """{"type":"tool.complete","name":"web_search","id":"call_1"}"""
        val event = SidecarFrameParser.parse(frame)
        assertTrue(event is HermesSideEvent.Tool)
        event as HermesSideEvent.Tool
        assertEquals("web_search", event.name)
        assertEquals(ToolCallStatus.DONE, event.status)
    }

    @Test
    fun parsesNestedToolFrame() {
        val frame = """{"jsonrpc":"2.0","method":"event","params":{"type":"tool.complete","session_id":"s1","payload":{"tool_id":"call_2","name":"terminal","summary":"done"}}}"""
        val event = SidecarFrameParser.parse(frame)
        assertTrue(event is HermesSideEvent.Tool)
        event as HermesSideEvent.Tool
        assertEquals("call_2", event.id)
        assertEquals("terminal", event.name)
        assertEquals("done", event.message)
    }

    @Test
    fun parsesMessageCompleteWithUsage() {
        val frame = """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"s1","payload":{"text":"final answer","status":"complete","usage":{"prompt_tokens":10,"completion_tokens":5,"cost_usd":0.02}}}}"""
        val event = SidecarFrameParser.parse(frame)
        assertTrue(event is HermesSideEvent.MessageComplete)
        event as HermesSideEvent.MessageComplete
        assertEquals("s1", event.sessionId)
        assertEquals("final answer", event.text)
        assertEquals(15L, event.totalTokens)
        assertEquals(0.02, event.costUsd!!, 0.0)
    }

    @Test
    fun parsesMessageFailureReasonAsFailedCompletion() {
        val event = SidecarFrameParser.parse(
            """{"method":"event","params":{"type":"message.complete","session_id":"s1","payload":{"failure_reason":"provider unavailable"}}}""",
        ) as HermesSideEvent.MessageComplete
        assertEquals("provider unavailable", event.text)
        assertEquals("error", event.status)
    }

    @Test
    fun parsesNestedApprovalDescription() {
        val frame = """{"method":"event","params":{"type":"approval.request","payload":{"command":"rm file","description":"Delete the file?"}}}"""
        val event = SidecarFrameParser.parse(frame)
        assertTrue(event is HermesSideEvent.Prompt)
        assertEquals("Delete the file?", (event as HermesSideEvent.Prompt).message)
    }

    @Test
    fun parsesUsageWithComputedTotal() {
        val frame = """{"type":"usage","usage":{"prompt_tokens":100,"completion_tokens":40}}"""
        val event = SidecarFrameParser.parse(frame)
        assertTrue(event is HermesSideEvent.Usage)
        event as HermesSideEvent.Usage
        assertEquals(100L, event.promptTokens)
        assertEquals(40L, event.completionTokens)
        assertEquals(140L, event.totalTokens)
    }

    @Test
    fun parsesNestedUsageAndModelPayloads() {
        val usage = SidecarFrameParser.parse(
            """{"method":"event","params":{"type":"usage.update","payload":{"usage":{"input_tokens":7,"output_tokens":3}}}}""",
        ) as HermesSideEvent.Usage
        assertEquals(10L, usage.totalTokens)

        val model = SidecarFrameParser.parse(
            """{"method":"event","params":{"type":"model.changed","payload":{"model":"qwen3","connected":true}}}""",
        ) as HermesSideEvent.Model
        assertEquals("qwen3", model.name)
        assertEquals(true, model.connected)
    }

    @Test
    fun parsesPromptFrame() {
        val frame = """{"type":"approval.request","session_id":"s1","payload":{"description":"Run command?","command":"rm file","choices":["once","deny"]}}"""
        val event = SidecarFrameParser.parse(frame)
        assertTrue(event is HermesSideEvent.Prompt)
        event as HermesSideEvent.Prompt
        assertEquals("Run command?", event.message)
        assertEquals(listOf("once", "deny"), event.choices)
        assertEquals("s1", event.sessionId)
    }

    @Test
    fun parsesSecretAndPromptExpiry() {
        val secret = SidecarFrameParser.parse(
            """{"method":"event","params":{"type":"secret.request","session_id":"s1","payload":{"request_id":"r1","env_var":"API_KEY","prompt":"Enter key"}}}""",
        ) as HermesSideEvent.Prompt
        assertEquals(com.hermesgadget.talaria.core.network.PromptKind.SECRET, secret.kind)
        assertEquals("r1", secret.requestId)

        val expired = SidecarFrameParser.parse(
            """{"method":"event","params":{"type":"secret.expire","session_id":"s1","payload":{"request_id":"r1"}}}""",
        ) as HermesSideEvent.PromptExpired
        assertEquals("r1", expired.requestId)
    }

    @Test
    fun parsesBackgroundCompletion() {
        val event = SidecarFrameParser.parse(
            """{"method":"event","params":{"type":"background.complete","session_id":"s1","payload":{"task_id":"bg_123","text":"Report ready"}}}""",
        ) as HermesSideEvent.BackgroundComplete
        assertEquals("s1", event.sessionId)
        assertEquals("bg_123", event.taskId)
        assertEquals("Report ready", event.text)
        assertEquals(false, event.failed)
    }

    @Test
    fun detectsFailedBackgroundCompletion() {
        val event = SidecarFrameParser.parse(
            """{"type":"background.complete","session_id":"s1","payload":{"task_id":"bg_123","text":"error: unavailable"}}""",
        ) as HermesSideEvent.BackgroundComplete
        assertEquals(true, event.failed)
    }

    /** JSON-RPC result responses (id + result, no method/type) are not events. */
    @Test
    fun ignoresResultResponses() {
        val frame = """{"jsonrpc":"2.0","id":1,"result":{"ok":true}}"""
        assertNull(SidecarFrameParser.parse(frame))
    }

    @Test
    fun ignoresGarbage() {
        assertNull(SidecarFrameParser.parse("not json"))
        assertNull(SidecarFrameParser.parse("""{"no":"type"}"""))
    }
}
