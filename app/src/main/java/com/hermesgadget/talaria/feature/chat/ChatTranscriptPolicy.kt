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

/** Raw PTY output is diagnostic-only and must never appear during an active turn. */
internal fun effectiveTranscriptMode(requested: TranscriptMode, working: Boolean): TranscriptMode =
    if (working) TranscriptMode.READING else requested

/** Reading mode contains committed messages only; assistant streams are never user-visible. */
internal fun visibleTranscriptLines(tab: ChatTab?, mode: TranscriptMode): List<ChatLine> {
    if (tab == null) return emptyList()
    if (mode == TranscriptMode.READING) return tab.readingMessages
    if (tab.streamingText.isEmpty()) return tab.lines
    return tab.lines + ChatLine(
        id = "streaming-${tab.id}",
        role = "assistant",
        text = tab.streamingText,
    )
}
