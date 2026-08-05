/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hermesgadget.talaria.domain.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.ArrayDeque

/** Accepts either an old ISO/text value or the numeric epoch values emitted by current Hermes. */
@OptIn(ExperimentalSerializationApi::class)
object FlexiblePrimitiveStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexiblePrimitiveString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement()
            ?: return decoder.decodeString()
        return (element as? JsonPrimitive)?.contentOrNull
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(value?.let(::JsonPrimitive) ?: JsonNull)
        } else if (value != null) {
            encoder.encodeString(value)
        } else {
            encoder.encodeNull()
        }
    }
}

/**
 * Hermes transcripts historically used strings, but current multimodal messages can contain
 * OpenAI-style content blocks. Preserve readable text without rejecting the entire transcript.
 */
object FlexibleMessageTextSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleMessageText", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement()
            ?: return decoder.decodeString()
        return element.toReadableMessageText()
    }

    override fun serialize(encoder: Encoder, value: String?) {
        FlexiblePrimitiveStringSerializer.serialize(encoder, value)
    }
}

private const val MAX_READABLE_MESSAGE_DEPTH = 16
private const val MAX_READABLE_MESSAGE_NODES = 4_096
private const val MAX_READABLE_MESSAGE_CHARS = 64 * 1024

private data class ReadableVisit(
    val element: JsonElement,
    val depth: Int,
    val fromArray: Boolean,
)

/** Bounded, iterative projection of multimodal JSON into transcript text. */
private fun JsonElement.toReadableMessageText(): String? {
    val pending = ArrayDeque<ReadableVisit>()
    val segments = mutableListOf<String>()
    var nodes = 0
    var outputChars = 0
    var truncated = false
    pending.addLast(ReadableVisit(this, depth = 0, fromArray = false))

    fun appendSegment(value: String?) {
        if (value.isNullOrBlank() || truncated) return
        val remaining = MAX_READABLE_MESSAGE_CHARS - outputChars
        if (remaining <= 0) {
            truncated = true
            return
        }
        val bounded = value.take(remaining)
        segments += bounded
        outputChars += bounded.length
        if (bounded.length < value.length) truncated = true
    }

    while (pending.isNotEmpty() && !truncated) {
        val visit = pending.removeLast()
        if (++nodes > MAX_READABLE_MESSAGE_NODES || visit.depth > MAX_READABLE_MESSAGE_DEPTH) break
        when (val element = visit.element) {
            JsonNull -> Unit
            is JsonPrimitive -> appendSegment(element.contentOrNull)
            is JsonArray -> {
                val childCount = minOf(element.size, MAX_READABLE_MESSAGE_NODES - nodes - pending.size)
                    .coerceAtLeast(0)
                for (index in (childCount - 1 downTo 0)) {
                    pending.addLast(
                        ReadableVisit(
                            element = element[index],
                            depth = visit.depth + 1,
                            fromArray = true,
                        ),
                    )
                }
            }
            is JsonObject -> {
                val text = element["text"]
                val content = element["content"]
                val result = element["result"]
                val selected = sequenceOf(text, content, result)
                    .filterNotNull()
                    .firstOrNull { it !is JsonNull }
                if (selected != null && nodes + pending.size < MAX_READABLE_MESSAGE_NODES) {
                    pending.addLast(
                        ReadableVisit(
                            element = selected,
                            depth = visit.depth + 1,
                            fromArray = visit.fromArray,
                        ),
                    )
                } else if (visit.fromArray) {
                    when (element["type"]?.jsonPrimitive?.contentOrNull) {
                        "image", "image_url", "input_image" -> appendSegment("[image]")
                        "audio", "input_audio" -> appendSegment("[audio]")
                        else -> Unit
                    }
                } else {
                    appendSegment(boundedObjectPreview(element))
                }
            }
        }
    }
    return segments.joinToString("\n").ifBlank { null }
}

private fun boundedObjectPreview(element: JsonObject): String = buildString {
    append('{')
    element.entries.take(8).forEachIndexed { index, (key, value) ->
        if (index > 0) append(", ")
        append(key.take(128)).append(':').append(boundedJsonValuePreview(value))
    }
    if (element.size > 8) append(", …")
    append('}')
}

private fun boundedJsonValuePreview(value: JsonElement): String = when (value) {
    JsonNull -> "null"
    is JsonPrimitive -> value.contentOrNull?.take(256) ?: "?"
    is JsonArray -> "[…]"
    is JsonObject -> "{…}"
}
