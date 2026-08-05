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
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import com.hermesgadget.talaria.core.util.suspendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    private data class TransportScope(
        val connectionId: String,
        val origin: String,
        val authMode: AuthMode,
        val authProvider: String,
        val managementProfile: String,
        val secrets: ConnectionSecrets,
    )

    private val mutex = Mutex()
    /** Includes credentials so token edits cannot reuse discovery from an older snapshot. */
    @Volatile private var cachedAuthRequired: Pair<TransportScope, Boolean>? = null

    suspend fun invalidate() {
        mutex.withLock { cachedAuthRequired = null }
    }

    /**
     * Returns e.g. `ticket=…` or `token=…` (without leading `?` / `&`).
     * Empty string when no credentials are available.
     */
    suspend fun authQueryParam(snapshot: ConnectionSnapshot): String = try {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                SnapshotAuthGuard.requireCurrent(
                    snapshot,
                    connectionStore.snapshotFor(snapshot.connectionId),
                )
                val scope = snapshot.transportScope()
                val api = clientFactory.api(snapshot)
                val authRequired = cachedAuthRequired
                    ?.takeIf { it.first == scope }
                    ?.second
                    ?: run {
                        val discovery = suspendResult {
                            api.getStatus(profile = snapshot.managementProfile).auth_required == true
                        }
                        discovery.exceptionOrNull()?.rethrowIfCancellationLike()
                        discovery.getOrDefault(
                            snapshot.authMode == AuthMode.BASIC || snapshot.authMode == AuthMode.OIDC_BROWSER,
                        ).also { cachedAuthRequired = scope to it }
                    }

                if (authRequired) {
                    val ticketResult = suspendResult { api.wsTicket().ticket }
                    ticketResult.exceptionOrNull()?.rethrowIfCancellationLike()
                    val ticket = ticketResult.getOrNull()
                    if (!ticket.isNullOrBlank()) return@withLock "ticket=${ticket.trim()}"
                }

                // Loopback dashboards embed the process session token in the SPA shell.
                // REST often works without it when auth_required=false, but /api/pty and
                // /api/ws reject the previous process token after a dashboard restart.
                // Always prefer the currently advertised token here: the encrypted value
                // is only a fallback for a temporarily unavailable SPA shell.
                if (!authRequired) {
                    fetchLoopbackSessionToken(snapshot)?.let { current ->
                        SnapshotAuthGuard.requireCurrent(
                            snapshot,
                            connectionStore.snapshotFor(snapshot.connectionId),
                        )
                        // Keep the REST path in lockstep with the WS path. REST
                        // auth uses the STORED token while WS uses this freshly
                        // discovered one; without syncing, a stored token that no
                        // longer matches the dashboard's process token makes every
                        // REST call 401 (empty rail, transcripts never load) while
                        // the PTY still connects — the app looks Live but chats
                        // never update. Sync is idempotent and best-effort.
                        if (current != snapshot.sessionToken) {
                            runCatching {
                                connectionStore.updateSessionToken(snapshot.connectionId, current)
                            }
                        }
                        return@withLock "token=$current"
                    }
                }

                snapshot.sessionToken?.takeIf { it.isNotBlank() }?.let {
                    return@withLock "token=${it.trim()}"
                }
                ""
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        failure.rethrowIfCancellationLike()
        throw failure
    }

    /**
     * Fetch the loopback session token from the dashboard's SPA shell.
     * Returns null when the shell is unreachable or carries no token.
     * Public so the connect screen can offer one-tap token discovery
     * instead of requiring a manual paste (which is how stale/malformed
     * tokens historically ended up stored).
     */
    suspend fun fetchLoopbackSessionToken(snapshot: ConnectionSnapshot): String? {
        val base = snapshot.baseUrl.trimEnd('/')
        val req = Request.Builder()
            .url("$base/")
            .header("Accept", "text/html")
            .get()
            .build()
        val result = suspendResult {
            clientFactory.okHttp(snapshot).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                SESSION_TOKEN_RE.find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            }
        }
        result.exceptionOrNull()?.rethrowIfCancellationLike()
        return result.getOrNull()
    }

    companion object {
        private val SESSION_TOKEN_RE =
            Regex("""__HERMES_SESSION_TOKEN__\s*=\s*["']([^"']+)["']""")

        fun explainCloseCode(code: Int): String? = when (code) {
            4401 -> "WebSocket auth failed (4401). Sign in again or refresh the session token."
            4403 -> "WebSocket rejected (4403). Check Host/peer guards — remote dashboards must bind non-loopback and match the URL host."
            4404 -> "WebSocket target not found (4404). The durable Hermes session may no longer exist."
            4408 -> "WebSocket policy rejected (4408). Check the dashboard policy and connection scope before retrying."
            else -> null
        }
    }

    private fun ConnectionSnapshot.transportScope(): TransportScope {
        val base = baseUrl.toHttpUrlOrNull()
        val origin = if (base == null) baseUrl else "${base.scheme}://${base.host}:${base.port}"
        return TransportScope(
            connectionId = connectionId,
            origin = origin,
            authMode = authMode,
            authProvider = authProvider,
            managementProfile = managementProfile,
            secrets = secrets,
        )
    }

    private fun Throwable.rethrowIfCancellationLike() {
        var current: Throwable? = this
        while (current != null) {
            if (current is CancellationException) throw current
            if (current.message.orEmpty().contains("operation was safely canceled", ignoreCase = true) ||
                current.message.orEmpty().contains("operation was safely cancelled", ignoreCase = true)
            ) {
                throw CancellationException("WebSocket authentication canceled", current)
            }
            current = current.cause
        }
    }
}
