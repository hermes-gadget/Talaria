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
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hermesgadget.talaria.core.network.JsonConfig
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** A car host is trusted only as an exact package and current signing-certificate pair. */
@Serializable
data class CarHostIdentity(
    val packageName: String,
    val certificateSha256: String,
) {
    init {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid car host package name" }
        require(normalizeCarHostFingerprint(certificateSha256) == certificateSha256) {
            "Invalid car host signing-certificate fingerprint"
        }
    }

    companion object {
        private val PACKAGE_NAME =
            Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")

        fun create(packageName: String, certificateSha256: String): CarHostIdentity? {
            val normalizedPackage = packageName.trim()
            val normalizedFingerprint = normalizeCarHostFingerprint(certificateSha256) ?: return null
            if (!PACKAGE_NAME.matches(normalizedPackage)) return null
            return CarHostIdentity(normalizedPackage, normalizedFingerprint)
        }
    }
}

/** Normalize the common contiguous or colon-delimited SHA-256 display forms. */
fun normalizeCarHostFingerprint(value: String): String? {
    val normalized = value
        .filterNot { it == ':' || it.isWhitespace() }
        .lowercase(Locale.ROOT)
    return normalized.takeIf { candidate ->
        candidate.length == 64 && candidate.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

fun CarHostIdentity.displayFingerprint(): String = certificateSha256
    .uppercase(Locale.ROOT)
    .chunked(2)
    .joinToString(":")

/** One encrypted package-to-certificate trust record. Null [enrolledAt] means observed only. */
@Serializable
data class CarHostTrustRecord(
    val identity: CarHostIdentity,
    val firstObservedAt: Long? = null,
    val enrolledAt: Long? = null,
    val actionApprovedAt: Long? = null,
    val lastUsedAt: Long? = null,
)

/** Local audit evidence for a car-triggered create/send attempt. */
@Serializable
data class CarHostActionRecord(
    val identity: CarHostIdentity,
    val action: String,
    val timestamp: Long,
)

@Serializable
private data class CarHostTrustState(
    val hosts: List<CarHostTrustRecord> = emptyList(),
    val actions: List<CarHostActionRecord> = emptyList(),
)

/**
 * Certificate-bound car-host registry backed by Android Keystore encryption.
 *
 * Observing a package with a new certificate replaces its record and clears any
 * prior enrollment. This makes application re-signing fail closed instead of
 * silently carrying trust forward by package name.
 */
class CarHostTrustStore internal constructor(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(createEncryptedPreferences(context.applicationContext))

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    @Synchronized
    fun list(): List<CarHostTrustRecord> = loadState().hosts
        .sortedWith(compareBy({ it.identity.packageName }, { it.identity.certificateSha256 }))

    @Synchronized
    fun recordFor(packageName: String): CarHostTrustRecord? =
        loadState().hosts.firstOrNull { it.identity.packageName == packageName }

    @Synchronized
    fun listEnrolledIdentities(): List<CarHostIdentity> = loadState().hosts
        .asSequence()
        .filter { it.enrolledAt != null }
        .map { it.identity }
        .toList()

    @Synchronized
    fun recentActions(limit: Int = MAX_ACTION_RECORDS): List<CarHostActionRecord> =
        loadState().actions.takeLast(limit.coerceIn(0, MAX_ACTION_RECORDS))

    fun observe(identity: CarHostIdentity, nowMillis: Long = System.currentTimeMillis()) {
        mutate { state ->
            val previous = state.hosts.firstOrNull { it.identity.packageName == identity.packageName }
            val next = if (previous?.identity == identity) {
                if (previous.firstObservedAt != null) previous else previous.copy(firstObservedAt = nowMillis)
            } else {
                CarHostTrustRecord(identity = identity, firstObservedAt = nowMillis)
            }
            state.copy(hosts = state.hosts.upsert(next))
        }
    }

    /** Enrollment is explicit and also grants a fresh 15-minute action confirmation. */
    fun enroll(identity: CarHostIdentity, nowMillis: Long = System.currentTimeMillis()) {
        mutate { state ->
            val previous = state.hosts.firstOrNull { it.identity.packageName == identity.packageName }
                ?.takeIf { it.identity == identity }
            val next = CarHostTrustRecord(
                identity = identity,
                firstObservedAt = previous?.firstObservedAt,
                enrolledAt = nowMillis,
                actionApprovedAt = nowMillis,
                lastUsedAt = previous?.lastUsedAt,
            )
            state.copy(hosts = state.hosts.upsert(next))
        }
    }

    /** Retain the observed identity so the handset can offer enrollment again. */
    fun revoke(packageName: String) {
        mutate { state ->
            val previous = state.hosts.firstOrNull { it.identity.packageName == packageName }
                ?: return@mutate state
            state.copy(
                hosts = state.hosts.upsert(
                    previous.copy(enrolledAt = null, actionApprovedAt = null),
                ),
            )
        }
    }

    /** Refresh high-risk action approval only for the currently enrolled certificate. */
    fun approveActions(
        identity: CarHostIdentity,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        var approved = false
        mutate { state ->
            val previous = state.hosts.firstOrNull { it.identity.packageName == identity.packageName }
            if (previous?.identity != identity || previous.enrolledAt == null) return@mutate state
            approved = true
            state.copy(hosts = state.hosts.upsert(previous.copy(actionApprovedAt = nowMillis)))
        }
        return approved
    }

    fun isEnrolled(identity: CarHostIdentity): Boolean = synchronized(this) {
        val record = loadState().hosts.firstOrNull { it.identity.packageName == identity.packageName }
        record?.identity == identity && record.enrolledAt != null
    }

    fun touchLastUsed(identity: CarHostIdentity, nowMillis: Long = System.currentTimeMillis()) {
        mutate { state ->
            val previous = state.hosts.firstOrNull { it.identity.packageName == identity.packageName }
            val next = if (previous?.identity == identity) {
                previous.copy(lastUsedAt = nowMillis)
            } else {
                CarHostTrustRecord(identity = identity, firstObservedAt = nowMillis, lastUsedAt = nowMillis)
            }
            state.copy(hosts = state.hosts.upsert(next))
        }
    }

    /** Record the exact host identity alongside every authorized high-risk action. */
    fun recordAction(
        identity: CarHostIdentity,
        action: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        mutate { state ->
            val previous = state.hosts.firstOrNull { it.identity.packageName == identity.packageName }
            val nextHost = if (previous?.identity == identity) {
                previous.copy(lastUsedAt = nowMillis)
            } else {
                CarHostTrustRecord(identity = identity, firstObservedAt = nowMillis, lastUsedAt = nowMillis)
            }
            val nextActions = (state.actions + CarHostActionRecord(identity, action, nowMillis))
                .takeLast(MAX_ACTION_RECORDS)
            state.copy(hosts = state.hosts.upsert(nextHost), actions = nextActions)
        }
    }

    fun clear() {
        mutate { CarHostTrustState() }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun List<CarHostTrustRecord>.upsert(record: CarHostTrustRecord): List<CarHostTrustRecord> =
        filterNot { it.identity.packageName == record.identity.packageName } + record

    private fun mutate(transform: (CarHostTrustState) -> CarHostTrustState) {
        val changed = synchronized(this) {
            val current = loadState()
            val next = transform(current)
            if (next == current) return@synchronized false
            check(prefs.edit().putString(KEY_STATE, JsonConfig.json.encodeToString(next)).commit()) {
                "Could not persist car host trust"
            }
            _revision.value += 1L
            true
        }
        if (changed) listeners.forEach { it.invoke() }
    }

    private fun loadState(): CarHostTrustState {
        val encoded = prefs.getString(KEY_STATE, null) ?: return CarHostTrustState()
        return runCatching { JsonConfig.json.decodeFromString<CarHostTrustState>(encoded) }
            .getOrDefault(CarHostTrustState())
    }

    companion object {
        private const val KEY_STATE = "car_host_trust_state"
        private const val MAX_ACTION_RECORDS = 50

        private fun createEncryptedPreferences(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                "talaria_car_host_trust",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
