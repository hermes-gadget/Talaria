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

package com.hermesgadget.talaria.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException
import kotlin.math.min

/** A stable, user-visible failure for a response that exceeded its transport budget. */
class ResponseTooLargeException(
    val path: String,
    val limitBytes: Long,
    val observedBytes: Long? = null,
    val declaredBytes: Long? = null,
) : IOException(
    buildString {
        append("Response too large")
        append(" for ")
        append(path)
        append(" (limit ")
        append(formatResponseBytes(limitBytes))
        if (declaredBytes != null) {
            append(", declared ")
            append(formatResponseBytes(declaredBytes))
        } else if (observedBytes != null) {
            append(", read ")
            append(formatResponseBytes(observedBytes))
        }
        append(')')
    },
)

/**
 * Rejects large declared REST bodies and counts bytes for chunked/unknown bodies.
 *
 * The wrapper sits above OkHttp's transparent decompression, so a compressed
 * response is bounded by its decompressed bytes too. It also covers Retrofit
 * converters and raw [ResponseBody] consumers using the same client.
 */
class ResponseBodyLimitInterceptor(
    private val defaultLimitBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    private val endpointLimits: List<EndpointResponseLimit> = DEFAULT_ENDPOINT_LIMITS,
) : Interceptor {
    init {
        require(defaultLimitBytes > 0L) { "defaultLimitBytes must be positive" }
        require(endpointLimits.all { it.limitBytes > 0L }) {
            "endpoint limits must be positive"
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val path = chain.request().url.encodedPath
        val limit = limitFor(path)
        val body = response.body ?: return response
        val declared = body.contentLength()
        if (declared > limit) {
            body.close()
            throw ResponseTooLargeException(
                path = path,
                limitBytes = limit,
                declaredBytes = declared,
            )
        }
        return response.newBuilder()
            .body(LimitedResponseBody(body, path, limit))
            .build()
    }

    internal fun limitFor(path: String): Long = endpointLimits
        .firstOrNull { it.matches(path) }
        ?.limitBytes
        ?: defaultLimitBytes

    data class EndpointResponseLimit(
        val path: String,
        val limitBytes: Long,
        val prefix: Boolean = false,
    ) {
        fun matches(candidate: String): Boolean = if (prefix) {
            candidate.startsWith(path)
        } else {
            candidate == path
        }
    }

    private class LimitedResponseBody(
        private val delegate: ResponseBody,
        private val path: String,
        private val limitBytes: Long,
    ) : ResponseBody() {
        private val boundedSource: BufferedSource by lazy {
            LimitedSource(delegate.source(), path, limitBytes).buffer()
        }

        override fun contentType() = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun source(): BufferedSource = boundedSource
    }

    private class LimitedSource(
        delegate: Source,
        private val path: String,
        private val limitBytes: Long,
    ) : ForwardingSource(delegate) {
        private var bytesRead = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (byteCount == 0L) return 0L
            val remaining = limitBytes - bytesRead
            if (remaining > 0L) {
                val read = super.read(sink, min(byteCount, remaining))
                if (read > 0L) bytesRead += read
                return read
            }

            // Probe one byte into a private buffer so an exactly-at-limit body
            // can still report EOF, while a body with more data is aborted
            // without exposing a byte beyond the configured ceiling.
            val probe = Buffer()
            val extra = super.read(probe, 1L)
            if (extra == -1L) return -1L
            close()
            throw ResponseTooLargeException(
                path = path,
                limitBytes = limitBytes,
                observedBytes = bytesRead + extra,
            )
        }
    }

    companion object {
        /** Conservative cap for ordinary JSON endpoints. */
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 2L * 1024L * 1024L

        private const val MIB = 1024L * 1024L

        /**
         * Larger ceilings are explicit because these routes intentionally carry
         * base64 media, transcript history, graphs, or streamed archives.
         */
        val DEFAULT_ENDPOINT_LIMITS: List<EndpointResponseLimit> = listOf(
            EndpointResponseLimit("/api/sessions/", 8L * MIB, prefix = true),
            EndpointResponseLimit("/api/sessions", 4L * MIB),
            EndpointResponseLimit("/api/fs/read-data-url", 24L * MIB),
            EndpointResponseLimit("/api/files/read", 24L * MIB),
            EndpointResponseLimit("/api/media", 24L * MIB),
            EndpointResponseLimit("/api/learning/graph", 8L * MIB),
            EndpointResponseLimit("/api/audio/speak", 13L * MIB),
            EndpointResponseLimit("/api/audio/transcribe", 4L * MIB),
            EndpointResponseLimit("/api/files/download", 64L * MIB),
            EndpointResponseLimit("/api/ops/backup/download", 64L * MIB),
        )
    }
}

private fun formatResponseBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L && bytes % (1024L * 1024L) == 0L ->
        "${bytes / (1024L * 1024L)} MiB"
    else -> "$bytes bytes"
}

/** Decode a streamed JSON response after the transport wrapper has bounded it. */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> ResponseBody.decodeJsonResponse(): T = use {
    JsonConfig.json.decodeFromStream<T>(byteStream())
}
