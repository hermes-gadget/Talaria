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

/**
 * Non-secret app preferences. Telemetry remains off by default (BuildConfig + this flag).
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("talaria_settings", Context.MODE_PRIVATE)

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
        set(value) = prefs.edit { putBoolean("cloud_stt", false).putBoolean("cloud_stt", value) }

    var httpLoggingEnabled: Boolean
        get() = prefs.getBoolean("http_log", false)
        set(value) = prefs.edit { putBoolean("http_log", value) }

    var dynamicColor: Boolean
        get() = prefs.getBoolean("dynamic_color", true)
        set(value) = prefs.edit { putBoolean("dynamic_color", value) }
}
