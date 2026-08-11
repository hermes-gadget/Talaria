/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.manage.config

import com.hermesgadget.talaria.core.network.JsonConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConfigJsonEditorTest {
    @Test
    fun readsAndUpdatesNestedDotPathsWithoutCreatingTopLevelKeys() {
        val original = """{"terminal":{"lifetime_seconds":30},"security":{"telemetry":true}}"""
        val root = JsonConfig.json.parseToJsonElement(original).jsonObject
        assertEquals(30, configValueAtPath(root, "terminal.lifetime_seconds")?.jsonPrimitive?.int)

        val value = parseConfigDraft("false", "boolean")
        val updated = applyConfigEdit(original, "security.telemetry", value)
        val parsed = JsonConfig.json.parseToJsonElement(updated).jsonObject
        assertFalse(parsed["security"]!!.jsonObject["telemetry"]!!.jsonPrimitive.boolean)
        assertEquals(null, parsed["security.telemetry"])
    }

    @Test
    fun preservesStringValuesThatLookNumeric() {
        val value = parseConfigDraft("123", "string")
        val updated = applyConfigEdit("{}", "voice.provider", value)
        val parsed = JsonConfig.json.parseToJsonElement(updated).jsonObject
        assertEquals("123", parsed["voice"]!!.jsonObject["provider"]!!.jsonPrimitive.content)
    }

    @Test
    fun updatesParsedLargeModelWithoutChangingUnrelatedBranches() {
        val root = JsonObject(
            (0 until 2_000).associate { index ->
                "unused_$index" to JsonPrimitive(index)
            } + ("terminal" to JsonObject(mapOf("lifetime_seconds" to JsonPrimitive(30)))),
        )

        val updated = setConfigValueAtPath(
            root = root,
            parts = listOf("terminal", "lifetime_seconds"),
            value = JsonPrimitive(60),
        )

        assertEquals(2_000, updated.keys.count { it.startsWith("unused_") })
        assertEquals(
            60,
            updated["terminal"]!!.jsonObject["lifetime_seconds"]!!.jsonPrimitive.int,
        )
    }
}
