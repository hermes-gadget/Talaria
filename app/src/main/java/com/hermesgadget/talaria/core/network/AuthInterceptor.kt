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
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches Hermes dashboard auth for the active connection profile.
 *
 * Loopback / token mode: `X-Hermes-Session-Token`
 * Gated password auth: Hermes session cookies minted by password-login
 * Gated native/bearer auth: Authorization bearer token
 */
class AuthInterceptor(
    private val connectionStore: SecureConnectionStore,
    private val oidcTokenRefresher: OidcTokenRefresher,
    private val passwordSessionManager: PasswordSessionManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val active = connectionStore.activeProfile()
        val secrets = active?.let { connectionStore.secretsFor(it.id) }
        val req = chain.request().newBuilder()
        when (active?.authMode) {
            AuthMode.SESSION_TOKEN -> secrets?.sessionToken?.takeIf { it.isNotBlank() }?.let {
                req.header(SESSION_HEADER, it)
            }
            AuthMode.BASIC -> {
                if (!isPasswordBootstrapPath(chain.request().url.encodedPath)) {
                    passwordSessionManager.ensureSession(active, chain.request().url)
                }
                secrets?.sessionToken?.takeIf { it.isNotBlank() }?.let {
                    req.header(SESSION_HEADER, it)
                }
            }
            AuthMode.BEARER -> secrets?.bearerToken?.takeIf { it.isNotBlank() }?.let {
                req.header("Authorization", "Bearer $it")
            }
            AuthMode.OIDC_BROWSER -> {
                if (!chain.request().url.encodedPath.contains("/auth/native/")) {
                    oidcTokenRefresher.accessToken(active)?.let {
                        req.header("Authorization", "Bearer $it")
                    }
                }
                secrets?.sessionToken?.takeIf { it.isNotBlank() }?.let {
                    req.header(SESSION_HEADER, it)
                }
            }
            AuthMode.NONE, null -> {
                secrets?.sessionToken?.takeIf { it.isNotBlank() }?.let {
                    req.header(SESSION_HEADER, it)
                }
            }
        }
        // Match Host expectations for DNS-rebinding guards when operator set a custom host header — not used by default.
        return chain.proceed(req.build())
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
