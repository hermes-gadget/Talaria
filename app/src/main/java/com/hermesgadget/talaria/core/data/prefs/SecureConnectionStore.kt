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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import com.hermesgadget.talaria.domain.model.normalizeManagementProfile
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
    /** One lock covers the logical profile + secret record, not just each pref call. */
    private val mutationLock = Any()
    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<ConnectionProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    fun activeProfile(): ConnectionProfile? = synchronized(mutationLock) {
        val id = _activeId.value ?: return _profiles.value.firstOrNull()
        return _profiles.value.find { it.id == id } ?: _profiles.value.firstOrNull()
    }

    fun profileFor(id: String): ConnectionProfile? = synchronized(mutationLock) {
        _profiles.value.find { it.id == id }
    }

    fun secretsFor(id: String): ConnectionSecrets = synchronized(mutationLock) {
        readSecrets(id)
    }

    /** Atomically captures one profile and its encrypted secret record. */
    fun snapshotFor(
        id: String,
        expectedManagementProfile: String? = null,
    ): ConnectionSnapshot? = synchronized(mutationLock) {
        val profile = _profiles.value.find { it.id == id } ?: return null
        if (expectedManagementProfile != null &&
            normalizeManagementProfile(expectedManagementProfile) !=
            normalizeManagementProfile(profile.managementProfile)
        ) {
            return null
        }
        ConnectionSnapshot.from(profile, readSecrets(id))
    }

    fun activeSnapshot(): ConnectionSnapshot? = synchronized(mutationLock) {
        val profile = activeProfile() ?: return null
        ConnectionSnapshot.from(profile, readSecrets(profile.id))
    }

    fun upsert(profile: ConnectionProfile, secrets: ConnectionSecrets? = null) = synchronized(mutationLock) {
        val list = _profiles.value.toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        val nextActiveId = _activeId.value ?: profile.id
        val editor = prefs.edit()
            .putString(KEY_PROFILES, json.encodeToString(list))
            .putString(KEY_ACTIVE, nextActiveId)
        if (secrets != null) {
            editor.putString(secretKey(profile.id), json.encodeToString(secrets))
        }
        check(editor.commit()) { "Could not persist the Hermes connection" }
        _profiles.value = list
        if (_activeId.value != nextActiveId) _activeId.value = nextActiveId
    }

    fun setActive(id: String) = synchronized(mutationLock) {
        check(_profiles.value.any { it.id == id }) { "Unknown Hermes connection" }
        check(prefs.edit().putString(KEY_ACTIVE, id).commit()) {
            "Could not select the Hermes connection"
        }
        _activeId.value = id
    }

    /** Persist a freshly minted loopback `__HERMES_SESSION_TOKEN__` for [id]. */
    fun updateSessionToken(id: String, token: String) = synchronized(mutationLock) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        val profile = _profiles.value.find { it.id == id } ?: return
        val prev = readSecrets(id)
        upsert(
            profile.copy(hasSessionToken = true),
            prev.copy(sessionToken = trimmed),
        )
    }

    /** Store native-app OAuth tokens in the encrypted connection record. */
    fun updateOidcTokens(
        id: String,
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
        provider: String,
    ) = synchronized(mutationLock) {
        if (accessToken.isBlank()) return
        val profile = _profiles.value.find { it.id == id } ?: return
        val prev = readSecrets(id)
        upsert(
            profile.copy(hasBearerToken = true),
            prev.copy(
                bearerToken = accessToken,
                oidcRefreshToken = refreshToken.ifBlank { prev.oidcRefreshToken },
                oidcExpiresAt = expiresAt,
                oidcProvider = provider.ifBlank { prev.oidcProvider },
            ),
        )
    }

    /** Persist a refresh result only if the captured profile and credentials are still current. */
    fun updateOidcTokensIfSnapshot(
        snapshot: ConnectionSnapshot,
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
        provider: String,
    ): Boolean = synchronized(mutationLock) {
        val profile = _profiles.value.find { it.id == snapshot.connectionId } ?: return false
        val current = ConnectionSnapshot.from(profile, readSecrets(snapshot.connectionId))
        if (!current.sameTransportAs(snapshot) || current.secrets != snapshot.secrets) return false
        val previous = current.secrets
        upsert(
            profile.copy(hasBearerToken = true),
            previous.copy(
                bearerToken = accessToken,
                oidcRefreshToken = refreshToken.ifBlank { previous.oidcRefreshToken },
                oidcExpiresAt = expiresAt,
                oidcProvider = provider.ifBlank { previous.oidcProvider },
            ),
        )
        true
    }

    /** Updates the Hermes management profile (`?profile=`) for the active connection. */
    fun setManagementProfile(profileName: String) = synchronized(mutationLock) {
        val active = activeProfile() ?: return
        upsert(active.copy(managementProfile = normalizeManagementProfile(profileName)))
    }

    fun delete(id: String) = synchronized(mutationLock) {
        val nextProfiles = _profiles.value.filterNot { it.id == id }
        val nextActiveId = if (_activeId.value == id) nextProfiles.firstOrNull()?.id else _activeId.value
        val editor = prefs.edit()
            .putString(KEY_PROFILES, json.encodeToString(nextProfiles))
            .remove(secretKey(id))
        if (nextActiveId == null) editor.remove(KEY_ACTIVE) else editor.putString(KEY_ACTIVE, nextActiveId)
        check(editor.commit()) { "Could not delete the Hermes connection" }
        _profiles.value = nextProfiles
        _activeId.value = nextActiveId
    }

    private fun loadProfiles(): List<ConnectionProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        val profiles = runCatching { json.decodeFromString<List<ConnectionProfile>>(raw) }
            .getOrDefault(emptyList())
        // Legacy records predate the cleartext-consent flag. Record implicit
        // consent once so behaviour is unchanged for existing private/loopback
        // profiles; new saves now require an explicit user decision.
        if (profiles.any { it.cleartextConsentRecorded == null }) {
            val migrated = profiles.map { it.copy(cleartextConsentRecorded = it.cleartextConsentRecorded ?: true) }
            prefs.edit().putString(KEY_PROFILES, json.encodeToString(migrated)).apply()
            return migrated
        }
        return profiles
    }

    private fun readSecrets(id: String): ConnectionSecrets {
        val raw = prefs.getString(secretKey(id), null) ?: return ConnectionSecrets()
        return runCatching { json.decodeFromString<ConnectionSecrets>(raw) }
            .getOrDefault(ConnectionSecrets())
    }

    private fun secretKey(id: String) = "secret_$id"

    companion object {
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ACTIVE = "active_id"
    }
}
