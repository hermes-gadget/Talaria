/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.nousresearch.talaria.core.network

import com.nousresearch.talaria.core.data.prefs.SecureConnectionStore
import com.nousresearch.talaria.core.security.CertificatePinnerFactory
import com.nousresearch.talaria.domain.model.AuthProvidersResponse
import com.nousresearch.talaria.domain.model.ConnectionProfile
import com.nousresearch.talaria.domain.model.PasswordLoginRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Restores a password-provider dashboard session when the in-memory cookie jar
 * is empty (notably after process restart). This client deliberately has no
 * [AuthInterceptor], so the public login bootstrap cannot recurse.
 */
class PasswordSessionManager(
    private val store: SecureConnectionStore,
    private val cookieJar: PersistentCookieJar,
) {
    private val lock = Any()
    @Volatile private var rejectedCredentialVersion: String? = null

    @Throws(IOException::class)
    fun ensureSession(profile: ConnectionProfile, requestUrl: HttpUrl) {
        if (cookieJar.hasCookiesFor(requestUrl)) return
        synchronized(lock) {
            if (cookieJar.hasCookiesFor(requestUrl)) return

            val current = store.activeProfile()?.takeIf { it.id == profile.id } ?: profile
            val secrets = store.secretsFor(current.id)
            val password = secrets.password.orEmpty()
            val username = current.username.orEmpty()
            if (username.isBlank() || password.isBlank()) {
                throw IOException("Password authentication requires both username and password")
            }
            val credentialVersion = "${current.id}:${current.authProvider}:${username}:${password.hashCode()}"
            if (rejectedCredentialVersion == credentialVersion) {
                throw IOException("Password login previously failed; update the credentials before retrying")
            }

            val client = bootstrapClient(current)
            try {
                val provider = current.authProvider.ifBlank {
                    discoverSinglePasswordProvider(client, current)
                }
                val payload = JsonConfig.json.encodeToString(
                    PasswordLoginRequest(
                        provider = provider,
                        username = username,
                        password = password,
                    ),
                )
                val request = Request.Builder()
                    .url("${current.baseUrl.trimEnd('/')}/auth/password-login")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
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

    private fun discoverSinglePasswordProvider(
        client: OkHttpClient,
        profile: ConnectionProfile,
    ): String {
        val request = Request.Builder()
            .url("${profile.baseUrl.trimEnd('/')}/api/auth/providers")
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
        val selected = providers.singleOrNull() ?: when {
            providers.isEmpty() -> throw IOException("Hermes did not advertise a password auth provider")
            else -> throw IOException(
                "Choose a password provider: ${providers.joinToString { it.name }}",
            )
        }
        store.upsert(profile.copy(authProvider = selected.name))
        return selected.name
    }

    private fun bootstrapClient(profile: ConnectionProfile): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor(EmulatorLoopbackInterceptor())
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
