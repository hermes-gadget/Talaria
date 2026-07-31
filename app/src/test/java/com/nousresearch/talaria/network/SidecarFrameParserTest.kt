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
package com.nousresearch.talaria.network

import com.nousresearch.talaria.core.network.HermesSideEvent
import com.nousresearch.talaria.core.network.SidecarFrameParser
import com.nousresearch.talaria.core.network.ToolCallStatus
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
        assertTrue(event is HermesSideEvent.Raw)
        assertEquals("sessions.changed", (event as HermesSideEvent.Raw).type)
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
    fun parsesPromptFrame() {
        val frame = """{"type":"approval.request","message":"Run rm -rf?"}"""
        val event = SidecarFrameParser.parse(frame)
        assertTrue(event is HermesSideEvent.Prompt)
        assertEquals("Run rm -rf?", (event as HermesSideEvent.Prompt).message)
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
