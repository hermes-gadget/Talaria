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

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import com.hermesgadget.talaria.domain.model.normalizeManagementProfile
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SecureStoreDiagnostics(
    val code: String,
    val phase: String,
    val causeType: String,
) {
    fun copyText(): String = "Talaria secure connections: code=$code; phase=$phase; cause=$causeType"
}

sealed interface SecureConnectionStoreState {
    data class Available(val profileCount: Int) : SecureConnectionStoreState
    data class RecoverableCorruption(val diagnostics: SecureStoreDiagnostics) : SecureConnectionStoreState
    data class PermanentKeystoreLoss(val diagnostics: SecureStoreDiagnostics) : SecureConnectionStoreState
}

internal enum class SecureStoreFailureKind { RECOVERABLE, PERMANENT }

internal class SecureStoreAccessException(
    val kind: SecureStoreFailureKind,
    cause: Throwable,
) : RuntimeException(cause)

internal interface SecureConnectionStorage {
    fun open(): SharedPreferences
    fun confirmedReset(): Boolean
}

/** Android storage seam. The journal lets a user-confirmed reset finish safely after interruption. */
private class AndroidSecureConnectionStorage(context: Context) : SecureConnectionStorage {
    private val appContext = context.applicationContext
    private val recovery = appContext.getSharedPreferences(RECOVERY_FILE, Context.MODE_PRIVATE)

    override fun open(): SharedPreferences {
        if (!finishInterruptedReset()) {
            throw SecureStoreAccessException(
                SecureStoreFailureKind.RECOVERABLE,
                IllegalStateException("Confirmed reset could not finish"),
            )
        }
        val alias = recovery.getString(KEY_ACTIVE_ALIAS, null)
        return try {
            val builder = if (alias == null) MasterKey.Builder(appContext) else MasterKey.Builder(appContext, alias)
            val masterKey = builder.setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                appContext,
                CONNECTION_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (failure: Throwable) {
            throw SecureStoreAccessException(classifySecureStoreFailure(failure), failure)
        }
    }

    override fun confirmedReset(): Boolean {
        // Explicit commit() + result check: reset durability is critical and the
        // KTX edit{} extension discards the commit result (returns Unit).
        @SuppressLint("UseKtx")
        if (!recovery.edit().putBoolean(KEY_RESET_IN_PROGRESS, true).commit()) return false
        return finishInterruptedReset()
    }

    private fun finishInterruptedReset(): Boolean {
        if (!recovery.getBoolean(KEY_RESET_IN_PROGRESS, false)) return true
        val deleted = appContext.deleteSharedPreferences(CONNECTION_FILE)
        if (!deleted && connectionFilesExist()) return false
        // Do not delete AndroidX's legacy default master key: sibling encrypted stores may use it.
        // A dedicated alias makes recovery from a permanently invalidated legacy key connection-scoped.
        deleteDedicatedResetKey()
        @SuppressLint("UseKtx")
        return recovery.edit()
            .putString(KEY_ACTIVE_ALIAS, RESET_MASTER_KEY_ALIAS)
            .remove(KEY_RESET_IN_PROGRESS)
            .commit()
    }

    private fun connectionFilesExist(): Boolean {
        val directory = File(appContext.applicationInfo.dataDir, "shared_prefs")
        return File(directory, "$CONNECTION_FILE.xml").exists() ||
            File(directory, "$CONNECTION_FILE.xml.bak").exists()
    }

    private fun deleteDedicatedResetKey() {
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(RESET_MASTER_KEY_ALIAS)) keyStore.deleteEntry(RESET_MASTER_KEY_ALIAS)
        }
    }

    companion object {
        private const val CONNECTION_FILE = "talaria_secure_connections"
        private const val RECOVERY_FILE = "talaria_secure_connections_recovery"
        private const val KEY_RESET_IN_PROGRESS = "confirmed_reset_in_progress"
        private const val KEY_ACTIVE_ALIAS = "active_master_key_alias"
        private const val RESET_MASTER_KEY_ALIAS = "talaria_secure_connections_recovery_key"
    }
}

internal fun classifySecureStoreFailure(failure: Throwable): SecureStoreFailureKind {
    val names = generateSequence(failure) { it.cause }.map { it.javaClass.name }.toList()
    return if (names.any { name ->
            name.contains("KeyPermanentlyInvalidatedException") ||
                name.contains("UnrecoverableKeyException") ||
                name.contains("KeyStoreException") ||
                name.contains("InvalidKeyException")
        }
    ) {
        SecureStoreFailureKind.PERMANENT
    } else {
        SecureStoreFailureKind.RECOVERABLE
    }
}

/**
 * Multi-profile connection registry backed by EncryptedSharedPreferences + Android Keystore.
 *
 * Construction never throws. Callers must observe [state]; snapshot reads fail closed while the
 * encrypted file is unavailable, and neither retry nor startup clears or recreates stored data.
 */
class SecureConnectionStore internal constructor(
    private val storage: SecureConnectionStorage,
    private val json: Json = JsonConfig.json,
) {
    constructor(context: Context) : this(AndroidSecureConnectionStorage(context))

    private val mutationLock = Any()
    private var prefs: SharedPreferences? = null
    private val _profiles = MutableStateFlow<List<ConnectionProfile>>(emptyList())
    val profiles: StateFlow<List<ConnectionProfile>> = _profiles.asStateFlow()
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()
    private val _state = MutableStateFlow<SecureConnectionStoreState>(
        SecureConnectionStoreState.RecoverableCorruption(
            SecureStoreDiagnostics("INITIALIZING", "startup", "none"),
        ),
    )
    val state: StateFlow<SecureConnectionStoreState> = _state.asStateFlow()

    init {
        synchronized(mutationLock) { load("startup") }
    }

    fun retry(): SecureConnectionStoreState = synchronized(mutationLock) {
        load("retry")
        _state.value
    }

    /** Deletes only encrypted connection records, and only after the UI's explicit confirmation. */
    fun confirmedReset(): SecureConnectionStoreState = synchronized(mutationLock) {
        prefs = null
        _profiles.value = emptyList()
        _activeId.value = null
        if (!runCatching { storage.confirmedReset() }.getOrDefault(false)) {
            _state.value = SecureConnectionStoreState.RecoverableCorruption(
                SecureStoreDiagnostics("RESET_INTERRUPTED", "reset", "commit"),
            )
            return@synchronized _state.value
        }
        load("reset")
        _state.value
    }

    fun activeProfile(): ConnectionProfile? = synchronized(mutationLock) {
        if (_state.value !is SecureConnectionStoreState.Available) return null
        val id = _activeId.value ?: return _profiles.value.firstOrNull()
        _profiles.value.find { it.id == id } ?: _profiles.value.firstOrNull()
    }

    fun profileFor(id: String): ConnectionProfile? = synchronized(mutationLock) {
        if (_state.value !is SecureConnectionStoreState.Available) null else _profiles.value.find { it.id == id }
    }

    fun secretsFor(id: String): ConnectionSecrets? = synchronized(mutationLock) {
        if (_state.value !is SecureConnectionStoreState.Available) return null
        readSecretsSafely(id, _profiles.value.find { it.id == id })
    }

    fun snapshotFor(id: String, expectedManagementProfile: String? = null): ConnectionSnapshot? =
        synchronized(mutationLock) {
            if (_state.value !is SecureConnectionStoreState.Available) return null
            val profile = _profiles.value.find { it.id == id } ?: return null
            if (expectedManagementProfile != null &&
                normalizeManagementProfile(expectedManagementProfile) !=
                normalizeManagementProfile(profile.managementProfile)
            ) return null
            val secrets = readSecretsSafely(id, profile) ?: return null
            ConnectionSnapshot.from(profile, secrets)
        }

    fun activeSnapshot(): ConnectionSnapshot? = synchronized(mutationLock) {
        val profile = activeProfile() ?: return null
        val secrets = readSecretsSafely(profile.id, profile) ?: return null
        ConnectionSnapshot.from(profile, secrets)
    }

    @SuppressLint("UseKtx")
    fun upsert(profile: ConnectionProfile, secrets: ConnectionSecrets? = null) = synchronized(mutationLock) {
        val availablePrefs = requireAvailablePrefs()
        val persistedProfile = CleartextConsentMigration.normalizeCurrent(profile)
        val list = _profiles.value.toMutableList()
        val idx = list.indexOfFirst { it.id == persistedProfile.id }
        if (idx >= 0) list[idx] = persistedProfile else list.add(persistedProfile)
        val nextActiveId = _activeId.value ?: persistedProfile.id
        val editor = availablePrefs.edit()
            .putString(KEY_PROFILES, json.encodeToString(list))
            .putString(KEY_ACTIVE, nextActiveId)
            .putInt(KEY_CLEARTEXT_CONSENT_VERSION, CleartextConsentMigration.CURRENT_VERSION)
        if (secrets != null) editor.putString(secretKey(persistedProfile.id), json.encodeToString(secrets))
        check(editor.commit()) { "Could not persist the Hermes connection" }
        _profiles.value = list
        _activeId.value = nextActiveId
        _state.value = SecureConnectionStoreState.Available(list.size)
    }

    @SuppressLint("UseKtx")
    fun setActive(id: String) = synchronized(mutationLock) {
        check(_profiles.value.any { it.id == id }) { "Unknown Hermes connection" }
        check(requireAvailablePrefs().edit().putString(KEY_ACTIVE, id).commit()) {
            "Could not select the Hermes connection"
        }
        _activeId.value = id
    }

    fun updateSessionToken(id: String, token: String) = synchronized(mutationLock) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        val profile = _profiles.value.find { it.id == id } ?: return
        val prev = readSecretsSafely(id, profile) ?: return
        upsert(profile.copy(hasSessionToken = true), prev.copy(sessionToken = trimmed))
    }

    fun completeConnectionTestIfSnapshot(
        snapshot: ConnectionSnapshot,
        discoveredSessionToken: String?,
        connectedAt: Long,
    ): Boolean = synchronized(mutationLock) {
        val profile = _profiles.value.find { it.id == snapshot.connectionId } ?: return false
        val secrets = readSecretsSafely(snapshot.connectionId, profile) ?: return false
        val current = ConnectionSnapshot.from(profile, secrets)
        if (current.profile != snapshot.profile || current.secrets != snapshot.secrets) return false
        val token = discoveredSessionToken?.trim()?.takeIf { it.isNotEmpty() }
        upsert(
            profile.copy(hasSessionToken = profile.hasSessionToken || token != null, lastConnectedAt = connectedAt),
            current.secrets.copy(sessionToken = token ?: current.sessionToken),
        )
        true
    }

    fun updateOidcTokens(id: String, accessToken: String, refreshToken: String, expiresAt: Long, provider: String) =
        synchronized(mutationLock) {
            if (accessToken.isBlank()) return
            val profile = _profiles.value.find { it.id == id } ?: return
            val prev = readSecretsSafely(id, profile) ?: return
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

    fun revokeCleartextConsent(id: String): Boolean = synchronized(mutationLock) {
        val profile = _profiles.value.find { it.id == id } ?: return false
        val secrets = readSecretsSafely(id, profile) ?: return false
        upsert(
            profile.copy(allowCleartext = false, cleartextConsentRecorded = false, cleartextConsentOrigin = null),
            secrets,
        )
        true
    }

    fun updateOidcTokensIfSnapshot(
        snapshot: ConnectionSnapshot,
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
        provider: String,
    ): Boolean = synchronized(mutationLock) {
        val profile = _profiles.value.find { it.id == snapshot.connectionId } ?: return false
        val secrets = readSecretsSafely(snapshot.connectionId, profile) ?: return false
        val current = ConnectionSnapshot.from(profile, secrets)
        if (!current.sameTransportAs(snapshot) || current.secrets != snapshot.secrets) return false
        upsert(
            profile.copy(hasBearerToken = true),
            secrets.copy(
                bearerToken = accessToken,
                oidcRefreshToken = refreshToken.ifBlank { secrets.oidcRefreshToken },
                oidcExpiresAt = expiresAt,
                oidcProvider = provider.ifBlank { secrets.oidcProvider },
            ),
        )
        true
    }

    fun setManagementProfile(profileName: String) = synchronized(mutationLock) {
        val active = activeProfile() ?: return
        upsert(active.copy(managementProfile = normalizeManagementProfile(profileName)))
    }

    @SuppressLint("UseKtx")
    fun delete(id: String) = synchronized(mutationLock) {
        val availablePrefs = requireAvailablePrefs()
        val nextProfiles = _profiles.value.filterNot { it.id == id }
        val nextActiveId = if (_activeId.value == id) nextProfiles.firstOrNull()?.id else _activeId.value
        val editor = availablePrefs.edit().putString(KEY_PROFILES, json.encodeToString(nextProfiles)).remove(secretKey(id))
        if (nextActiveId == null) editor.remove(KEY_ACTIVE) else editor.putString(KEY_ACTIVE, nextActiveId)
        check(editor.commit()) { "Could not delete the Hermes connection" }
        _profiles.value = nextProfiles
        _activeId.value = nextActiveId
        _state.value = SecureConnectionStoreState.Available(nextProfiles.size)
    }

    @SuppressLint("UseKtx")
    private fun load(phase: String) {
        try {
            val candidate = storage.open()
            candidate.all // Force authentication of every encrypted key/value, including orphan records.
            val rawProfiles = candidate.getString(KEY_PROFILES, null)
            val decoded = rawProfiles?.let { json.decodeFromString<List<ConnectionProfile>>(it) }.orEmpty()
            val persistedVersion = candidate.getInt(KEY_CLEARTEXT_CONSENT_VERSION, 0)
            val migrated = CleartextConsentMigration.migrate(decoded, persistedVersion)
            migrated.forEach { profile -> decodeSecrets(candidate, profile.id, profile) }
            if (rawProfiles != null &&
                (persistedVersion != CleartextConsentMigration.CURRENT_VERSION || migrated != decoded)
            ) {
                check(
                    candidate.edit()
                        .putString(KEY_PROFILES, json.encodeToString(migrated))
                        .putInt(KEY_CLEARTEXT_CONSENT_VERSION, CleartextConsentMigration.CURRENT_VERSION)
                        .commit(),
                ) { "Could not migrate the Hermes connection consent records" }
            }
            val active = candidate.getString(KEY_ACTIVE, null)
            prefs = candidate
            _profiles.value = migrated
            _activeId.value = active
            _state.value = SecureConnectionStoreState.Available(migrated.size)
        } catch (failure: Throwable) {
            prefs = null
            _profiles.value = emptyList()
            _activeId.value = null
            val root = (failure as? SecureStoreAccessException)?.cause ?: failure
            val diagnostics = SecureStoreDiagnostics(
                code = if ((failure as? SecureStoreAccessException)?.kind == SecureStoreFailureKind.PERMANENT ||
                    classifySecureStoreFailure(failure) == SecureStoreFailureKind.PERMANENT
                ) "KEYSTORE_LOST" else "ENCRYPTED_DATA_CORRUPT",
                phase = phase,
                causeType = root.javaClass.simpleName.ifBlank { "Unknown" },
            )
            _state.value = if (diagnostics.code == "KEYSTORE_LOST") {
                SecureConnectionStoreState.PermanentKeystoreLoss(diagnostics)
            } else {
                SecureConnectionStoreState.RecoverableCorruption(diagnostics)
            }
        }
    }

    private fun readSecretsSafely(id: String, profile: ConnectionProfile?): ConnectionSecrets? = try {
        decodeSecrets(requireAvailablePrefs(), id, profile)
    } catch (failure: Throwable) {
        val diagnostics = SecureStoreDiagnostics(
            code = if (classifySecureStoreFailure(failure) == SecureStoreFailureKind.PERMANENT) {
                "KEYSTORE_LOST"
            } else {
                "ENCRYPTED_DATA_CORRUPT"
            },
            phase = "secret_read",
            causeType = failure.javaClass.simpleName.ifBlank { "Unknown" },
        )
        prefs = null
        _profiles.value = emptyList()
        _activeId.value = null
        _state.value = if (diagnostics.code == "KEYSTORE_LOST") {
            SecureConnectionStoreState.PermanentKeystoreLoss(diagnostics)
        } else {
            SecureConnectionStoreState.RecoverableCorruption(diagnostics)
        }
        null
    }

    private fun decodeSecrets(
        source: SharedPreferences,
        id: String,
        profile: ConnectionProfile?,
    ): ConnectionSecrets {
        val raw = source.getString(secretKey(id), null)
        if (raw == null) {
            check(profile == null || (!profile.hasPassword && !profile.hasSessionToken && !profile.hasBearerToken)) {
                "Encrypted credential record is missing"
            }
            return ConnectionSecrets()
        }
        return json.decodeFromString(raw)
    }

    private fun requireAvailablePrefs(): SharedPreferences = prefs
        ?.takeIf { _state.value is SecureConnectionStoreState.Available }
        ?: error("Encrypted connections are unavailable; use the recovery actions")

    private fun secretKey(id: String) = "secret_$id"

    companion object {
        internal const val KEY_PROFILES = "profiles_json"
        internal const val KEY_ACTIVE = "active_id"
        internal const val KEY_CLEARTEXT_CONSENT_VERSION = "cleartext_consent_schema_version"
        internal fun secretKeyForTest(id: String) = "secret_$id"
    }
}
