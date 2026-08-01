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

package com.nousresearch.talaria.feature.chat

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class ValidatedChatImage(
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/** Validation shared by picker ingestion and tests; mirrors Hermes' attach-bytes limits. */
internal object ChatImageAttachments {
    const val MAX_BYTES: Int = 25 * 1024 * 1024

    fun readCapped(input: InputStream, maxBytes: Int = MAX_BYTES): ByteArray {
        require(maxBytes > 0)
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("Image is larger than ${maxBytes / (1024 * 1024)} MB")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun validate(bytes: ByteArray, filename: String?, declaredMimeType: String?): ValidatedChatImage {
        require(bytes.isNotEmpty()) { "Image is empty" }
        require(bytes.size <= MAX_BYTES) { "Image is larger than ${MAX_BYTES / (1024 * 1024)} MB" }

        val detected = detectType(bytes)
            ?: throw IllegalArgumentException("Unsupported image type. Choose PNG, JPEG, GIF, WebP, or BMP.")
        val safeName = filename
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            ?.trim(' ', '.')
            ?.take(120)
            ?.takeIf { it.isNotBlank() }
            ?: "image.${detected.second}"
        val nameWithExtension = if (safeName.substringAfterLast('.', "").lowercase() in detected.acceptedExtensions) {
            safeName
        } else {
            "${safeName.substringBeforeLast('.', safeName)}.${detected.second}"
        }

        // ContentResolver MIME values are advisory. Magic bytes are authoritative,
        // but retain a matching declared subtype when Hermes supports it.
        val mime = declaredMimeType
            ?.lowercase()
            ?.takeIf { it == detected.first }
            ?: detected.first
        return ValidatedChatImage(nameWithExtension, mime, bytes)
    }

    private data class Type(
        val first: String,
        val second: String,
        val acceptedExtensions: Set<String>,
    )

    private fun detectType(bytes: ByteArray): Type? = when {
        bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ->
            Type("image/png", "png", setOf("png"))
        bytes.startsWith(0xff, 0xd8, 0xff) ->
            Type("image/jpeg", "jpg", setOf("jpg", "jpeg"))
        bytes.asciiStartsWith("GIF87a") || bytes.asciiStartsWith("GIF89a") ->
            Type("image/gif", "gif", setOf("gif"))
        bytes.asciiStartsWith("RIFF") && bytes.asciiAt(8, "WEBP") ->
            Type("image/webp", "webp", setOf("webp"))
        bytes.asciiStartsWith("BM") ->
            Type("image/bmp", "bmp", setOf("bmp"))
        else -> null
    }

    private fun ByteArray.startsWith(vararg signature: Int): Boolean =
        size >= signature.size && signature.indices.all { this[it].toInt() and 0xff == signature[it] }

    private fun ByteArray.asciiStartsWith(value: String): Boolean = asciiAt(0, value)

    private fun ByteArray.asciiAt(offset: Int, value: String): Boolean =
        size >= offset + value.length && value.indices.all { this[offset + it].toInt() == value[it].code }
}
