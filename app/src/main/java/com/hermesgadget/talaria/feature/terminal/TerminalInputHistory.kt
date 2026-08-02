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

/** ↑/↓ navigation for commands submitted through the terminal line. */
internal class TerminalInputHistory(
    initialEntries: List<String> = emptyList(),
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val entries = initialEntries
        .map(String::trim)
        .filter(String::isNotEmpty)
        .takeLast(maxEntries)
        .toMutableList()
    private var cursor = entries.size
    private var draftBeforeNavigation = ""

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    val snapshot: List<String>
        get() = entries.toList()

    fun record(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        entries += trimmed
        while (entries.size > maxEntries) entries.removeAt(0)
        resetNavigation()
    }

    fun previous(currentLine: String): String? {
        if (entries.isEmpty()) return null
        if (cursor == entries.size) draftBeforeNavigation = currentLine
        cursor = (cursor - 1).coerceAtLeast(0)
        return entries[cursor]
    }

    fun next(): String? {
        if (entries.isEmpty()) return null
        if (cursor < entries.lastIndex) {
            cursor += 1
            return entries[cursor]
        }
        if (cursor == entries.lastIndex) {
            cursor = entries.size
            return draftBeforeNavigation
        }
        return null
    }

    /** Reset the cursor when the user edits the line rather than navigating history. */
    fun onManualEdit() {
        resetNavigation()
    }

    private fun resetNavigation() {
        cursor = entries.size
        draftBeforeNavigation = ""
    }

    companion object {
        const val MAX_ENTRIES = 50
    }
}
