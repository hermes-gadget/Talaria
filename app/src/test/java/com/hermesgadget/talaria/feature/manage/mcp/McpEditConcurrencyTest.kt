package com.hermesgadget.talaria.feature.manage.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpEditConcurrencyTest {
    @Test
    fun `detects additions edits and removals outside the submitted map`() {
        val baseline = JsonObject(
            mapOf(
                "alpha" to JsonObject(mapOf("url" to JsonPrimitive("https://alpha.test"))),
                "beta" to JsonObject(mapOf("url" to JsonPrimitive("https://beta.test"))),
            ),
        )
        val current = JsonObject(
            mapOf(
                "alpha" to JsonObject(mapOf("url" to JsonPrimitive("https://changed.test"))),
                "gamma" to JsonObject(mapOf("url" to JsonPrimitive("https://gamma.test"))),
            ),
        )

        assertEquals(setOf("alpha", "beta", "gamma"), changedMcpServerNames(baseline, current))
    }

    @Test
    fun `accepts an unchanged server map`() {
        val servers = JsonObject(
            mapOf("alpha" to JsonObject(mapOf("command" to JsonPrimitive("uvx")))),
        )

        assertTrue(changedMcpServerNames(servers, servers).isEmpty())
    }
}
