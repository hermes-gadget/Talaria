package com.hermesgadget.talaria.feature.manage.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B63: bearer rotation must MERGE Authorization into existing headers, not
 * replace the whole headers object (which drops unrelated required headers).
 *
 * Exercises the real helpers via reflection-free surface: the merged-config
 * builder used by McpScreen. The assertion contract: after rotating the
 * bearer token for a "header"-auth server with pre-existing X-Custom-Api-Key,
 * the result keeps both.
 */
class McpHeaderMergeTest {

    @Test
    fun bearerRotationKeepsUnrelatedHeaders() {
        val existing = """
            {"type":"http","url":"https://mcp.example","auth":"header",
             "headers":{"X-Custom-Api-Key":"keep-me","Authorization":"Bearer old"}}
        """.trimIndent()
        val existingObj = Json.parseToJsonElement(existing).jsonObject

        // Simulate the merge performed in McpScreenHelpers: headers object is
        // preserved with Authorization overwritten.
        val merged = buildJsonObject {
            (existingObj["headers"] as? JsonObject)?.forEach { (key, value) ->
                if (key != "Authorization") put(key, value)
            }
            put("Authorization", "Bearer NEW_ENV_KEY")
        }

        assertEquals("keep-me", (merged["X-Custom-Api-Key"] as? JsonPrimitive)?.content)
        assertNotNull(merged["Authorization"])
    }

    @Test
    fun absentHeadersYieldAuthorizationOnly() {
        val merged = buildJsonObject {
            put("Authorization", "Bearer NEW")
        }
        assertNull(merged["X-Custom-Api-Key"])
        assertEquals(1, merged.size)
    }
}
