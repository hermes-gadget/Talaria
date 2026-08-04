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
import java.nio.charset.StandardCharsets

/** Shared ingress limits applied before JSON, UTF-8, ANSI, or UI conversion. */
object WebSocketFrameBudget {
    const val MAX_FRAME_BYTES = 256 * 1024
    const val MESSAGE_TOO_BIG_CLOSE_CODE = 1009

    /** The UTF-8 measurement is bounded by [maxBytes] before the conversion. */
    fun textWithinLimit(text: String, maxBytes: Int = MAX_FRAME_BYTES): Boolean {
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (text.length > maxBytes) return false
        return text.toByteArray(StandardCharsets.UTF_8).size <= maxBytes
    }

    fun binaryWithinLimit(bytes: ByteString, maxBytes: Int = MAX_FRAME_BYTES): Boolean {
        require(maxBytes > 0) { "maxBytes must be positive" }
        return bytes.size <= maxBytes
    }
}
