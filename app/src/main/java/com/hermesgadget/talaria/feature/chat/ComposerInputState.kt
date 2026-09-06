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
    // P06: the queued-prompt list is bounded so a runaway enqueue loop cannot
    // grow memory without limit.
    const val MAX_QUEUE = 100

    fun enqueue(queue: List<String>, prompt: String): List<String> {
        val trimmed = prompt.trim()
        return if (trimmed.isEmpty()) queue else (queue + trimmed).takeLast(MAX_QUEUE)
    }

    fun dequeue(queue: List<String>): Pair<String?, List<String>> =
        queue.firstOrNull()?.let { it to queue.drop(1) } ?: (null to queue)

    /** B38: put a message that failed to launch back at the FRONT. */
    fun requeueFront(queue: List<String>, prompt: String): List<String> {
        val trimmed = prompt.trim()
        return if (trimmed.isEmpty()) queue else listOf(trimmed) + queue
    }
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
        val sanitized = entries
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // P06: individual prompts are capped so one giant paste cannot
            // dominate the persisted history or the shared-preferences file.
            .map { entry -> entry.take(MAX_ENTRY_CHARS) }
            .takeLast(InputHistoryNavigator.MAX_ENTRIES)
        prefs.edit {
            putString(
                key(sessionKey),
                JsonConfig.json.encodeToString(sanitized),
            )
        }
        enforceNamespaceBudget(sanitized.sumOf { it.length }, sessionKey)
    }

    /**
     * P06: the history file is a shared namespace across sessions; when the total
     * persisted bytes exceed the budget, drop the oldest OTHER session history
     * entries (this session's just-saved data always survives).
     */
    private fun enforceNamespaceBudget(ownBytes: Int, justSavedKey: String) {
        val all = prefs.all
        var total = 0L
        val historyKeys = ArrayList<String>()
        for (entry in all.entries) {
            if (entry.key.startsWith(KEY_PREFIX)) {
                historyKeys.add(entry.key)
                total += (entry.value as? String)?.length ?: 0
            }
        }
        if (total <= NAMESPACE_BUDGET_CHARS) return
        // Oldest first: SharedPreferences order is arbitrary, so drop in reverse
        // insertion of keys we know — iterating and removing until under budget.
        for (historyKey in historyKeys) {
            if (total <= NAMESPACE_BUDGET_CHARS) break
            if (historyKey == key(justSavedKey)) continue
            val value = prefs.getString(historyKey, null) ?: continue
            total -= value.length
            prefs.edit { remove(historyKey) }
        }
    }

    private fun key(sessionKey: String): String = "${KEY_PREFIX}$sessionKey"

    companion object {
        private const val PREFS_NAME = "talaria_settings"
        private const val KEY_PREFIX = "chat_input_history_"
        // P06: 4 KB per prompt entry, ~512 KB total namespace ceiling.
        const val MAX_ENTRY_CHARS = 4_096
        const val NAMESPACE_BUDGET_CHARS = 512_000
    }
}
