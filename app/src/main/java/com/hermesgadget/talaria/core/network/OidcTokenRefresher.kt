/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.hermesgadget.talaria.core.network

import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.security.CertificatePinnerFactory
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Synchronous, single-flight refresh used from OkHttp's interceptor thread. */
class OidcTokenRefresher(
    private val store: SecureConnectionStore,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
    private val lock = Any()

    fun accessToken(profile: ConnectionProfile): String? = synchronized(lock) {
        val secrets = store.secretsFor(profile.id)
        val current = secrets.bearerToken?.takeIf { it.isNotBlank() } ?: return@synchronized null
        val expiresAt = secrets.oidcExpiresAt ?: return@synchronized current
        if (nowSeconds() < expiresAt - REFRESH_SKEW_SECONDS) return@synchronized current
        val refresh = secrets.oidcRefreshToken?.takeIf { it.isNotBlank() }
            ?: return@synchronized current.takeIf { nowSeconds() < expiresAt }

        val base = profile.baseUrl.toHttpUrlOrNull() ?: return@synchronized current
        val url = base.newBuilder().addPathSegments("auth/native/refresh").build()
        val requestBody = buildJsonObject {
            put("refresh_token", refresh)
            secrets.oidcProvider?.takeIf { it.isNotBlank() }?.let { put("provider", it) }
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(EmulatorLoopbackInterceptor())
        profile.pinSha256?.takeIf { it.isNotBlank() }?.let {
            clientBuilder.certificatePinner(CertificatePinnerFactory.forPin(profile.baseUrl, it))
        }
        val request = Request.Builder().url(url).post(requestBody).build()
        runCatching {
            clientBuilder.build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val raw = response.body?.string().orEmpty()
                val json = JsonConfig.json.parseToJsonElement(raw) as? JsonObject ?: return@use null
                val access = json["access_token"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: return@use null
                store.updateOidcTokens(
                    id = profile.id,
                    accessToken = access,
                    refreshToken = json["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    expiresAt = json["expires_at"]?.jsonPrimitive?.longOrNull ?: 0,
                    provider = json["provider"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
                access
            }
        }.getOrNull() ?: current.takeIf { nowSeconds() < expiresAt }
    }

    private companion object {
        const val REFRESH_SKEW_SECONDS = 60
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
