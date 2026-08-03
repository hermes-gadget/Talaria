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

object AnsiStripper {
    private const val ESC = "\u001B"

    /**
     * Strip one complete value without changing ordinary whitespace.
     *
     * WebSocket messages are not terminal lines: an ANSI escape can be split
     * between two messages. Callers that consume a stream should keep one
     * [Stream] instance for the lifetime of that stream.
     */
    fun strip(input: String): String = Stream().append(input)

    /** Stateful ANSI parser for arbitrarily-fragmented terminal output. */
    class Stream {
        private var state = State.GROUND

        fun append(input: String): String {
            if (input.isEmpty()) return ""
            val output = StringBuilder(input.length)
            input.forEach { character -> consume(character, output) }
            return output.toString()
        }

        private fun consume(character: Char, output: StringBuilder) {
            when (state) {
                State.GROUND -> when {
                    character == ESC[0] -> state = State.ESC
                    character == '\r' -> Unit
                    else -> output.append(character)
                }

                State.ESC -> when {
                    character == '[' -> state = State.CSI
                    character == ']' -> state = State.OSC
                    character == '=' || character == '>' || character == '<' -> {
                        state = State.GROUND
                    }
                    character == ESC[0] -> state = State.ESC
                    else -> {
                        // Unknown two-byte escape: discard the escape prefix,
                        // then process this character as ordinary input rather
                        // than leaking a control sequence into the transcript.
                        state = State.GROUND
                        if (character != '\r') output.append(character)
                    }
                }

                State.CSI -> when {
                    character in CSI_FINALS -> state = State.GROUND
                    character == ESC[0] -> state = State.ESC
                    character == '\n' -> {
                        // A malformed/incomplete CSI must not swallow a real
                        // line break. Resume parsing after preserving it.
                        state = State.GROUND
                        output.append(character)
                    }
                    character == '\r' -> state = State.GROUND
                }

                State.OSC -> when {
                    character == '\u0007' -> state = State.GROUND
                    character == ESC[0] -> state = State.OSC_ESC
                }

                State.OSC_ESC -> when (character) {
                    '\\' -> state = State.GROUND // String Terminator: ESC \\
                    ESC[0] -> state = State.OSC_ESC
                    else -> state = State.OSC
                }
            }
        }

        private enum class State {
            GROUND,
            ESC,
            CSI,
            OSC,
            OSC_ESC,
        }

        private companion object {
            // CSI final bytes are the inclusive range 0x40 ('@')..0x7e ('~').
            private val CSI_FINALS = '\u0040'..'\u007E'
        }
    }
}
