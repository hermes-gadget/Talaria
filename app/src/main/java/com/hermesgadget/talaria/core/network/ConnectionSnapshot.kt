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
import java.io.IOException

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
    val scopeId: String get() = profile.scopeId()

    /** Compare the parts that make a request unsafe to reuse after an edit. */
    fun sameTransportAs(other: ConnectionSnapshot): Boolean =
        connectionId == other.connectionId &&
            baseUrl == other.baseUrl &&
            managementProfile == other.managementProfile &&
            authMode == other.authMode &&
            username == other.username &&
            authProvider == other.authProvider &&
            pinSha256 == other.pinSha256 &&
            allowCleartext == other.allowCleartext

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
                    allowCleartext = true,
                    createdAt = 0L,
                ),
                secrets = ConnectionSecrets(),
                httpLoggingEnabled = httpLoggingEnabled,
            )
    }
}

/** Cleartext is only a supported transport for explicitly verified local hosts. */
object CleartextPolicy {
    fun isVerifiedDestination(host: String): Boolean {
        val normalized = host.trim().lowercase().removeSuffix(".")
        if (isAutoApprovedLocalHost(normalized)) {
            return true
        }
        val octets = normalized.split('.')
            .takeIf { it.size == 4 }
            ?.map { it.toIntOrNull() }
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
            (a == 169 && b == 254) // Android emulator / link-local development.
    }

    /** Hosts safe to allow for a new profile without an explicit confirmation. */
    fun isAutoApprovedLocalHost(host: String): Boolean {
        val normalized = host.trim().lowercase().removeSuffix(".")
        return normalized == "localhost" ||
            normalized == "ip6-localhost" ||
            normalized == "::1" ||
            normalized == "127.0.0.1" ||
            normalized == "10.0.2.2"
    }

    fun check(snapshot: ConnectionSnapshot, url: HttpUrl) {
        if (url.isHttps) return
        if (url.scheme != "http" || !snapshot.allowCleartext || !isVerifiedDestination(url.host)) {
            throw IOException(
                "Cleartext Hermes connections require an explicit local-network confirmation",
            )
        }
    }
}
