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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nousresearch.talaria.core.network.JsonConfig
import com.nousresearch.talaria.domain.model.ConnectionProfile
import com.nousresearch.talaria.domain.model.ConnectionSecrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Multi-profile connection registry backed by EncryptedSharedPreferences + Android Keystore.
 * Secrets never land in plaintext SharedPreferences or Room.
 */
class SecureConnectionStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "talaria_secure_connections",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val json = JsonConfig.json
    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<ConnectionProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    fun activeProfile(): ConnectionProfile? {
        val id = _activeId.value ?: return _profiles.value.firstOrNull()
        return _profiles.value.find { it.id == id } ?: _profiles.value.firstOrNull()
    }

    fun secretsFor(id: String): ConnectionSecrets {
        val raw = prefs.getString(secretKey(id), null) ?: return ConnectionSecrets()
        return runCatching { json.decodeFromString<ConnectionSecrets>(raw) }.getOrDefault(ConnectionSecrets())
    }

    fun upsert(profile: ConnectionProfile, secrets: ConnectionSecrets? = null) {
        val list = _profiles.value.toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        persistProfiles(list)
        if (secrets != null) {
            prefs.edit().putString(secretKey(profile.id), json.encodeToString(secrets)).apply()
        }
        if (_activeId.value == null) setActive(profile.id)
    }

    fun setActive(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        _activeId.value = id
    }

    fun delete(id: String) {
        persistProfiles(_profiles.value.filterNot { it.id == id })
        prefs.edit().remove(secretKey(id)).apply()
        if (_activeId.value == id) {
            val next = _profiles.value.firstOrNull()?.id
            prefs.edit().putString(KEY_ACTIVE, next).apply()
            _activeId.value = next
        }
    }

    private fun loadProfiles(): List<ConnectionProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ConnectionProfile>>(raw) }.getOrDefault(emptyList())
    }

    private fun persistProfiles(list: List<ConnectionProfile>) {
        prefs.edit().putString(KEY_PROFILES, json.encodeToString(list)).apply()
        _profiles.value = list
    }

    private fun secretKey(id: String) = "secret_$id"

    companion object {
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ACTIVE = "active_id"
    }
}
