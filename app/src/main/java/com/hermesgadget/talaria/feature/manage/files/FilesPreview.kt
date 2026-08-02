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
package com.hermesgadget.talaria.feature.manage.files

import java.util.Base64

enum class FilePreviewType {
    TEXT,
    IMAGE,
    BINARY,
}

internal data class ParsedDataUrl(
    val mimeType: String?,
    val bytes: ByteArray,
)

private val imageMimeTypes = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
)

/** Maps a file name, server MIME type, and binary flag to the pane preview mode. */
internal fun previewTypeFor(
    fileName: String,
    mimeType: String? = null,
    isBinary: Boolean = false,
): FilePreviewType {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    return when {
        mime?.startsWith("image/") == true -> FilePreviewType.IMAGE
        fileExtension(fileName) in imageMimeTypes -> FilePreviewType.IMAGE
        isBinary -> FilePreviewType.BINARY
        else -> FilePreviewType.TEXT
    }
}

/** Returns a useful MIME fallback when `/api/fs` omits it from its response. */
internal fun mimeTypeFor(fileName: String, fallback: String? = null): String {
    val mime = fallback?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    val guessed = imageMimeTypes[fileExtension(fileName)]
    return when {
        mime.isNotBlank() && !(mime == "application/octet-stream" && guessed != null) -> mime
        guessed != null -> guessed
        mime.isNotBlank() -> mime
        else -> "application/octet-stream"
    }
}

/**
 * Parses the base64 data URL returned by `/api/fs/read-data-url`.
 *
 * Hermes currently returns only `dataUrl`, so the MIME type is taken from the
 * data URL prefix and the size is obtained from the decoded bytes.
 */
internal fun parseDataUrl(dataUrl: String): ParsedDataUrl {
    val value = dataUrl.trim()
    require(value.startsWith("data:", ignoreCase = true)) { "Expected a data URL" }

    val comma = value.indexOf(',')
    require(comma > "data:".length) { "Data URL is missing its payload" }

    val metadata = value.substring("data:".length, comma)
    val parts = metadata.split(';')
    require(parts.any { it.equals("base64", ignoreCase = true) }) {
        "Data URL is not base64 encoded"
    }

    val mimeType = parts.firstOrNull()
        ?.trim()
        ?.takeIf { it.contains('/') }
        ?.lowercase()
    val encoded = value.substring(comma + 1).filterNot(Char::isWhitespace)
    val bytes = try {
        Base64.getDecoder().decode(encoded)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Data URL contains invalid base64", error)
    }
    return ParsedDataUrl(mimeType = mimeType, bytes = bytes)
}

internal fun dataUrlFor(bytes: ByteArray, mimeType: String): String =
    "data:${mimeType.substringBefore(';').ifBlank { "application/octet-stream" }};base64," +
        Base64.getEncoder().encodeToString(bytes)

private fun fileExtension(fileName: String): String =
    fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
