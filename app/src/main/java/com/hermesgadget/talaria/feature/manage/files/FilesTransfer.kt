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

/** Keep small uploads in the JSON data-URL request; stream larger files instead. */
internal const val INLINE_UPLOAD_LIMIT_BYTES = 2L * 1024L * 1024L

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
    val input = resolver.openInputStream(uri) ?: error("")
    return input.use { source ->
        ByteArrayOutputStream(
            expectedLength.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: 16 * 1024,
        ).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var copied = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                copied += count
                onProgress(copied, expectedLength)
            }
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
            var copied = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                sink.write(buffer, 0, count)
                copied += count
                onProgress(copied, length)
            }
        }
    }
}

internal fun joinManagedPath(directory: String, name: String): String {
    val cleanName = name.trim().trim('/')
    require(cleanName.isNotBlank())
    val cleanDirectory = directory.trim()
    return when {
        cleanDirectory.isBlank() || cleanDirectory == "/" -> "/$cleanName"
        else -> "${cleanDirectory.trimEnd('/')}/$cleanName"
    }
}
