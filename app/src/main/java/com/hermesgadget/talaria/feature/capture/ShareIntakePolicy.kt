/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.capture

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable

/** The bounded, content-addressed rules for Android share intake. */
internal object ShareIntakePolicy {
    const val MAX_ITEMS = 12
    const val MAX_ITEM_BYTES = 16L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 32L * 1024L * 1024L
    const val MAX_TEXT_CHARS = 64 * 1024
    const val MAX_SUBJECT_CHARS = 512
    const val MAX_INSTRUCTION_CHARS = 4 * 1024
    const val MAX_FILENAME_CHARS = 120
    const val MAX_URI_CHARS = 4 * 1024
    const val MAX_SNIFF_BYTES = 64 * 1024

    val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    fun normalizeUri(uri: String): String {
        val normalized = uri.trim()
        require(normalized.length <= MAX_URI_CHARS) { "Shared URI is too long" }
        return normalized
    }

    fun remainingBytes(existingBytes: Long): Long =
        (MAX_TOTAL_BYTES - existingBytes).coerceAtLeast(0L)

    fun checkItemBudget(itemCount: Int, existingBytes: Long, itemBytes: Long) {
        require(itemCount < MAX_ITEMS) { "You can share up to $MAX_ITEMS items at once" }
        require(itemBytes in 1..MAX_ITEM_BYTES) {
            "Each shared file must be between 1 byte and ${MAX_ITEM_BYTES / (1024 * 1024)} MB"
        }
        require(existingBytes <= MAX_TOTAL_BYTES - itemBytes) {
            "Shared files exceed the ${MAX_TOTAL_BYTES / (1024 * 1024)} MB total limit"
        }
    }

    fun checkText(text: String) {
        require(text.length <= MAX_TEXT_CHARS) {
            "Shared text is larger than ${MAX_TEXT_CHARS / 1024} KB"
        }
    }

    fun checkInstruction(instruction: String) {
        require(instruction.length <= MAX_INSTRUCTION_CHARS) {
            "Instruction is larger than ${MAX_INSTRUCTION_CHARS / 1024} KB"
        }
    }

    fun safeFilename(candidate: String?, fallback: String = "shared-file"): String {
        val clean = candidate.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim(' ', '.')
            .take(MAX_FILENAME_CHARS)
            .takeIf { it.isNotBlank() }
        return clean ?: fallback
    }

    fun suffixFor(filename: String): String =
        filename.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,12}")) }
            ?.let { ".${it}" }
            ?: ".bin"

    /**
     * Classifies a bounded prefix. MIME and filename claims are advisory; a
     * declared image/PDF must have the matching signature before it is kept.
     */
    fun classify(
        filename: String,
        declaredMimeType: String?,
        prefix: ByteArray,
    ): ClassifiedShareFile {
        val mime = declaredMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it != "*/*" }
            ?: "application/octet-stream"
        val extension = filename.substringAfterLast('.', "").lowercase()
        val signature = signatureOf(prefix)
        val claimsImage = mime.startsWith("image/") || extension in imageExtensions
        val claimsPdf = mime == "application/pdf" || extension == "pdf"

        if (claimsImage) {
            val image = signature?.takeIf { it.kind == ShareItemKind.IMAGE }
                ?: throw IllegalArgumentException("Image MIME or extension does not match its bytes")
            if (mime.startsWith("image/") && mime != "image/*" && mime != image.mimeType) {
                throw IllegalArgumentException("Image MIME type does not match its signature")
            }
            if (extension in imageExtensions && extension !in image.extensions) {
                throw IllegalArgumentException("Image filename extension does not match its signature")
            }
            return ClassifiedShareFile(ShareItemKind.IMAGE, image.mimeType)
        }

        if (claimsPdf) {
            if (signature?.kind != ShareItemKind.DOCUMENT || signature.mimeType != "application/pdf") {
                throw IllegalArgumentException("PDF MIME or extension does not match its bytes")
            }
            if (mime != "application/pdf" && mime != "application/octet-stream") {
                throw IllegalArgumentException("PDF MIME type does not match its signature")
            }
            return ClassifiedShareFile(ShareItemKind.DOCUMENT, "application/pdf")
        }

        signature?.let {
            if (it.kind == ShareItemKind.IMAGE && (mime.startsWith("text/") || mime == "application/pdf")) {
                throw IllegalArgumentException("Declared MIME type does not match the image bytes")
            }
            if (it.mimeType == "application/pdf" && mime.startsWith("text/")) {
                throw IllegalArgumentException("Declared MIME type does not match the PDF bytes")
            }
            if (it.kind == ShareItemKind.IMAGE) return ClassifiedShareFile(ShareItemKind.IMAGE, it.mimeType)
            if (it.mimeType == "application/pdf") return ClassifiedShareFile(ShareItemKind.DOCUMENT, it.mimeType)
        }

        if (mime.startsWith("text/")) {
            require(isUtf8(prefix)) { "Declared text MIME type does not match the file bytes" }
            return ClassifiedShareFile(ShareItemKind.DOCUMENT, mime)
        }
        return ClassifiedShareFile(
            kind = if (mime == "application/pdf") ShareItemKind.DOCUMENT else ShareItemKind.BINARY,
            mimeType = mime,
        )
    }

    fun urlSuggestions(text: String): List<String> {
        val candidate = text.trim()
        if (candidate.isEmpty() || candidate != text || candidate.any(Char::isWhitespace)) {
            return emptyList()
        }
        val url = runCatching { URI(candidate) }.getOrNull() ?: return emptyList()
        if (url.scheme !in setOf("http", "https") || url.host.isNullOrBlank()) return emptyList()
        return listOf("summarize", "compare", "extract")
    }

    private fun isUtf8(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun signatureOf(bytes: ByteArray): Signature? = when {
        bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ->
            Signature("image/png", ShareItemKind.IMAGE, setOf("png"))
        bytes.startsWith(0xff, 0xd8, 0xff) ->
            Signature("image/jpeg", ShareItemKind.IMAGE, setOf("jpg", "jpeg"))
        bytes.asciiStartsWith("GIF87a") || bytes.asciiStartsWith("GIF89a") ->
            Signature("image/gif", ShareItemKind.IMAGE, setOf("gif"))
        bytes.asciiAt(0, "RIFF") && bytes.asciiAt(8, "WEBP") ->
            Signature("image/webp", ShareItemKind.IMAGE, setOf("webp"))
        bytes.asciiStartsWith("BM") ->
            Signature("image/bmp", ShareItemKind.IMAGE, setOf("bmp"))
        bytes.asciiStartsWith("%PDF-") ->
            Signature("application/pdf", ShareItemKind.DOCUMENT, setOf("pdf"))
        else -> null
    }

    private data class Signature(
        val mimeType: String,
        val kind: ShareItemKind,
        val extensions: Set<String>,
    )

    private fun ByteArray.startsWith(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { this[it].toInt() and 0xff == values[it] }

    private fun ByteArray.asciiStartsWith(value: String): Boolean = asciiAt(0, value)

    private fun ByteArray.asciiAt(offset: Int, value: String): Boolean =
        size >= offset + value.length && value.indices.all { this[offset + it].toInt() == value[it].code }
}

@Serializable
enum class ShareItemKind {
    IMAGE,
    DOCUMENT,
    BINARY,
}

internal data class ClassifiedShareFile(
    val kind: ShareItemKind,
    val mimeType: String,
)

internal data class ShareIntentPayload(
    val action: String?,
    val mimeType: String?,
    val text: String,
    val subject: String,
    val uriStrings: List<String>,
)
