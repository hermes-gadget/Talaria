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

package com.hermesgadget.talaria.feature.terminal

import com.hermesgadget.talaria.core.util.AnsiStripper

/**
 * The shared stripper trims chunk-ending whitespace because chat renders
 * complete lines. A terminal still needs to retain chunk-ending newlines, so
 * restore only those delimiters after stripping the control sequences.
 */
internal object TerminalOutputSanitizer {
    fun strip(raw: String): String {
        if (raw.isEmpty()) return ""
        val trailingNewlines = raw.takeLastWhile { it == '\n' }.length
        val stripped = AnsiStripper.strip(raw)
        return if (trailingNewlines == 0) {
            stripped
        } else {
            stripped.trimEnd('\n') + "\n".repeat(trailingNewlines)
        }
    }
}

/** Bounded PTY output so a long-lived terminal cannot grow the Compose state forever. */
internal class TerminalOutputBuffer(
    private val maxChars: Int = MAX_CHARS,
    private val diagnosticTailChars: Int = DIAGNOSTIC_TAIL_CHARS,
) {
    private val buffer = StringBuilder()
    private val diagnosticTail = StringBuilder()
    private var truncated = false
    private var droppedCharCount = 0L

    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(diagnosticTailChars > 0) { "diagnosticTailChars must be positive" }
    }

    val text: String
        get() = buffer.toString()

    /** UI projection that makes omitted earlier output explicit. */
    val displayText: String
        get() = if (!truncated) text else "$TRUNCATION_MARKER\n$text"

    /** Payload is retained only locally for diagnostics; counters never include it. */
    val diagnosticTailText: String
        get() = diagnosticTail.toString()

    val isTruncated: Boolean
        get() = truncated

    val droppedChars: Long
        get() = droppedCharCount

    fun append(raw: String): String {
        val sanitized = TerminalOutputSanitizer.strip(raw)
        buffer.append(sanitized)
        if (buffer.length > maxChars) {
            val removed = buffer.length - maxChars
            retainTail(buffer.substring(0, removed))
            buffer.delete(0, removed)
            droppedCharCount += removed.toLong()
            truncated = true
        }
        return text
    }

    fun clear(): String {
        buffer.setLength(0)
        diagnosticTail.setLength(0)
        truncated = false
        droppedCharCount = 0L
        return ""
    }

    private fun retainTail(removed: String) {
        if (removed.isEmpty()) return
        diagnosticTail.append(removed.takeLast(diagnosticTailChars))
        if (diagnosticTail.length > diagnosticTailChars) {
            diagnosticTail.delete(0, diagnosticTail.length - diagnosticTailChars)
        }
    }

    companion object {
        const val MAX_CHARS = 120_000
        const val DIAGNOSTIC_TAIL_CHARS = 256
        const val TRUNCATION_MARKER = "Earlier terminal output omitted; full history remains on server"
    }
}
