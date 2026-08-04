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

package com.hermesgadget.talaria.core.util

/** A bounded answer/output buffer with a small, non-reportable diagnostic tail. */
class BoundedTextBuffer(
    private val maxChars: Int,
    private val diagnosticTailChars: Int = DEFAULT_DIAGNOSTIC_TAIL_CHARS,
) {
    private val buffer = StringBuilder()
    private val tail = StringBuilder()
    private var droppedChars = 0L

    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(diagnosticTailChars > 0) { "diagnosticTailChars must be positive" }
    }

    val text: String
        get() = buffer.toString()

    val diagnosticTail: String
        get() = tail.toString()

    val isTruncated: Boolean
        get() = droppedChars > 0

    val droppedCharCount: Long
        get() = droppedChars

    fun append(value: CharSequence) {
        if (value.isEmpty()) return
        val remaining = maxChars - buffer.length
        val accepted = remaining.coerceAtLeast(0).coerceAtMost(value.length)
        if (accepted > 0) buffer.append(value, 0, accepted)
        if (accepted < value.length) {
            droppedChars += (value.length - accepted).toLong()
            retainTail(value, accepted)
        }
    }

    /** Return the retained prefix plus a compact marker when any suffix was omitted. */
    fun displayText(marker: String): String {
        if (!isTruncated) return text
        return buildString(text.length + marker.length + 2) {
            append(text)
            if (isNotEmpty()) append("\n\n")
            append(marker)
        }
    }

    fun clear() {
        buffer.setLength(0)
        tail.setLength(0)
        droppedChars = 0L
    }

    private fun retainTail(value: CharSequence, start: Int) {
        val tailStart = maxOf(start, value.length - diagnosticTailChars)
        tail.append(value, tailStart, value.length)
        if (tail.length > diagnosticTailChars) {
            tail.delete(0, tail.length - diagnosticTailChars)
        }
    }

    companion object {
        const val DEFAULT_DIAGNOSTIC_TAIL_CHARS = 256
    }
}
