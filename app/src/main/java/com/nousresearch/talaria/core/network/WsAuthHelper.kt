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

package com.nousresearch.talaria.core.network

import com.nousresearch.talaria.core.data.prefs.SecureConnectionStore
import com.nousresearch.talaria.domain.model.AuthMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Builds WebSocket auth query params matching dashboard `buildWsUrl`:
 * - Loopback / token mode → `token=` (Hermes still requires
 *   `__HERMES_SESSION_TOKEN__` even when `auth_required=false`)
 * - Gated dashboards → single-use `ticket=` from `POST /api/auth/ws-ticket`
 */
class WsAuthHelper(
    private val clientFactory: HermesClientFactory,
    private val connectionStore: SecureConnectionStore,
) {
    private val mutex = Mutex()
    /** Auth policy is connection-scoped; two saved profiles may target different gates. */
    @Volatile private var cachedAuthRequired: Pair<String, Boolean>? = null

    suspend fun invalidate() {
        mutex.withLock { cachedAuthRequired = null }
    }

    /**
     * Returns e.g. `ticket=…` or `token=…` (without leading `?` / `&`).
     * Empty string when no credentials are available.
     */
    suspend fun authQueryParam(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val profile = connectionStore.activeProfile() ?: return@withLock ""
            val secrets = connectionStore.secretsFor(profile.id)
            val authRequired = cachedAuthRequired
                ?.takeIf { it.first == profile.id }
                ?.second
                ?: runCatching {
                    clientFactory.api().getStatus().auth_required == true
                }.getOrDefault(
                    profile.authMode == AuthMode.BASIC || profile.authMode == AuthMode.OIDC_BROWSER,
                ).also { cachedAuthRequired = profile.id to it }

            if (authRequired) {
                val ticket = runCatching { clientFactory.api().wsTicket().ticket }.getOrNull()
                if (!ticket.isNullOrBlank()) return@withLock "ticket=${ticket.trim()}"
            }

            // Loopback dashboards embed the process session token in the SPA shell.
            // REST often works without it when auth_required=false, but /api/pty and
            // /api/ws reject the previous process token after a dashboard restart.
            // Always prefer the currently advertised token here: the encrypted value
            // is only a fallback for a temporarily unavailable SPA shell.
            if (!authRequired) {
                fetchLoopbackSessionToken(profile.baseUrl)?.let { current ->
                    connectionStore.updateSessionToken(profile.id, current)
                    return@withLock "token=$current"
                }
            }

            secrets.sessionToken?.takeIf { it.isNotBlank() }?.let {
                return@withLock "token=${it.trim()}"
            }
            ""
        }
    }

    private fun fetchLoopbackSessionToken(baseUrl: String): String? {
        val base = baseUrl.trimEnd('/')
        val req = Request.Builder()
            .url("$base/")
            .header("Accept", "text/html")
            .get()
            .build()
        return runCatching {
            clientFactory.okHttp().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                SESSION_TOKEN_RE.find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    companion object {
        private val SESSION_TOKEN_RE =
            Regex("""__HERMES_SESSION_TOKEN__\s*=\s*["']([^"']+)["']""")

        fun explainCloseCode(code: Int): String? = when (code) {
            4401 -> "WebSocket auth failed (4401). Sign in again or refresh the session token."
            4403 -> "WebSocket rejected (4403). Check Host/peer guards — remote dashboards must bind non-loopback and match the URL host."
            else -> null
        }
    }
}
