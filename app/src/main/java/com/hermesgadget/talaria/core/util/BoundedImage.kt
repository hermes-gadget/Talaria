/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hermesgadget.talaria.core.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A bounded reference to a prepared image. It deliberately contains no bytes or bitmap. */
data class ImageHandle(
    val path: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val mimeType: String,
)

internal data class PreparedImage(
    val handle: ImageHandle,
    val filename: String,
)

/**
 * Shared image budgets and bounds-first preparation for picker and artifact media.
 *
 * The source is streamed to a private temporary file so the caller never needs to retain the
 * original payload. Bounds are then decoded before a sampled bitmap is materialised, and the
 * sampled bitmap is re-encoded into the transport/display budget.
 */
internal object BoundedImage {
    const val MAX_SOURCE_BYTES: Long = 25L * 1024L * 1024L
    const val MAX_TRANSPORT_BYTES: Long = 8L * 1024L * 1024L
    const val MAX_DECODED_PIXELS: Long = 16_000_000L
    const val MAX_DISPLAY_PIXELS: Long = 4_000_000L
    const val MAX_DISPLAY_DIMENSION: Int = 2_048

    suspend fun prepareFromUri(
        resolver: ContentResolver,
        uri: Uri,
        outputDirectory: File,
        displayName: String?,
    ): PreparedImage = withContext(Dispatchers.IO) {
        outputDirectory.mkdirs()
        val source = File.createTempFile("talaria-image-source-", ".partial", outputDirectory)
        try {
            resolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { output ->
                    copyCapped(input, output, MAX_SOURCE_BYTES)
                }
            } ?: error("Could not read the selected image")
            currentCoroutineContext().ensureActive()
            val prepared = prepareFile(source, outputDirectory, displayName)
            currentCoroutineContext().ensureActive()
            prepared
        } finally {
            source.delete()
        }
    }

    /** Prepare a bounded data payload received from a server API. */
    suspend fun prepareBytes(
        bytes: ByteArray,
        outputDirectory: File,
        displayName: String?,
        maxSourceBytes: Long = MAX_SOURCE_BYTES,
    ): PreparedImage = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "Image is empty" }
        require(bytes.size.toLong() <= maxSourceBytes) { "Image is larger than the allowed limit" }
        outputDirectory.mkdirs()
        val source = File.createTempFile("talaria-image-source-", ".partial", outputDirectory)
        try {
            currentCoroutineContext().ensureActive()
            source.outputStream().use { it.write(bytes) }
            currentCoroutineContext().ensureActive()
            val prepared = prepareFile(source, outputDirectory, displayName)
            currentCoroutineContext().ensureActive()
            prepared
        } finally {
            source.delete()
        }
    }

    /** Probe dimensions without allocating pixel storage. Useful for deterministic boundary tests. */
    fun probeDimensions(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.isNotEmpty()) { "Image is empty" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        validateBounds(width, height)
        return width to height
    }

    fun validateBounds(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Image dimensions could not be read" }
        val pixels = width.toLong() * height.toLong()
        require(pixels <= MAX_DECODED_PIXELS) {
            "Image has too many decoded pixels"
        }
    }

    fun sampleSizeFor(width: Int, height: Int): Int {
        validateBounds(width, height)
        var sample = 1
        while (ceilDivide(width, sample).toLong() * ceilDivide(height, sample) > MAX_DISPLAY_PIXELS ||
            maxOf(ceilDivide(width, sample), ceilDivide(height, sample)) > MAX_DISPLAY_DIMENSION
        ) {
            if (sample > (1 shl 29)) return Int.MAX_VALUE
            sample = sample shl 1
        }
        return sample
    }

    fun delete(handle: ImageHandle?) {
        handle?.let { File(it.path).delete() }
    }

    private fun prepareFile(
        source: File,
        outputDirectory: File,
        displayName: String?,
    ): PreparedImage {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, boundsOptions)
        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        validateBounds(width, height)

        val sample = sampleSizeFor(width, height)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, decodeOptions)
            ?: error("Image could not be decoded")
        try {
            val decodedPixels = bitmap.width.toLong() * bitmap.height.toLong()
            require(decodedPixels <= MAX_DISPLAY_PIXELS) {
                "Image has too many display pixels"
            }
            require(maxOf(bitmap.width, bitmap.height) <= MAX_DISPLAY_DIMENSION) {
                "Image dimensions exceed the display budget"
            }

            val output = File.createTempFile("talaria-image-", ".jpg", outputDirectory)
            var encoded = false
            try {
                for (quality in intArrayOf(85, 70, 55, 40)) {
                    output.delete()
                    try {
                        FileOutputStream(output).use { fileOutput ->
                            CappedOutputStream(fileOutput, MAX_TRANSPORT_BYTES).use { capped ->
                                encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, capped)
                            }
                        }
                    } catch (_: IllegalArgumentException) {
                        encoded = false
                    }
                    if (encoded && output.length() <= MAX_TRANSPORT_BYTES) break
                    encoded = false
                }
                require(encoded && output.length() > 0L) {
                    "Image could not be re-encoded within the transport budget"
                }
                return PreparedImage(
                    handle = ImageHandle(
                        path = output.absolutePath,
                        width = bitmap.width,
                        height = bitmap.height,
                        sizeBytes = output.length(),
                        mimeType = "image/jpeg",
                    ),
                    filename = safeFilename(displayName),
                )
            } catch (failure: Throwable) {
                output.delete()
                throw failure
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun copyCapped(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(16 * 1024)
        var copied = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(copied <= maxBytes - count) {
                "Image is larger than ${maxBytes / (1024L * 1024L)} MB"
            }
            output.write(buffer, 0, count)
            copied += count
        }
        require(copied > 0L) { "Image is empty" }
    }

    private fun ceilDivide(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun safeFilename(displayName: String?): String {
        val base = displayName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            ?.trim(' ', '.')
            ?.take(120)
            ?.takeIf { it.isNotBlank() }
            ?: "image.jpg"
        val stem = base.substringBeforeLast('.', base).ifBlank { "image" }
        return "$stem.jpg"
    }

    private class CappedOutputStream(
        output: OutputStream,
        private val maxBytes: Long,
    ) : FilterOutputStream(output) {
        private var written = 0L

        override fun write(byteArray: ByteArray, offset: Int, length: Int) {
            require(written <= maxBytes - length) { "Image exceeds the transport budget" }
            out.write(byteArray, offset, length)
            written += length
        }

        override fun write(oneByte: Int) {
            require(written < maxBytes) { "Image exceeds the transport budget" }
            out.write(oneByte)
            written++
        }
    }
}
