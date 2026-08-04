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

package com.hermesgadget.talaria.core.network

import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import com.hermesgadget.talaria.domain.model.effectiveManagementProfile
import com.hermesgadget.talaria.domain.model.scopeId
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.Locale

/** Normalized transport origin used as the cleartext-consent binding key. */
object ConnectionOrigin {
    fun normalize(baseUrl: String): String? = baseUrl.toHttpUrlOrNull()?.let(::normalize)

    fun normalize(url: HttpUrl): String {
        val scheme = when (url.scheme.lowercase(Locale.US)) {
            "ws" -> "http"
            "wss" -> "https"
            else -> url.scheme.lowercase(Locale.US)
        }
        val host = url.host.lowercase(Locale.US)
        val renderedHost = if (host.contains(':')) "[$host]" else host
        return "$scheme://$renderedHost:${url.port}"
    }

    fun isCleartext(url: HttpUrl): Boolean = url.scheme == "http" || url.scheme == "ws"

    fun isSecure(url: HttpUrl): Boolean = url.scheme == "https" || url.scheme == "wss"
}

/**
 * Immutable transport identity for one saved connection and management profile.
 *
 * A snapshot is deliberately self-contained: changing the active connection,
 * editing a saved profile, or refreshing credentials cannot change the URL,
 * profile query, credentials, pin, cleartext decision, or logging policy used by
 * a client that was built from this value.
 */
data class ConnectionSnapshot(
    val profile: ConnectionProfile,
    val secrets: ConnectionSecrets,
    val httpLoggingEnabled: Boolean = false,
) {
    val connectionId: String get() = profile.id
    val managementProfile: String get() = profile.effectiveManagementProfile()
    val baseUrl: String get() = profile.baseUrl
    val authMode: AuthMode get() = profile.authMode
    val username: String? get() = profile.username
    val authProvider: String get() = profile.authProvider
    val sessionToken: String? get() = secrets.sessionToken
    val password: String? get() = secrets.password
    val bearerToken: String? get() = secrets.bearerToken
    val oidcRefreshToken: String? get() = secrets.oidcRefreshToken
    val oidcExpiresAt: Long? get() = secrets.oidcExpiresAt
    val oidcProvider: String? get() = secrets.oidcProvider
    val pinSha256: String? get() = profile.pinSha256
    val allowCleartext: Boolean get() = profile.allowCleartext
    val cleartextConsentRecorded: Boolean get() = profile.cleartextConsentRecorded == true
    val cleartextConsentOrigin: String? get() = profile.cleartextConsentOrigin
    val scopeId: String get() = profile.scopeId()

    fun hasCleartextConsentFor(url: HttpUrl): Boolean {
        val savedOrigin = baseUrl.toHttpUrlOrNull()?.let(ConnectionOrigin::normalize) ?: return false
        val requestedOrigin = ConnectionOrigin.normalize(url)
        return cleartextConsentRecorded &&
            cleartextConsentOrigin == savedOrigin &&
            requestedOrigin == savedOrigin
    }

    /** Compare the parts that make a request unsafe to reuse after an edit. */
    fun sameTransportAs(other: ConnectionSnapshot): Boolean =
        connectionId == other.connectionId &&
            baseUrl == other.baseUrl &&
            managementProfile == other.managementProfile &&
            authMode == other.authMode &&
            username == other.username &&
            authProvider == other.authProvider &&
            pinSha256 == other.pinSha256 &&
            allowCleartext == other.allowCleartext &&
            cleartextConsentRecorded == other.cleartextConsentRecorded &&
            cleartextConsentOrigin == other.cleartextConsentOrigin

    fun withHttpLogging(enabled: Boolean): ConnectionSnapshot = copy(httpLoggingEnabled = enabled)

    override fun toString(): String =
        "ConnectionSnapshot(connectionId=$connectionId, baseUrl=$baseUrl, " +
            "managementProfile=$managementProfile, authMode=$authMode, " +
            "httpLoggingEnabled=$httpLoggingEnabled)"

    companion object {
        fun from(
            profile: ConnectionProfile,
            secrets: ConnectionSecrets,
            httpLoggingEnabled: Boolean = false,
        ): ConnectionSnapshot = ConnectionSnapshot(profile, secrets, httpLoggingEnabled)

        fun anonymous(httpLoggingEnabled: Boolean = false): ConnectionSnapshot =
            ConnectionSnapshot(
                profile = ConnectionProfile(
                    id = "__anonymous__",
                    name = "Default Hermes",
                    baseUrl = "http://10.0.2.2:9119",
                    authMode = AuthMode.NONE,
                    createdAt = 0L,
                ),
                secrets = ConnectionSecrets(),
                httpLoggingEnabled = httpLoggingEnabled,
            )
    }
}

/** Pure checks shared by REST, WebSocket, worker, car, and OIDC operation boundaries. */
internal object SnapshotAuthGuard {
    const val CHANGED_MESSAGE =
        "The saved connection changed and the operation was safely canceled."
    const val OIDC_CHANGED_MESSAGE =
        "The saved connection changed while signing in and the operation was safely canceled."

    fun isCurrent(saved: ConnectionSnapshot, current: ConnectionSnapshot?): Boolean =
        current != null && current.sameTransportAs(saved) && current.secrets == saved.secrets

    fun isExactCurrent(saved: ConnectionSnapshot, current: ConnectionSnapshot?): Boolean =
        current != null && current.profile == saved.profile && current.secrets == saved.secrets

    @Throws(IOException::class)
    fun requireCurrent(
        saved: ConnectionSnapshot,
        current: ConnectionSnapshot?,
        message: String = CHANGED_MESSAGE,
    ) {
        if (!isCurrent(saved, current)) throw IOException(message)
    }

    @Throws(IOException::class)
    fun requireExactCurrent(
        saved: ConnectionSnapshot,
        current: ConnectionSnapshot?,
        message: String = CHANGED_MESSAGE,
    ) {
        if (!isExactCurrent(saved, current)) throw IOException(message)
    }

    fun hasSameOrigin(snapshot: ConnectionSnapshot, url: HttpUrl): Boolean {
        val saved = snapshot.baseUrl.toHttpUrlOrNull() ?: return false
        return ConnectionOrigin.normalize(saved) == ConnectionOrigin.normalize(url)
    }

    @Throws(IOException::class)
    fun requireSameOrigin(snapshot: ConnectionSnapshot, url: HttpUrl) {
        if (!hasSameOrigin(snapshot, url)) {
            throw IOException(
                "The request origin did not match the saved connection and the operation was safely canceled.",
            )
        }
    }

    /** Native OIDC bootstrap and exchange routes never receive stored credentials. */
    fun suppressCredentials(path: String): Boolean = path.startsWith("/auth/native/")
}

data class CleartextConsentDecision(
    val recorded: Boolean,
    val origin: String?,
)

/** Pure origin-binding rules shared by persistence and transport tests. */
object CleartextConsentPolicy {
    fun resolve(
        baseUrl: HttpUrl,
        requestedRecorded: Boolean?,
        requestedOrigin: String?,
        previous: ConnectionProfile?,
    ): CleartextConsentDecision {
        if (baseUrl.isHttps) return CleartextConsentDecision(false, null)
        val origin = ConnectionOrigin.normalize(baseUrl)
        if (requestedRecorded == false) return CleartextConsentDecision(false, null)
        if (requestedRecorded == true) {
            // A missing key is bound to the current origin by this save call;
            // an explicitly supplied malformed/mismatched key fails closed.
            val requestedMatches = requestedOrigin == null || requestedOrigin == origin
            return if (requestedMatches) {
                CleartextConsentDecision(true, origin)
            } else {
                CleartextConsentDecision(false, null)
            }
        }
        val previousMatches = previous?.cleartextConsentRecorded == true &&
            previous.cleartextConsentOrigin == origin
        return if (previousMatches) {
            CleartextConsentDecision(true, origin)
        } else {
            CleartextConsentDecision(false, null)
        }
    }
}

/** Cleartext is only a supported transport for explicitly verified local hosts. */
object CleartextPolicy {
    fun isVerifiedDestination(host: String): Boolean {
        val normalized = host.trim().lowercase(Locale.US).removeSuffix(".")
        if (isAutoApprovedLocalHost(normalized)) {
            return true
        }
        // IPv6 literals only — DNS names never qualify. ULA fc00::/7
        // (Tailscale/self-hosted mesh) and link-local fe80::/10 computed
        // explicitly (JDK isSiteLocalAddress covers only legacy fec0::/10).
        // getByName on a literal does not perform DNS resolution.
        if (normalized.contains(':')) {
            return runCatching {
                val address = java.net.InetAddress.getByName(normalized) as? java.net.Inet6Address
                    ?: return@runCatching false
                val bytes = address.address
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                val ula = (first and 0xfe) == 0xfc // fc00::/7
                val linkLocal = first == 0xfe && (second and 0xc0) == 0x80 // fe80::/10
                ula || linkLocal
            }.getOrDefault(false)
        }
        val octets = normalized.split('.')
            .takeIf { it.size == 4 }
            ?.map { part ->
                part.takeIf { it.isNotEmpty() && (it == "0" || !it.startsWith('0')) && it.all(Char::isDigit) }
                    ?.toIntOrNull()
            }
            ?: return false
        if (octets.any { it == null }) return false
        val numbers = octets.filterNotNull()
        if (numbers.any { it !in 0..255 }) return false
        val a = numbers[0]
        val b = numbers[1]
        return a == 127 ||
            a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) || // Android emulator / link-local development.
            (a == 100 && b in 64..127) // CGNAT 100.64.0.0/10 — Tailscale.
    }

    /** Hosts recognized as local, but still requiring persisted explicit consent. */
    fun isAutoApprovedLocalHost(host: String): Boolean {
        val normalized = host.trim().lowercase(Locale.US).removeSuffix(".")
        return normalized == "localhost" ||
            normalized == "ip6-localhost" ||
            normalized == "::1" ||
            normalized == "127.0.0.1" ||
            normalized == "10.0.2.2"
    }

    fun check(snapshot: ConnectionSnapshot, url: HttpUrl) {
        if (ConnectionOrigin.isSecure(url)) return
        val policyUrl = if (url.scheme == "ws") {
            url.newBuilder().scheme("http").build()
        } else {
            url
        }
        if (!ConnectionOrigin.isCleartext(url) ||
            policyUrl.scheme != "http" ||
            !isVerifiedDestination(policyUrl.host) ||
            !snapshot.hasCleartextConsentFor(policyUrl)
        ) {
            throw IOException(
                "Cleartext Hermes connections require an explicit local-network confirmation",
            )
        }
    }
}
