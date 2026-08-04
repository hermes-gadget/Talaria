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


package com.hermesgadget.talaria.core.data.repo

import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.network.CleartextPolicy
import com.hermesgadget.talaria.core.network.HermesClientFactory
import com.hermesgadget.talaria.core.network.WsAuthHelper
import com.hermesgadget.talaria.core.security.CertificatePinnerFactory
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import com.hermesgadget.talaria.domain.model.StatusResponse
import com.hermesgadget.talaria.domain.model.normalizeManagementProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

class ConnectionRepository(
    private val store: SecureConnectionStore,
    private val clientFactory: HermesClientFactory,
    private val wsAuthHelper: WsAuthHelper,
) {
    val profiles: StateFlow<List<ConnectionProfile>> = store.profiles
    val activeId: StateFlow<String?> = store.activeId

    fun active(): ConnectionProfile? = store.activeProfile()

    suspend fun save(
        name: String,
        baseUrl: String,
        authMode: AuthMode,
        username: String?,
        authProvider: String,
        sessionToken: String?,
        password: String?,
        bearerToken: String?,
        managementProfile: String,
        pinSha256: String?,
        existingId: String? = null,
        allowCleartext: Boolean? = null,
        cleartextConsentRecorded: Boolean? = null,
        keepSessionToken: Boolean = true,
        keepPassword: Boolean = true,
        keepBearerToken: Boolean = true,
        keepOidcTokens: Boolean = true,
    ): ConnectionProfile = withContext(Dispatchers.IO) {
        val id = existingId ?: UUID.randomUUID().toString()
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val parsedBase = normalizedBase.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Dashboard URL must be a valid http:// or https:// URL")
        require(parsedBase.scheme == "http" || parsedBase.scheme == "https") {
            "Dashboard URL must use http:// or https://"
        }
        require(parsedBase.username.isEmpty() && parsedBase.password.isEmpty()) {
            "Put credentials in the authentication fields, not in the dashboard URL"
        }
        require(parsedBase.query == null && parsedBase.fragment == null) {
            "Dashboard URL cannot contain a query string or fragment"
        }
        val normalizedPin = pinSha256?.trim()?.takeIf { it.isNotEmpty() }?.let { pin ->
            require(parsedBase.isHttps) { "TLS pinning requires an https:// dashboard URL" }
            CertificatePinnerFactory.normalizePin(pin)
        }
        val previousSnapshot = existingId?.let { existing -> store.snapshotFor(existing) }
        val previousProfile = previousSnapshot?.profile
        val previousSecrets = previousSnapshot?.secrets ?: ConnectionSecrets()
        val sameBase = previousProfile?.baseUrl?.trim()?.trimEnd('/') == normalizedBase
        val sameAuthMode = previousProfile?.authMode == authMode
        val sameAuthProvider = authMode !in setOf(AuthMode.BASIC, AuthMode.OIDC_BROWSER) ||
            previousProfile?.authProvider == authProvider.trim()
        // A URL or auth-mode edit is a trust-boundary change. Blank fields may
        // retain only secrets that still belong to the same boundary.
        val retained = if (sameBase && sameAuthMode && sameAuthProvider) {
            previousSecrets
        } else {
            ConnectionSecrets()
        }
        val cleartextAllowed = if (parsedBase.isHttps) {
            false
        } else {
            allowCleartext
                ?: previousProfile?.takeIf { sameBase }?.allowCleartext
                ?: CleartextPolicy.isAutoApprovedLocalHost(parsedBase.host)
        }
        // HTTPS never needs consent. Legacy records (null consent) keep their
        // implicit approval; a URL change drops consent (sameBase=false -> null -> false).
        val consentRecorded = when {
            parsedBase.isHttps -> true
            cleartextConsentRecorded != null -> cleartextConsentRecorded
            previousProfile?.takeIf { sameBase } != null -> previousProfile.cleartextConsentRecorded ?: true
            else -> false
        }
        require(parsedBase.isHttps || (cleartextAllowed && CleartextPolicy.isVerifiedDestination(parsedBase.host))) {
            "Cleartext is restricted to an explicitly confirmed local/private Hermes destination"
        }
        val secrets = when (authMode) {
            AuthMode.NONE -> ConnectionSecrets()
            AuthMode.SESSION_TOKEN -> ConnectionSecrets(
                sessionToken = sessionToken?.takeIf { it.isNotBlank() }
                    ?: retained.sessionToken?.takeIf { keepSessionToken },
            )
            AuthMode.BASIC -> ConnectionSecrets(
                password = password?.takeIf { it.isNotBlank() }
                    ?: retained.password?.takeIf { keepPassword },
            )
            AuthMode.BEARER -> ConnectionSecrets(
                bearerToken = bearerToken?.takeIf { it.isNotBlank() }
                    ?: retained.bearerToken?.takeIf { keepBearerToken },
            )
            AuthMode.OIDC_BROWSER -> ConnectionSecrets(
                bearerToken = bearerToken?.takeIf { it.isNotBlank() }
                    ?: retained.bearerToken?.takeIf { keepBearerToken },
                oidcRefreshToken = retained.oidcRefreshToken?.takeIf { keepOidcTokens },
                oidcExpiresAt = retained.oidcExpiresAt.takeIf { keepOidcTokens },
                oidcProvider = retained.oidcProvider?.takeIf { keepOidcTokens },
            )
        }
        val profile = ConnectionProfile(
            id = id,
            name = name.ifBlank { normalizedBase },
            baseUrl = normalizedBase,
            authMode = authMode,
            username = username,
            authProvider = authProvider.trim(),
            hasPassword = !secrets.password.isNullOrBlank(),
            hasSessionToken = !secrets.sessionToken.isNullOrBlank(),
            hasBearerToken = !secrets.bearerToken.isNullOrBlank(),
            managementProfile = normalizeManagementProfile(managementProfile),
            pinSha256 = normalizedPin,
            allowCleartext = cleartextAllowed,
            cleartextConsentRecorded = consentRecorded,
            createdAt = previousProfile?.createdAt ?: System.currentTimeMillis(),
            lastConnectedAt = previousProfile?.lastConnectedAt,
        )
        store.upsert(profile, secrets)
        clientFactory.invalidate()
        profile
    }

    fun setActive(id: String) {
        store.setActive(id)
        clientFactory.invalidate()
    }

    fun delete(id: String) {
        store.delete(id)
        clientFactory.invalidate()
    }

    suspend fun testConnection(): Result<StatusResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val profile = store.activeProfile()
                ?: error("Save a connection profile before testing it")
            val snapshot = clientFactory.snapshotFor(profile.id, profile.managementProfile)
                ?: error("The selected Hermes connection is no longer available")
            val api = clientFactory.api(snapshot)
            val status = api.getStatus(profile = snapshot.managementProfile)
            if (status.auth_required == true) {
                check(profile.authMode != AuthMode.NONE) {
                    "This Hermes dashboard requires authentication"
                }
                check(profile.authMode != AuthMode.SESSION_TOKEN) {
                    "This gated Hermes dashboard requires password, bearer, or browser authentication"
                }
                // This protected endpoint proves that password cookies or bearer
                // tokens were actually accepted; /api/status is intentionally public.
                api.authMe()
            } else {
                // Loopback Hermes publishes its generated session token in the
                // SPA shell. Discover it before probing a protected endpoint so
                // a freshly saved, zero-config local connection can be tested.
                wsAuthHelper.invalidate()
                wsAuthHelper.authQueryParam()
                // Prove the legacy session token/header on a protected endpoint.
                api.getSessions(limit = 1, profile = snapshot.managementProfile)
            }
            store.profiles.value.find { it.id == profile.id }
                ?.copy(lastConnectedAt = System.currentTimeMillis())
                ?.let(store::upsert)
            status
        }
    }
}
