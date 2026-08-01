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
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.data.prefs.SettingsStore
import com.hermesgadget.talaria.core.security.CertificatePinnerFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class HermesClientFactory(
    private val connectionStore: SecureConnectionStore,
    private val settingsStore: SettingsStore,
) {
    val cookieJar = PersistentCookieJar()
    private val oidcTokenRefresher = OidcTokenRefresher(connectionStore)
    private val passwordSessionManager = PasswordSessionManager(connectionStore, cookieJar)

    @Volatile
    private var cached: Pair<String, HermesApi>? = null

    fun okHttp(): OkHttpClient {
        val profile = connectionStore.activeProfile()
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(connectionStore, oidcTokenRefresher, passwordSessionManager))
            .addInterceptor(ProfileQueryInterceptor(connectionStore))
            // Application interceptor also runs for WebSocket upgrades.
            .addInterceptor(EmulatorLoopbackInterceptor())

        if (settingsStore.httpLoggingEnabled) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
            )
        }
        profile?.pinSha256?.takeIf { it.isNotBlank() }?.let { pin ->
            builder.certificatePinner(CertificatePinnerFactory.forPin(profile.baseUrl, pin))
        }
        return builder.build()
    }

    fun api(): HermesApi {
        // Prefer the active profile. Fallback matches Connect defaults (emulator host loopback).
        val base = connectionStore.activeProfile()?.baseUrl?.trimEnd('/')?.plus("/")
            ?: "http://10.0.2.2:9119/"
        cached?.let { if (it.first == base) return it.second }
        val retrofit = Retrofit.Builder()
            .baseUrl(base)
            .client(okHttp())
            .addConverterFactory(JsonConfig.json.asConverterFactory("application/json".toMediaType()))
            .build()
        val api = retrofit.create(HermesApi::class.java)
        cached = base to api
        return api
    }

    fun invalidate() {
        cached = null
        cookieJar.clear()
        passwordSessionManager.clearFailure()
    }

    fun webSocketClient(): OkHttpClient = okHttp()
}
