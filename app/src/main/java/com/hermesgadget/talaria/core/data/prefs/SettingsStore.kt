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


package com.hermesgadget.talaria.core.data.prefs

import android.content.Context
import androidx.core.content.edit
import com.hermesgadget.talaria.core.network.JsonConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
}

/** One persisted Chat tab: its Hermes session (if any) and user-visible title. */
@Serializable
data class PersistedChatTab(val sessionId: String? = null, val title: String)

/** The full Chat surface for a profile: open tabs + which one was focused. */
@Serializable
data class PersistedChatState(
    val tabs: List<PersistedChatTab> = emptyList(),
    val activeSessionId: String? = null,
)

/** One agent turn monitored by the foreground notification service. */
@Serializable
data class PersistedAgentWatch(
    val watcherId: String,
    val agentName: String,
    val channelId: String,
    val sessionId: String? = null,
    val connectionId: String? = null,
    val managementProfile: String? = null,
)

/**
 * Non-secret app preferences. Telemetry remains off by default (BuildConfig + this flag).
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("talaria_settings", Context.MODE_PRIVATE)

    /** Persisted BCP-47 app locale override; null means follow the system locale. */
    var localeTag: String?
        get() = prefs.getString(KEY_LOCALE_TAG, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_LOCALE_TAG) else putString(KEY_LOCALE_TAG, value)
        }

    private fun readThemeMode(): ThemeMode = when (prefs.getString("theme_mode", ThemeMode.DARK.name)) {
        ThemeMode.LIGHT.name -> ThemeMode.LIGHT
        ThemeMode.SYSTEM.name -> ThemeMode.SYSTEM
        else -> ThemeMode.DARK
    }

    // Default OFF so the curated Hermes brand palette is what ships; users can
    // opt into Material You dynamic color from the You screen.
    private val _dynamicColor = MutableStateFlow(prefs.getBoolean("dynamic_color", false))
    val dynamicColorFlow: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeModeFlow: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    var telemetryEnabled: Boolean
        get() = prefs.getBoolean("telemetry", false)
        set(value) = prefs.edit { putBoolean("telemetry", value) }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications", true)
        set(value) = prefs.edit { putBoolean("notifications", value) }

    var notifyReplies: Boolean
        get() = prefs.getBoolean("notify_replies", true)
        set(value) = prefs.edit { putBoolean("notify_replies", value) }

    var notifyAgentPermissions: Boolean
        get() = prefs.getBoolean("notify_agent_permissions", true)
        set(value) = prefs.edit { putBoolean("notify_agent_permissions", value) }

    var notifyTaskCompletions: Boolean
        get() = prefs.getBoolean("notify_task_completions", true)
        set(value) = prefs.edit { putBoolean("notify_task_completions", value) }

    var notificationPermissionRequested: Boolean
        get() = prefs.getBoolean("notification_permission_requested", false)
        set(value) = prefs.edit { putBoolean("notification_permission_requested", value) }

    var notifyCron: Boolean
        get() = prefs.getBoolean("notify_cron", true)
        set(value) = prefs.edit { putBoolean("notify_cron", value) }

    var notifyGateway: Boolean
        get() = prefs.getBoolean("notify_gateway", true)
        set(value) = prefs.edit { putBoolean("notify_gateway", value) }

    var notifyPairing: Boolean
        get() = prefs.getBoolean("notify_pairing", true)
        set(value) = prefs.edit { putBoolean("notify_pairing", value) }

    var notifyErrors: Boolean
        get() = prefs.getBoolean("notify_errors", true)
        set(value) = prefs.edit { putBoolean("notify_errors", value) }

    var backgroundSyncEnabled: Boolean
        get() = prefs.getBoolean("bg_sync", true)
        set(value) = prefs.edit { putBoolean("bg_sync", value) }

    var syncIntervalMinutes: Long
        get() = prefs.getLong("sync_interval_min", 30)
        set(value) = prefs.edit { putLong("sync_interval_min", value) }

    var ttsEnabled: Boolean
        get() = prefs.getBoolean("tts", false)
        set(value) = prefs.edit { putBoolean("tts", value) }

    var cloudSttOptIn: Boolean
        get() = prefs.getBoolean("cloud_stt", false)
        set(value) = prefs.edit { putBoolean("cloud_stt", value) }

    /**
     * The Chat surface (open tabs + titles + focused tab) per connection profile,
     * so a cold start (force-close wipes the ViewModel's in-memory tabs) restores
     * every thread with its renamed title and resumes the right conversation
     * instead of opening a blank new agent. Scoped by profile id so switching
     * connections doesn't cross-restore.
     */
    fun saveChatState(profileId: String, state: PersistedChatState) =
        prefs.edit { putString("chat_state_$profileId", JsonConfig.json.encodeToString(state)) }

    fun loadChatState(profileId: String): PersistedChatState {
        val raw = prefs.getString("chat_state_$profileId", null) ?: return PersistedChatState()
        return runCatching { JsonConfig.json.decodeFromString<PersistedChatState>(raw) }
            .getOrDefault(PersistedChatState())
    }

    @Synchronized
    fun saveAgentWatches(watches: List<PersistedAgentWatch>) = prefs.edit {
        putString("agent_notification_watches", JsonConfig.json.encodeToString(watches))
    }

    @Synchronized
    fun loadAgentWatches(): List<PersistedAgentWatch> {
        val raw = prefs.getString("agent_notification_watches", null) ?: return emptyList()
        return runCatching { JsonConfig.json.decodeFromString<List<PersistedAgentWatch>>(raw) }
            .getOrDefault(emptyList())
    }

    /** Atomically suppress a replayed sidecar frame without hiding later turns. */
    @Synchronized
    fun claimAgentNotification(
        lane: String,
        fingerprint: String,
        nowMillis: Long = System.currentTimeMillis(),
        duplicateWindowMillis: Long = 30_000L,
    ): Boolean {
        val slot = lane.hashCode().toUInt().toString(16)
        val fingerprintKey = "agent_notification_fingerprint_$slot"
        val timestampKey = "agent_notification_timestamp_$slot"
        val previous = prefs.getString(fingerprintKey, null)
        val previousAt = prefs.getLong(timestampKey, 0L)
        if (previous == fingerprint && nowMillis - previousAt in 0 until duplicateWindowMillis) return false
        prefs.edit {
            putString(fingerprintKey, fingerprint)
            putLong(timestampKey, nowMillis)
        }
        return true
    }

    @Synchronized
    fun addActiveAgentPermission(lane: String, notificationId: Int) {
        val key = "active_agent_permissions_${lane.hashCode().toUInt().toString(16)}"
        val ids = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        ids += notificationId.toString()
        prefs.edit { putStringSet(key, ids) }
    }

    @Synchronized
    fun removeActiveAgentPermission(lane: String, notificationId: Int) {
        val key = "active_agent_permissions_${lane.hashCode().toUInt().toString(16)}"
        val ids = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        ids -= notificationId.toString()
        prefs.edit { putStringSet(key, ids) }
    }

    @Synchronized
    fun takeActiveAgentPermissions(lane: String): Set<Int> {
        val key = "active_agent_permissions_${lane.hashCode().toUInt().toString(16)}"
        val ids = prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull(String::toIntOrNull).toSet()
        prefs.edit { remove(key) }
        return ids
    }

    var httpLoggingEnabled: Boolean
        get() = prefs.getBoolean("http_log", false)
        set(value) = prefs.edit { putBoolean("http_log", value) }

    var dynamicColor: Boolean
        get() = _dynamicColor.value
        set(value) {
            prefs.edit { putBoolean("dynamic_color", value) }
            _dynamicColor.value = value
        }

    /** Material You / system / forced light-dark. Defaults to dark Hermes aesthetic. */
    var themeMode: ThemeMode
        get() = _themeMode.value
        set(value) {
            prefs.edit { putString("theme_mode", value.name) }
            _themeMode.value = value
        }

    // --- Offline snapshot (Phase 13): last-good status for the widget / offline UI ---

    /** Widget/offline status is isolated per connection + Hermes management profile. */
    fun cachedStatusLine(scopeId: String): String? = prefs.getString("cache_status_line_$scopeId", null)

    fun setCachedStatusLine(scopeId: String, value: String) =
        prefs.edit { putString("cache_status_line_$scopeId", value) }

    fun setCachedStatusUpdatedAt(scopeId: String, value: Long) =
        prefs.edit { putLong("cache_status_at_$scopeId", value) }

    fun pendingPairingCount(scopeId: String): Int = prefs.getInt("cache_pending_pairing_$scopeId", 0)

    fun setPendingPairingCount(scopeId: String, value: Int) =
        prefs.edit { putInt("cache_pending_pairing_$scopeId", value) }

    /** Last successful background-poll state, scoped per connection to suppress duplicate alerts. */
    fun syncFingerprint(profileId: String, category: String): Set<String> =
        prefs.getStringSet("sync_${category}_$profileId", emptySet())?.toSet().orEmpty()

    fun setSyncFingerprint(profileId: String, category: String, values: Set<String>) =
        prefs.edit { putStringSet("sync_${category}_$profileId", values.toSet()) }

    private companion object {
        const val KEY_LOCALE_TAG = "app_locale"
    }
}
