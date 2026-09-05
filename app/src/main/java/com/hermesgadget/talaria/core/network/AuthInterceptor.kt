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
import okhttp3.MediaType.Companion.toMediaTypeOrNull

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
            ensureSnapshotStillStored()
            return chain.proceed(request)
        }
        val req = request.newBuilder()
        var oidcToken: String? = null
        when (snapshot.authMode) {
            AuthMode.SESSION_TOKEN -> snapshot.sessionToken
                ?.takeIf { it.isNotBlank() && request.header(SESSION_HEADER) == null }
                ?.let { raw ->
                    val token = sanitizeToken(raw)
                    // B15: a non-header-safe stored credential must fail the
                    // call at this boundary, not throw on the OkHttp
                    // dispatcher thread.
                    if (!isHeaderSafeCredential(token)) {
                        return unsendableCredentialResponse(request, "session token")
                    }
                    req.header(SESSION_HEADER, token)
                }
            AuthMode.BASIC -> {
                if (!isPasswordBootstrapPath(request.url.encodedPath)) {
                    passwordSessionManager(snapshot, request.url)
                }
                snapshot.sessionToken
                    ?.takeIf { it.isNotBlank() && request.header(SESSION_HEADER) == null }
                    ?.let { raw ->
                        val token = sanitizeToken(raw)
                        if (!isHeaderSafeCredential(token)) {
                            return unsendableCredentialResponse(request, "session token")
                        }
                        req.header(SESSION_HEADER, token)
                    }
            }
            AuthMode.BEARER -> snapshot.bearerToken?.takeIf { it.isNotBlank() }?.let {
                val token = sanitizeToken(it)
                if (!isHeaderSafeCredential(token)) {
                    return unsendableCredentialResponse(request, "bearer token")
                }
                req.header("Authorization", "Bearer $token")
            }
            AuthMode.OIDC_BROWSER -> {
                oidcTokenRefresher(snapshot)?.let {
                    val token = sanitizeToken(it)
                    if (!isHeaderSafeCredential(token)) {
                        return unsendableCredentialResponse(request, "bearer token")
                    }
                    oidcToken = token
                    req.header("Authorization", "Bearer $token")
                }
            }
            // NONE is intentionally credential-free. A retained token must not
            // follow a profile edited into an unauthenticated mode.
            AuthMode.NONE -> Unit
        }
        // Match Host expectations for DNS-rebinding guards when operator set a custom host header — not used by default.
        if (snapshot.authMode == AuthMode.OIDC_BROWSER && oidcToken != null) {
            if (oidcToken == snapshot.bearerToken) {
                ensureSnapshotStillStored()
            } else {
                SnapshotAuthGuard.requireCurrentWithBearer(
                    saved = snapshot,
                    current = connectionStore.snapshotFor(snapshot.connectionId),
                    bearerToken = oidcToken!!,
                )
            }
        } else {
            ensureSnapshotStillStored()
        }
        SnapshotAuthGuard.requireSameOrigin(snapshot, request.url)
        return chain.proceed(req.build())
    }

    private fun ensureSnapshotStillStored() {
        if (snapshot.connectionId == "__anonymous__") return
        SnapshotAuthGuard.requireCurrent(snapshot, connectionStore.snapshotFor(snapshot.connectionId))
    }

    companion object {
        const val SESSION_HEADER = "X-Hermes-Session-Token"

        /**
         * Strips characters that OkHttp rejects in header values (CR, LF, NUL,
         * other C0 controls) and trims surrounding whitespace. Pasted tokens
         * frequently carry a trailing newline; without this, [Interceptor]s
         * throw IllegalArgumentException on the OkHttp dispatcher thread, which
         * crashes the process (the app's coroutine try/catch cannot see it).
         */
        fun sanitizeToken(value: String): String {
            val stripped = buildString(value.length) {
                for (ch in value) {
                    if (ch.code >= 0x20 && ch.code != 0x7f) append(ch)
                }
            }
            return stripped.trim()
        }

        /**
         * B15: header values must be printable ASCII. sanitizeToken removes
         * C0/DEL but a stored credential can still carry non-ASCII characters,
         * and OkHttp 4.12 rejects those in header values with
         * IllegalArgumentException — thrown on the dispatcher thread, outside
         * the interceptor's IOException conversion boundary. Callers must check
         * this before building a credential header and surface a typed
         * recoverable error instead.
         */
        fun isHeaderSafeCredential(value: String): Boolean =
            value.isNotEmpty() && value.all { it.code in 0x20..0x7e }

        /**
         * Synthesized response for a stored credential that cannot be placed
         * in a header. Fails the call at the boundary (no network side
         * effects) with a typed, diagnosable status instead of throwing.
         */
        fun unsendableCredentialResponse(request: okhttp3.Request, credentialKind: String): okhttp3.Response {
            val body = okhttp3.ResponseBody.create(
                "application/json; charset=utf-8".toMediaTypeOrNull(),
                """{"ok":false,"error":"credential_unsendable","credential":"$credentialKind"}""",
            )
            return okhttp3.Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(581)
                .message("Stored $credentialKind contains characters that cannot be sent in an HTTP header")
                .body(body)
                .build()
        }

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
    private val currentSnapshot: (() -> ConnectionSnapshot?)? = null,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        currentSnapshot?.let { readCurrent ->
            val current = readCurrent()
            val authorization = original.header("Authorization")
            val bearer = authorization
                ?.takeIf { it.startsWith("Bearer ") }
                ?.removePrefix("Bearer ")
            if (bearer != null) {
                if (bearer == snapshot.bearerToken) {
                    SnapshotAuthGuard.requireCurrent(snapshot, current)
                } else {
                    SnapshotAuthGuard.requireCurrentWithBearer(snapshot, current, bearer)
                }
            } else {
                SnapshotAuthGuard.requireCurrent(snapshot, current)
            }
        }
        SnapshotAuthGuard.requireSameOrigin(snapshot, original.url)
        val request = if (SnapshotAuthGuard.suppressCredentials(original.url.encodedPath)) {
            original.newBuilder()
                .removeHeader("Authorization")
                .removeHeader(AuthInterceptor.SESSION_HEADER)
                .removeHeader("Cookie")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
    }
}
