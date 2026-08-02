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

package com.hermesgadget.talaria.feature.chat

import com.hermesgadget.talaria.domain.model.ChatLine
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTranscriptPolicyTest {
    private val committed = ChatLine("user-1", "user", "Do the work")
    private val rawTui = ChatLine("pty-1", "assistant", "thinking... ANSI TUI redraw")

    @Test
    fun activeTurnForcesReadingModeAndHidesRawTui() {
        val tab = tab(working = true)
        val mode = effectiveTranscriptMode(TranscriptMode.TERMINAL, tab.working)

        assertEquals(TranscriptMode.READING, mode)
        assertEquals(listOf(committed), visibleTranscriptLines(tab, mode))
    }

    @Test
    fun idleExplicitTerminalModeStillShowsDiagnosticOutput() {
        val tab = tab(working = false)
        val mode = effectiveTranscriptMode(TranscriptMode.TERMINAL, tab.working)

        assertEquals(TranscriptMode.TERMINAL, mode)
        assertEquals(listOf(rawTui), visibleTranscriptLines(tab, mode))
    }

    @Test
    fun transcriptSearchFiltersCaseInsensitivelyAndCountsMatchingRows() {
        val lines = listOf(
            ChatLine("1", "user", "Build the Android app"),
            ChatLine("2", "assistant", "The build is green"),
            ChatLine("3", "assistant", "No match here"),
        )

        assertEquals(listOf("Build the Android app", "The build is green"), filterTranscriptLines(lines, "BUILD").map { it.text })
        assertEquals(2, transcriptMatchCount(lines, "build"))
        assertEquals(lines, filterTranscriptLines(lines, ""))
    }

    private fun tab(working: Boolean) = ChatTab(
        id = "tab-1",
        title = "Build agent",
        channelId = "channel-1",
        lines = listOf(rawTui),
        readingMessages = listOf(committed),
        working = working,
    )
}
