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

import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.domain.model.AuthMode
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Attaches Hermes dashboard auth for one immutable connection snapshot.
 *
 * Loopback / token mode: `X-Hermes-Session-Token`
 * Gated password auth: Hermes session cookies minted by password-login
 * Gated native/bearer auth: Authorization bearer token
 */
class AuthInterceptor(
    private val snapshot: ConnectionSnapshot,
    private val connectionStore: SecureConnectionStore,
    private val oidcTokenRefresher: (ConnectionSnapshot) -> String?,
    private val passwordSessionManager: (ConnectionSnapshot, HttpUrl) -> Unit,
) : Interceptor {
    /** Compatibility constructor for callers that have not yet captured a snapshot. */
    constructor(
        connectionStore: SecureConnectionStore,
        oidcTokenRefresher: OidcTokenRefresher,
        passwordSessionManager: PasswordSessionManager,
    ) : this(
        snapshot = connectionStore.activeSnapshot() ?: ConnectionSnapshot.anonymous(),
        connectionStore = connectionStore,
        oidcTokenRefresher = { bound -> oidcTokenRefresher.accessToken(bound.profile) },
        passwordSessionManager = { bound, url -> passwordSessionManager.ensureSession(bound.profile, url) },
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        ensureSnapshotStillStored()
        val req = chain.request().newBuilder()
        when (snapshot.authMode) {
            AuthMode.SESSION_TOKEN -> snapshot.sessionToken?.takeIf { it.isNotBlank() }?.let {
                req.header(SESSION_HEADER, it)
            }
            AuthMode.BASIC -> {
                if (!isPasswordBootstrapPath(chain.request().url.encodedPath)) {
                    passwordSessionManager(snapshot, chain.request().url)
                }
                snapshot.sessionToken?.takeIf { it.isNotBlank() }?.let {
                    req.header(SESSION_HEADER, it)
                }
            }
            AuthMode.BEARER -> snapshot.bearerToken?.takeIf { it.isNotBlank() }?.let {
                req.header("Authorization", "Bearer $it")
            }
            AuthMode.OIDC_BROWSER -> {
                if (!chain.request().url.encodedPath.contains("/auth/native/")) {
                    oidcTokenRefresher(snapshot)?.let {
                        req.header("Authorization", "Bearer $it")
                    }
                }
            }
            // NONE is intentionally credential-free. A retained token must not
            // follow a profile edited into an unauthenticated mode.
            AuthMode.NONE -> Unit
        }
        // Match Host expectations for DNS-rebinding guards when operator set a custom host header — not used by default.
        return chain.proceed(req.build())
    }

    private fun ensureSnapshotStillStored() {
        val currentProfile = connectionStore.profileFor(snapshot.connectionId)
        if (currentProfile == null) {
            if (snapshot.connectionId == "__anonymous__") return
            throw IOException("The Hermes connection was deleted")
        }
        if (currentProfile.baseUrl != snapshot.profile.baseUrl ||
            currentProfile.authMode != snapshot.profile.authMode ||
            currentProfile.username != snapshot.profile.username ||
            currentProfile.authProvider != snapshot.profile.authProvider ||
            currentProfile.managementProfile != snapshot.profile.managementProfile ||
            currentProfile.pinSha256 != snapshot.profile.pinSha256 ||
            currentProfile.allowCleartext != snapshot.profile.allowCleartext ||
            connectionStore.secretsFor(snapshot.connectionId) != snapshot.secrets
        ) {
            throw IOException("The Hermes connection changed while this request was starting")
        }
    }

    companion object {
        const val SESSION_HEADER = "X-Hermes-Session-Token"

        private fun isPasswordBootstrapPath(path: String): Boolean =
            path == "/api/status" ||
                path == "/api/auth/providers" ||
                path == "/auth/password-login" ||
                path.startsWith("/auth/native/")
    }
}
