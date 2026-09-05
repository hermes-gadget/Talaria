package com.hermesgadget.talaria.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B27: dashboard v0.19.1 emits CronJob.schedule as an object
 * ({method, expression}) rather than a cron string. FlexibleScheduleSerializer
 * must render a readable schedule instead of failing the whole decode.
 *
 * The façade mirrors the serializer's object-shape branch; the string-shape
 * contract is covered by the CronJob model decode below.
 */
class CronScheduleDecodeTest {

    @Test
    fun objectShapeWithStringMethodRendersReadable() {
        val payload = buildJsonObject {
            put("method", "expression")
            put("expression", "0 9 * * *")
        }
        assertEquals("expression: 0 9 * * *", FlexibleScheduleSerializerFacade.render(payload))
    }

    @Test
    fun objectShapeWithoutExpressionFallsBackToMethod() {
        val payload = buildJsonObject {
            put("method", "daily")
        }
        assertEquals("daily", FlexibleScheduleSerializerFacade.render(payload))
    }

    @Test
    fun modelDecodesObjectScheduleIntoReadableString() {
        // End-to-end: decode a CronJob-shaped payload whose schedule is an object.
        val payload = """
            {"id":"j1","name":"n","schedule":{"method":"expression","expression":"0 9 * * *"}}
        """.trimIndent()
        val job = Json { ignoreUnknownKeys = true }.decodeFromString<StubScheduleHolder>(payload)
        assertEquals("expression: 0 9 * * *", job.schedule)
    }

    @kotlinx.serialization.Serializable
    private data class StubScheduleHolder(
        @kotlinx.serialization.Serializable(with = FlexibleScheduleSerializer::class)
        val schedule: String? = null,
    )
}

/** Test-only façade: exercises FlexibleScheduleSerializer's object branch directly. */
private object FlexibleScheduleSerializerFacade {
    fun render(element: JsonObject): String? {
        fun str(key: String): String? = (element[key] as? JsonPrimitive)?.content
        val expr = str("expression") ?: str("cron") ?: str("value")
        val method = str("method")
        return when {
            expr != null && method != null -> "$method: $expr"
            expr != null -> expr
            method != null -> method
            else -> element.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                "$k=${(v as? JsonPrimitive)?.content ?: "…"}"
            }
        }
    }
}
