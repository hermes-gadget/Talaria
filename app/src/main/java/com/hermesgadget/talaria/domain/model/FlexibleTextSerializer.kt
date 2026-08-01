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

private fun JsonElement.toReadableMessageText(): String? = when (this) {
    JsonNull -> null
    is JsonPrimitive -> contentOrNull
    is JsonArray -> mapNotNull { block ->
        when (block) {
            is JsonObject -> {
                block["text"]?.toReadableMessageText()
                    ?: block["content"]?.toReadableMessageText()
                    ?: when (block["type"]?.jsonPrimitive?.contentOrNull) {
                        "image", "image_url", "input_image" -> "[image]"
                        "audio", "input_audio" -> "[audio]"
                        else -> null
                    }
            }
            else -> block.toReadableMessageText()
        }
    }.joinToString("\n").ifBlank { null }
    is JsonObject -> {
        this["text"]?.toReadableMessageText()
            ?: this["content"]?.toReadableMessageText()
            ?: this["result"]?.toReadableMessageText()
            ?: toString()
    }
}
