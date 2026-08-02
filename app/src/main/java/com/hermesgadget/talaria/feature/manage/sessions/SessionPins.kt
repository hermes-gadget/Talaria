/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.manage.sessions

import android.content.Context
import androidx.core.content.edit

/** Local-only session organization; Hermes does not receive pin metadata. */
interface SessionPinStore {
    fun load(scopeId: String): Set<String>
    fun setPinned(scopeId: String, sessionId: String, pinned: Boolean)
}

/**
 * Persists pins in the same non-secret preferences file used by the app settings store.
 * The key includes the connection/profile scope so a pin never leaks between Hermes homes.
 */
class SharedPreferencesSessionPinStore(context: Context) : SessionPinStore {
    private val prefs = context.applicationContext.getSharedPreferences(
        "talaria_settings",
        Context.MODE_PRIVATE,
    )

    @Synchronized
    override fun load(scopeId: String): Set<String> =
        prefs.getStringSet(key(scopeId), emptySet()).orEmpty().toSet()

    @Synchronized
    override fun setPinned(scopeId: String, sessionId: String, pinned: Boolean) {
        val ids = load(scopeId).toMutableSet()
        if (pinned) ids += sessionId else ids -= sessionId
        prefs.edit { putStringSet(key(scopeId), ids) }
    }

    private fun key(scopeId: String): String =
        "sessions_pinned_${scopeId.trim().ifBlank { "none" }}"
}
