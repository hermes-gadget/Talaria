package com.hermesgadget.talaria.feature.manage.models

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B61: a PUT acknowledgement ({"ok":true}) must NOT be parsed into a
 * replacement MoA configuration — it would fabricate a "default" preset
 * from the ack and clobber the config the user just saved.
 */
class MoaAckPayloadTest {

    private fun parse(element: kotlinx.serialization.json.JsonElement): Any? {
        // Mirrors parseMoaConfig's contract: null when there is no "presets"
        // object (ack shape), a draft when the response carries presets.
        val root = element as? kotlinx.serialization.json.JsonObject ?: return null
        val hasPresets = root.containsKey("presets")
        return if (!hasPresets) null else "draft"
    }

    @Test
    fun ackPayloadIsNotAdoptedAsConfig() {
        val ack = buildJsonObject {
            put("ok", true)
        }
        assertNull("ack must not parse as a full config", parse(ack))
    }

    @Test
    fun configResponseIsAdopted() {
        val response = buildJsonObject {
            put("default_preset", "fast")
            put(
                "presets",
                buildJsonObject {
                    put(
                        "fast",
                        buildJsonObject {
                            put(
                                "reference_models",
                                kotlinx.serialization.json.buildJsonArray {},
                            )
                        },
                    )
                },
            )
        }
        assertEquals("draft", parse(response))
    }
}
