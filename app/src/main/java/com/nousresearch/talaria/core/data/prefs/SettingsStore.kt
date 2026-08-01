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


package com.nousresearch.talaria.core.data.prefs

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
}

/**
 * Non-secret app preferences. Telemetry remains off by default (BuildConfig + this flag).
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("talaria_settings", Context.MODE_PRIVATE)

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

    var ignoreBatteryOptimizationsRequested: Boolean
        get() = prefs.getBoolean("ignore_battery_asked", false)
        set(value) = prefs.edit { putBoolean("ignore_battery_asked", value) }

    var ttsEnabled: Boolean
        get() = prefs.getBoolean("tts", false)
        set(value) = prefs.edit { putBoolean("tts", value) }

    var cloudSttOptIn: Boolean
        get() = prefs.getBoolean("cloud_stt", false)
        set(value) = prefs.edit { putBoolean("cloud_stt", value) }

    /**
     * Last Hermes chat session id per connection profile, so a cold start
     * (force-close wipes the ViewModel's in-memory tabs) can resume the last
     * conversation instead of opening a blank new agent. Scoped by profile id
     * so switching connections doesn't cross-restore.
     */
    fun lastSessionId(profileId: String): String? =
        prefs.getString("last_session_$profileId", null)

    fun setLastSessionId(profileId: String, sessionId: String) =
        prefs.edit { putString("last_session_$profileId", sessionId) }

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

    /** One-line status summary from the last successful poll (widget + offline fallback). */
    var cachedStatusLine: String?
        get() = prefs.getString("cache_status_line", null)
        set(value) = prefs.edit { putString("cache_status_line", value) }

    /** Epoch millis of the last successful status poll, 0 if never. */
    var cachedStatusUpdatedAt: Long
        get() = prefs.getLong("cache_status_at", 0L)
        set(value) = prefs.edit { putLong("cache_status_at", value) }

    /** Pending pairing requests seen at the last poll (widget badge). */
    var pendingPairingCount: Int
        get() = prefs.getInt("cache_pending_pairing", 0)
        set(value) = prefs.edit { putInt("cache_pending_pairing", value) }
}
