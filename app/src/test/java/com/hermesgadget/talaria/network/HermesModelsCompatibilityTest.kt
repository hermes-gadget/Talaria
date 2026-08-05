/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hermesgadget.talaria.network

import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.domain.model.SessionMessagesResponse
import com.hermesgadget.talaria.domain.model.SessionSummary
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesModelsCompatibilityTest {
    @Test
    fun decodesCurrentNumericSessionTimestamps() {
        val session = JsonConfig.json.decodeFromString<SessionSummary>(
            """{"id":"s1","started_at":1785591000.25,"last_active":1785591060,"is_active":true}""",
        )
        assertEquals("1785591000.25", session.started_at)
        assertEquals("1785591060", session.last_active)
        assertEquals(true, session.is_active)
    }

    @Test
    fun decodesStructuredMultimodalMessageContent() {
        val response = JsonConfig.json.decodeFromString<SessionMessagesResponse>(
            """{"session_id":"s1","messages":[{"role":"user","timestamp":1785591000,"content":[{"type":"text","text":"describe this"},{"type":"image_url","image_url":{"url":"data:image/png;base64,x"}}]}]}""",
        )
        assertEquals("1785591000", response.messages.single().timestamp)
        assertEquals("describe this\n[image]", response.messages.single().content)
    }

    @Test
    fun deeplyNestedMessageContentIsBounded() {
        val nested = buildString {
            append("{\"content\":")
            repeat(100) { append("{\"content\":") }
            append("\"done\"")
            repeat(101) { append('}') }
        }

        val response = JsonConfig.json.decodeFromString<SessionMessagesResponse>(
            "{\"session_id\":\"s1\",\"messages\":[{\"role\":\"assistant\",\"content\":$nested}]}",
        )

        assertTrue(response.messages.single().content.orEmpty().length <= 64 * 1024)
    }
}
