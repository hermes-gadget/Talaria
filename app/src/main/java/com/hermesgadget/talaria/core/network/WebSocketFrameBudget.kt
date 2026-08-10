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

import okio.ByteString

/**
 * Shared ingress limits applied before JSON, UTF-8, ANSI, or UI conversion.
 *
 * OkHttp 4.12's public WebSocket API has no inbound frame/message limit and no
 * streaming listener: [okhttp3.WebSocketListener.onMessage] is called only
 * after OkHttp has assembled the complete text or binary message. These
 * predicates are therefore the earliest application-side guard available with
 * the current dependency. They deliberately do no secondary copy or decode.
 *
 * Residual risk: a peer can still make OkHttp allocate a complete message
 * larger than this budget before the callback runs. Closing with 1009 here
 * limits subsequent processing and retention, but cannot provide a hard
 * pre-allocation bound. A future OkHttp API with streaming inbound frames (or
 * a client fork/adapter that exposes the frame reader) is required to remove
 * that transport-level risk; the server/proxy must enforce the same ceiling as
 * defense in depth.
 */
object WebSocketFrameBudget {
    const val MAX_FRAME_BYTES = 256 * 1024
    const val MESSAGE_TOO_BIG_CLOSE_CODE = 1009

    /** Measure UTF-8 without allocating a second byte array. */
    fun textWithinLimit(text: String, maxBytes: Int = MAX_FRAME_BYTES): Boolean {
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (text.length > maxBytes) return false

        var bytes = 0
        var index = 0
        while (index < text.length) {
            val character = text[index]
            val encodedBytes = when {
                character <= '\u007f' -> 1
                character <= '\u07ff' -> 2
                character.isHighSurrogate() && index + 1 < text.length &&
                    text[index + 1].isLowSurrogate() -> {
                    index++
                    4
                }
                character.isHighSurrogate() || character.isLowSurrogate() -> 1
                else -> 3
            }
            if (bytes > maxBytes - encodedBytes) return false
            bytes += encodedBytes
            index++
        }
        return true
    }

    fun binaryWithinLimit(bytes: ByteString, maxBytes: Int = MAX_FRAME_BYTES): Boolean {
        require(maxBytes > 0) { "maxBytes must be positive" }
        return bytes.size <= maxBytes
    }
}
