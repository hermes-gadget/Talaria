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
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        ensureSnapshotStillStored()
        SnapshotAuthGuard.requireSameOrigin(snapshot, request.url)
        if (SnapshotAuthGuard.suppressCredentials(request.url.encodedPath)) {
            return chain.proceed(request)
        }
        val req = request.newBuilder()
        when (snapshot.authMode) {
            AuthMode.SESSION_TOKEN -> snapshot.sessionToken
                ?.takeIf { it.isNotBlank() && request.header(SESSION_HEADER) == null }
                ?.let {
                req.header(SESSION_HEADER, it)
            }
            AuthMode.BASIC -> {
                if (!isPasswordBootstrapPath(request.url.encodedPath)) {
                    passwordSessionManager(snapshot, request.url)
                }
                snapshot.sessionToken
                    ?.takeIf { it.isNotBlank() && request.header(SESSION_HEADER) == null }
                    ?.let {
                    req.header(SESSION_HEADER, it)
                }
            }
            AuthMode.BEARER -> snapshot.bearerToken?.takeIf { it.isNotBlank() }?.let {
                req.header("Authorization", "Bearer $it")
            }
            AuthMode.OIDC_BROWSER -> {
                oidcTokenRefresher(snapshot)?.let {
                    req.header("Authorization", "Bearer $it")
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
        if (snapshot.connectionId == "__anonymous__") return
        SnapshotAuthGuard.requireCurrent(snapshot, connectionStore.snapshotFor(snapshot.connectionId))
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

/** Re-check every redirect/retry before a credential-bearing request reaches the network. */
internal class SnapshotOriginInterceptor(
    private val snapshot: ConnectionSnapshot,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        SnapshotAuthGuard.requireSameOrigin(snapshot, chain.request().url)
        val request = if (SnapshotAuthGuard.suppressCredentials(chain.request().url.encodedPath)) {
            chain.request().newBuilder()
                .removeHeader("Authorization")
                .removeHeader(AuthInterceptor.SESSION_HEADER)
                .removeHeader("Cookie")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
