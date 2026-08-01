/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.manage.config

import com.hermesgadget.talaria.core.network.JsonConfig
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

        val updated = updateConfigKey(original, "security.telemetry", "false", "boolean")
        val parsed = JsonConfig.json.parseToJsonElement(updated).jsonObject
        assertFalse(parsed["security"]!!.jsonObject["telemetry"]!!.jsonPrimitive.boolean)
        assertEquals(null, parsed["security.telemetry"])
    }

    @Test
    fun preservesStringValuesThatLookNumeric() {
        val updated = updateConfigKey("{}", "voice.provider", "123", "string")
        val value = JsonConfig.json.parseToJsonElement(updated).jsonObject["voice"]!!
            .jsonObject["provider"]!!.jsonPrimitive.content
        assertEquals("123", value)
    }
}
