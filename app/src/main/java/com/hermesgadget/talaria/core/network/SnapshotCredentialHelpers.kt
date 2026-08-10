/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.core.network

import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.security.CertificatePinnerFactory
import com.hermesgadget.talaria.domain.model.AuthProvidersResponse
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.PasswordLoginRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Password login helper whose URL, credentials, and cookie jar are snapshot-bound. */
class SnapshotPasswordSessionManager(
    private val snapshot: ConnectionSnapshot,
    private val cookieJar: PersistentCookieJar,
    private val currentSnapshot: () -> ConnectionSnapshot? = { snapshot },
) {
    private val lock = Any()
    @Volatile private var rejectedCredentialVersion: String? = null

    @Throws(IOException::class)
    fun ensureSession(requestUrl: HttpUrl) {
        requireCurrent()
        if (cookieJar.hasCookiesFor(requestUrl)) return
        synchronized(lock) {
            requireCurrent()
            if (cookieJar.hasCookiesFor(requestUrl)) return
            val username = snapshot.username.orEmpty()
            val password = snapshot.password.orEmpty()
            if (username.isBlank() || password.isBlank()) {
                throw IOException("Password authentication requires both username and password")
            }
            val credentialVersion = "${snapshot.connectionId}:${snapshot.authProvider}:$username:${password.hashCode()}"
            if (rejectedCredentialVersion == credentialVersion) {
                throw IOException("Password login previously failed; update the credentials before retrying")
            }

            val client = bootstrapClient(snapshot.profile)
            try {
                val provider = snapshot.authProvider.ifBlank { discoverSinglePasswordProvider(client) }
                val payload = JsonConfig.json.encodeToString(
                    PasswordLoginRequest(provider = provider, username = username, password = password),
                )
                val request = Request.Builder()
                    .url("${snapshot.baseUrl.trimEnd('/')}/auth/password-login")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                // The login body is the credential boundary. Recheck after
                // provider discovery and immediately before transmission.
                requireCurrent()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val detail = response.body?.string().orEmpty().take(512)
                        val message = "Hermes password login failed (${response.code})" +
                            detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                        if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                            throw PasswordLoginRejectedException(message)
                        }
                        throw IOException(message)
                    }
                }
                if (!cookieJar.hasCookiesFor(requestUrl)) {
                    throw IOException("Hermes accepted password login but did not return a usable session cookie")
                }
                rejectedCredentialVersion = null
            } catch (failure: PasswordLoginRejectedException) {
                rejectedCredentialVersion = credentialVersion
                throw failure
            }
        }
    }

    fun clearFailure() {
        rejectedCredentialVersion = null
    }

    private fun requireCurrent() {
        SnapshotAuthGuard.requireCurrent(snapshot, currentSnapshot())
    }

    private fun discoverSinglePasswordProvider(client: OkHttpClient): String {
        val request = Request.Builder()
            .url("${snapshot.baseUrl.trimEnd('/')}/api/auth/providers")
            .get()
            .build()
        val providers = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Could not discover Hermes auth providers (${response.code})")
            }
            val body = response.body?.string().orEmpty()
            runCatching { JsonConfig.json.decodeFromString<AuthProvidersResponse>(body) }
                .getOrElse { throw IOException("Hermes returned an invalid auth-provider response", it) }
                .providers
                .filter { it.supports_password }
        }
        return when {
            providers.size == 1 -> providers.single().name
            providers.isEmpty() -> throw IOException("Hermes did not advertise a password auth provider")
            else -> throw IOException("Choose a password provider: ${providers.joinToString { it.name }}")
        }
    }

    private fun bootstrapClient(profile: ConnectionProfile): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // A credential POST must never be replayed to a Location target.
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(CleartextPolicyInterceptor(snapshot))
            .addInterceptor(ResponseBodyLimitInterceptor())
            // Credential bootstrap follows the same per-hop origin and
            // cleartext checks as the main REST client.
            .addNetworkInterceptor(SnapshotOriginInterceptor(snapshot, currentSnapshot))
            .addNetworkInterceptor(CleartextPolicyInterceptor(snapshot))
            .addInterceptor(EmulatorLoopbackInterceptor())
        profile.pinSha256?.takeIf { it.isNotBlank() }?.let { pin ->
            builder.certificatePinner(CertificatePinnerFactory.forPin(profile.baseUrl, pin))
        }
        return builder.build()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    private class PasswordLoginRejectedException(message: String) : IOException(message)
}

/** OIDC refresh helper that never rereads a mutable profile or secret record. */
class SnapshotOidcTokenRefresher(
    private val store: SecureConnectionStore,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
    /** A refresh is single-flight only within the exact immutable scope that requested it. */
    private val singleFlights = ConcurrentHashMap<ConnectionSnapshot, Any>()

    fun accessToken(snapshot: ConnectionSnapshot): String? = synchronized(
        singleFlights.computeIfAbsent(snapshot) { Any() },
    ) {
        SnapshotAuthGuard.requireCurrent(snapshot, store.snapshotFor(snapshot.connectionId))
        val current = snapshot.bearerToken?.takeIf { it.isNotBlank() } ?: return@synchronized null
        val expiresAt = snapshot.oidcExpiresAt ?: return@synchronized current
        if (nowSeconds() < expiresAt - REFRESH_SKEW_SECONDS) return@synchronized current
        val refresh = snapshot.oidcRefreshToken?.takeIf { it.isNotBlank() }
            ?: return@synchronized current.takeIf { nowSeconds() < expiresAt }
        val base = snapshot.baseUrl.toHttpUrlOrNull() ?: return@synchronized current
        val url = base.newBuilder().addPathSegments("auth/native/refresh").build()
        val body = buildJsonObject {
            put("refresh_token", refresh)
            snapshot.oidcProvider?.takeIf { it.isNotBlank() }?.let { put("provider", it) }
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Refresh tokens are credential POST bodies, not browser navigations.
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(CleartextPolicyInterceptor(snapshot))
            .addInterceptor(ResponseBodyLimitInterceptor())
            .addNetworkInterceptor(
                SnapshotOriginInterceptor(snapshot) {
                    store.snapshotFor(snapshot.connectionId)
                },
            )
            .addNetworkInterceptor(CleartextPolicyInterceptor(snapshot))
            .addInterceptor(EmulatorLoopbackInterceptor())
        snapshot.pinSha256?.takeIf { it.isNotBlank() }?.let {
            builder.certificatePinner(CertificatePinnerFactory.forPin(snapshot.baseUrl, it))
        }
        val request = Request.Builder().url(url).post(body).build()
        try {
            // Recheck inside the per-snapshot single-flight immediately before
            // the refresh body is transmitted.
            SnapshotAuthGuard.requireCurrent(snapshot, store.snapshotFor(snapshot.connectionId))
            builder.build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use current.takeIf { nowSeconds() < expiresAt }
                val root = JsonConfig.json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                    ?: return@use current.takeIf { nowSeconds() < expiresAt }
                val access = root["access_token"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: return@use current.takeIf { nowSeconds() < expiresAt }
                val committed = store.updateOidcTokensIfSnapshot(
                    snapshot = snapshot,
                    accessToken = access,
                    refreshToken = root["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    expiresAt = root["expires_at"]?.jsonPrimitive?.longOrNull ?: 0,
                    provider = root["provider"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
                // Never hand a token to the caller when the exact snapshot CAS
                // rejected it. The caller must not transmit a stale refresh.
                if (committed) access else null
            }
        } catch (failure: IOException) {
            if (failure.message == SnapshotAuthGuard.CHANGED_MESSAGE ||
                failure.message == SnapshotAuthGuard.OIDC_CHANGED_MESSAGE
            ) {
                throw failure
            }
            current.takeIf { nowSeconds() < expiresAt }
        } catch (_: Throwable) {
            current.takeIf { nowSeconds() < expiresAt }
        }
    }

    private companion object {
        const val REFRESH_SKEW_SECONDS = 60
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
