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

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.hermesgadget.talaria.BuildConfig
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.data.prefs.SettingsStore
import com.hermesgadget.talaria.core.security.CertificatePinnerFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Builds transport clients that are immutable with respect to one connection snapshot. */
class HermesClientFactory(
    private val connectionStore: SecureConnectionStore,
    private val settingsStore: SettingsStore,
) {
    private data class ClientBundle(
        val rest: OkHttpClient,
        val webSocket: OkHttpClient,
        val api: HermesApi,
        val cookieJar: PersistentCookieJar,
        val passwordSessionManager: SnapshotPasswordSessionManager,
    )

    private val oidcTokenRefresher = SnapshotOidcTokenRefresher(connectionStore)
    private val bundles = ConcurrentHashMap<ConnectionSnapshot, ClientBundle>()

    /** Capture the active profile, credentials, and logging policy before a request starts. */
    fun snapshot(): ConnectionSnapshot? = connectionStore.activeSnapshot()
        ?.withHttpLogging(settingsStore.httpLoggingEnabled)

    /** Resolve a persisted connection without consulting the mutable active selection. */
    fun snapshotFor(
        connectionId: String,
        managementProfile: String? = null,
    ): ConnectionSnapshot? = connectionStore.snapshotFor(connectionId, managementProfile)
        ?.withHttpLogging(settingsStore.httpLoggingEnabled)

    fun okHttp(snapshot: ConnectionSnapshot? = snapshot()): OkHttpClient =
        bundle(snapshot ?: ConnectionSnapshot.anonymous(settingsStore.httpLoggingEnabled)).rest

    /**
     * Minimal client for credential-free SPA-shell fetches (the one-tap
     * "Fetch token from dashboard" discovery and the loopback token sync).
     *
     * Deliberately NOT the store-bound bundle: a draft connection is never
     * persisted, so AuthInterceptor's ensureSnapshotStillStored() would throw
     * "connection changed" before the request leaves, and building a
     * PersistentCookieJar on the calling thread is wasted disk I/O. The shell
     * is public HTML with no credentials attached, so the only policy that
     * must apply is the cleartext gate (consent) plus the emulator Host
     * rewrite in debug builds.
     */
    fun shellClient(snapshot: ConnectionSnapshot): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(CleartextPolicyInterceptor(snapshot))
            .addNetworkInterceptor(CleartextPolicyInterceptor(snapshot))
            .apply {
                // Emulator loopback rewrite is dev scaffolding; never ship it in release.
                if (BuildConfig.DEBUG) addInterceptor(EmulatorLoopbackInterceptor())
                // Same opt-in observability as the credential-bearing bundle:
                // the SPA-shell fetch carries no secrets, so BASIC level with
                // only Host redacted is safe to enable for diagnostics.
                if (snapshot.httpLoggingEnabled) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                            redactHeader("Host")
                        },
                    )
                }
            }
            .build()

    fun api(snapshot: ConnectionSnapshot? = snapshot()): HermesApi =
        bundle(snapshot ?: ConnectionSnapshot.anonymous(settingsStore.httpLoggingEnabled)).api

    /**
     * Invalidate every old-scope client and cancel its calls. New calls must
     * obtain a bundle from a newly captured snapshot.
     */
    fun invalidate() {
        bundles.values.forEach { bundle ->
            bundle.rest.dispatcher.cancelAll()
            bundle.webSocket.dispatcher.cancelAll()
            bundle.rest.connectionPool.evictAll()
            bundle.webSocket.connectionPool.evictAll()
            bundle.cookieJar.clear()
            bundle.passwordSessionManager.clearFailure()
        }
        bundles.clear()
    }

    /** WebSocket clients intentionally have no HTTP logger: URLs carry auth queries. */
    fun webSocketClient(snapshot: ConnectionSnapshot? = snapshot()): OkHttpClient =
        bundle(snapshot ?: ConnectionSnapshot.anonymous(false)).webSocket

    private fun bundle(snapshot: ConnectionSnapshot): ClientBundle {
        // A refreshed token, edited URL, or profile switch creates a new key. Do
        // not retain the old credential-bearing bundle for the same connection.
        bundles.keys
            .filter { it.connectionId == snapshot.connectionId && it != snapshot }
            .forEach { old ->
                bundles.remove(old)?.let { close(it) }
            }
        return bundles[snapshot] ?: synchronized(bundles) {
            bundles[snapshot] ?: buildBundle(snapshot).also { bundles[snapshot] = it }
        }
    }

    private fun close(bundle: ClientBundle) {
        bundle.rest.dispatcher.cancelAll()
        bundle.webSocket.dispatcher.cancelAll()
        bundle.rest.connectionPool.evictAll()
        bundle.webSocket.connectionPool.evictAll()
        bundle.cookieJar.clear()
        bundle.passwordSessionManager.clearFailure()
    }

    private fun buildBundle(snapshot: ConnectionSnapshot): ClientBundle {
        val cookieJar = PersistentCookieJar()
        val passwordSessionManager = SnapshotPasswordSessionManager(
            snapshot = snapshot,
            cookieJar = cookieJar,
            currentSnapshot = { connectionStore.snapshotFor(snapshot.connectionId) },
        )
        val auth = AuthInterceptor(
            snapshot = snapshot,
            connectionStore = connectionStore,
            oidcTokenRefresher = oidcTokenRefresher::accessToken,
            passwordSessionManager = { _, url -> passwordSessionManager.ensureSession(url) },
        )

        fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(auth)
            .addInterceptor(ProfileQueryInterceptor(snapshot))
            // Application interceptor: covers the WebSocket upgrade handshake (network
            // interceptors do not run for WebSockets).
            .addInterceptor(CleartextPolicyInterceptor(snapshot))
            // Redirects/retries do not re-enter application interceptors. Check
            // their final origin before a credential-bearing request hits the wire.
            .addNetworkInterceptor(
                SnapshotOriginInterceptor(snapshot) {
                    connectionStore.snapshotFor(snapshot.connectionId)
                },
            )
            // Network interceptor: runs again per redirect/retry, so an https->http
            // 30x cannot bypass the cleartext gate after the first hop.
            .addNetworkInterceptor(CleartextPolicyInterceptor(snapshot))
            .apply {
                // Emulator loopback rewrite is dev scaffolding; never ship it in release.
                if (BuildConfig.DEBUG) addInterceptor(EmulatorLoopbackInterceptor())
            }

        val restBuilder = baseBuilder()
        // Retrofit converters normally materialize the entire response before
        // returning a typed value. This wrapper rejects an oversized declared
        // body and aborts chunked/unknown-length reads at the endpoint budget
        // before a converter or feature-level parser can retain more data.
        restBuilder.addInterceptor(ResponseBodyLimitInterceptor())
        if (snapshot.httpLoggingEnabled) {
            restBuilder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                    redactHeader(AuthInterceptor.SESSION_HEADER)
                },
            )
        }
        snapshot.pinSha256?.takeIf { it.isNotBlank() }?.let { pin ->
            restBuilder.certificatePinner(CertificatePinnerFactory.forPin(snapshot.baseUrl, pin))
        }

        val webSocketBuilder = baseBuilder()
        snapshot.pinSha256?.takeIf { it.isNotBlank() }?.let { pin ->
            webSocketBuilder.certificatePinner(CertificatePinnerFactory.forPin(snapshot.baseUrl, pin))
        }

        val rest = restBuilder.build()
        val webSocket = webSocketBuilder.build()
        val retrofit = Retrofit.Builder()
            .baseUrl(snapshot.baseUrl.trimEnd('/').plus('/'))
            .client(rest)
            .addConverterFactory(JsonConfig.json.asConverterFactory("application/json".toMediaType()))
            .build()
        return ClientBundle(
            rest = rest,
            webSocket = webSocket,
            api = retrofit.create(HermesApi::class.java),
            cookieJar = cookieJar,
            passwordSessionManager = passwordSessionManager,
        )
    }
}
