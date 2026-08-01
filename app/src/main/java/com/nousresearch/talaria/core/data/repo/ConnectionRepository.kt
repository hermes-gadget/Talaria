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


package com.nousresearch.talaria.core.data.repo

import com.nousresearch.talaria.core.data.prefs.SecureConnectionStore
import com.nousresearch.talaria.core.network.HermesClientFactory
import com.nousresearch.talaria.core.network.WsAuthHelper
import com.nousresearch.talaria.core.security.CertificatePinnerFactory
import com.nousresearch.talaria.domain.model.AuthMode
import com.nousresearch.talaria.domain.model.ConnectionProfile
import com.nousresearch.talaria.domain.model.ConnectionSecrets
import com.nousresearch.talaria.domain.model.StatusResponse
import com.nousresearch.talaria.domain.model.normalizeManagementProfile
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
        val previousProfile = existingId?.let { existing -> store.profiles.value.find { it.id == existing } }
        val prev = if (existingId != null) store.secretsFor(id) else ConnectionSecrets()
        val profile = ConnectionProfile(
            id = id,
            name = name.ifBlank { normalizedBase },
            baseUrl = normalizedBase,
            authMode = authMode,
            username = username,
            authProvider = authProvider.trim(),
            hasPassword = !password.isNullOrBlank() || !prev.password.isNullOrBlank(),
            hasSessionToken = !sessionToken.isNullOrBlank() || !prev.sessionToken.isNullOrBlank(),
            hasBearerToken = !bearerToken.isNullOrBlank() || !prev.bearerToken.isNullOrBlank(),
            managementProfile = normalizeManagementProfile(managementProfile),
            pinSha256 = normalizedPin,
            allowCleartext = parsedBase.scheme == "http",
            createdAt = previousProfile?.createdAt ?: System.currentTimeMillis(),
            lastConnectedAt = previousProfile?.lastConnectedAt,
        )
        val secrets = ConnectionSecrets(
            sessionToken = sessionToken?.takeIf { it.isNotBlank() } ?: prev.sessionToken,
            password = password?.takeIf { it.isNotBlank() } ?: prev.password,
            bearerToken = bearerToken?.takeIf { it.isNotBlank() } ?: prev.bearerToken,
            oidcRefreshToken = prev.oidcRefreshToken,
            oidcExpiresAt = prev.oidcExpiresAt,
            oidcProvider = prev.oidcProvider,
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
            val api = clientFactory.api()
            val profile = store.activeProfile()
                ?: error("Save a connection profile before testing it")
            val status = api.getStatus()
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
                api.getSessions(limit = 1)
            }
            store.profiles.value.find { it.id == profile.id }
                ?.copy(lastConnectedAt = System.currentTimeMillis())
                ?.let(store::upsert)
            status
        }
    }
}
