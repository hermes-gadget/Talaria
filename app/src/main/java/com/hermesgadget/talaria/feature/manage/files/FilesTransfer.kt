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

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import com.hermesgadget.talaria.core.util.suspendResult

/** Keep small uploads in the JSON data-URL request; stream larger files instead. */
internal const val INLINE_UPLOAD_LIMIT_BYTES = 2L * 1024L * 1024L
internal const val MAX_MANAGED_PREVIEW_BYTES = 16L * 1024L * 1024L

private const val PROGRESS_MIN_BYTES = 64L * 1024L
private const val PROGRESS_INTERVAL_NANOS = 100L * 1_000_000L

/** Emits transfer progress at a renderable cadence and never drops completion. */
internal class ProgressThrottler(
    private val onProgress: (copied: Long, total: Long) -> Unit,
    private val minBytes: Long = PROGRESS_MIN_BYTES,
    private val intervalNanos: Long = PROGRESS_INTERVAL_NANOS,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private var lastCopied = -1L
    private var lastTotal = Long.MIN_VALUE
    private var lastEmitNanos = Long.MIN_VALUE

    fun report(copied: Long, total: Long) {
        val now = clockNanos()
        val first = lastCopied < 0L
        val terminal = total >= 0L && copied >= total
        val enoughBytes = copied - lastCopied >= minBytes
        val enoughTime = lastEmitNanos == Long.MIN_VALUE ||
            now - lastEmitNanos >= intervalNanos
        if (first || terminal || enoughBytes || enoughTime) {
            emit(copied, total, now)
        }
    }

    fun complete(copied: Long, total: Long) {
        emit(copied, total, clockNanos())
    }

    private fun emit(copied: Long, total: Long, now: Long) {
        if (copied == lastCopied && total == lastTotal && lastEmitNanos != Long.MIN_VALUE) return
        lastCopied = copied
        lastTotal = total
        lastEmitNanos = now
        onProgress(copied, total)
    }
}

internal fun contentDisplayName(resolver: ContentResolver, uri: Uri): String {
    val queriedName = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return queriedName?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: ""
}

internal fun contentLength(resolver: ContentResolver, uri: Uri): Long {
    val descriptorLength = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor -> descriptor.length }
    }.getOrNull()
    if (descriptorLength != null && descriptorLength >= 0L) return descriptorLength

    return runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)
}

internal fun readContent(
    resolver: ContentResolver,
    uri: Uri,
    expectedLength: Long,
    onProgress: (Long, Long) -> Unit,
): ByteArray {
    require(expectedLength < 0L || expectedLength <= INLINE_UPLOAD_LIMIT_BYTES) {
        "Inline upload exceeds the ${INLINE_UPLOAD_LIMIT_BYTES / (1024 * 1024)} MiB limit"
    }
    val input = resolver.openInputStream(uri) ?: error("")
    return input.use { source ->
        ByteArrayOutputStream(
            expectedLength.takeIf { it in 1..INLINE_UPLOAD_LIMIT_BYTES }
                ?.toInt()
                ?: 16 * 1024,
        ).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val progress = ProgressThrottler(onProgress)
            var copied = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                require(copied <= INLINE_UPLOAD_LIMIT_BYTES - count) {
                    "Inline upload exceeds the ${INLINE_UPLOAD_LIMIT_BYTES / (1024 * 1024)} MiB limit"
                }
                output.write(buffer, 0, count)
                copied += count
                progress.report(copied, expectedLength)
            }
            progress.complete(copied, expectedLength)
            output.toByteArray()
        }
    }
}

/** Streams a SAF-backed Uri into Retrofit's multipart request while reporting bytes sent. */
internal class ContentResolverRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val mediaType: MediaType,
    private val length: Long,
    private val onProgress: (Long, Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri) ?: error("")
        input.use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val progress = ProgressThrottler(onProgress)
            var copied = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                sink.write(buffer, 0, count)
                copied += count
                progress.report(copied, length)
            }
            progress.complete(copied, length)
        }
    }
}

internal fun joinManagedPath(directory: String, name: String): String {
    require(name.none { character ->
        character == '/' || character == '\\' || character.isISOControl()
    })
    val cleanName = name.trim()
    require(cleanName.isNotBlank())
    require(cleanName != "." && cleanName != "..")
    val cleanDirectory = directory.trim()
    return when {
        cleanDirectory.isBlank() || cleanDirectory == "/" -> "/$cleanName"
        else -> "${cleanDirectory.trimEnd('/')}/$cleanName"
    }
}
