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

import android.content.Context
import androidx.core.content.edit
import com.hermesgadget.talaria.core.network.JsonConfig
import kotlinx.serialization.encodeToString
import com.hermesgadget.talaria.core.util.suspendResult

/** Immutable queue operations used by the ViewModel and unit tests. */
internal object ComposerQueue {
    fun enqueue(queue: List<String>, prompt: String): List<String> {
        val trimmed = prompt.trim()
        return if (trimmed.isEmpty()) queue else queue + trimmed
    }

    fun dequeue(queue: List<String>): Pair<String?, List<String>> =
        queue.firstOrNull()?.let { it to queue.drop(1) } ?: (null to queue)
}

/**
 * Per-session ↑/↓ navigation. The draft that was present before navigation is
 * restored after the user walks past the newest history item.
 */
internal class InputHistoryNavigator(
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

    val snapshot: List<String> get() = entries.toList()

    fun record(draft: String) {
        val trimmed = draft.trim()
        if (trimmed.isEmpty()) return
        entries += trimmed
        while (entries.size > maxEntries) entries.removeAt(0)
        resetNavigation()
    }

    /** Call when the user types or otherwise edits the composer directly. */
    fun onManualEdit() {
        resetNavigation()
    }

    fun previous(currentDraft: String): String? {
        if (entries.isEmpty()) return null
        if (cursor == entries.size) draftBeforeNavigation = currentDraft
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

    private fun resetNavigation() {
        cursor = entries.size
        draftBeforeNavigation = ""
    }

    companion object {
        const val MAX_ENTRIES = 50
    }
}

/** Versioned, unambiguous namespace for persisted composer history. */
internal object ChatInputHistoryKey {
    fun forSession(
        connectionId: String,
        managementProfile: String,
        sessionId: String,
    ): String = buildString {
        append("v2:")
        listOf(connectionId, managementProfile, sessionId).forEach { component ->
            append(component.length)
            append(':')
            append(component)
        }
    }
}

/**
 * History persistence follows SettingsStore's SharedPreferences/JSON pattern.
 * It intentionally lives in feature/chat because core/ is owned by another lane.
 */
internal class ChatInputHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(sessionKey: String): List<String> {
        val raw = prefs.getString(key(sessionKey), null) ?: return emptyList()
        return runCatching {
            JsonConfig.json.decodeFromString<List<String>>(raw)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .takeLast(InputHistoryNavigator.MAX_ENTRIES)
        }.getOrDefault(emptyList())
    }

    fun save(sessionKey: String, entries: List<String>) {
        prefs.edit {
            putString(
                key(sessionKey),
                JsonConfig.json.encodeToString(entries.takeLast(InputHistoryNavigator.MAX_ENTRIES)),
            )
        }
    }

    private fun key(sessionKey: String): String = "chat_input_history_$sessionKey"

    companion object {
        private const val PREFS_NAME = "talaria_settings"
    }
}
