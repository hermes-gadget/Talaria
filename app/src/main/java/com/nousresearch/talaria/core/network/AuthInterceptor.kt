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
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches Hermes dashboard auth for the active connection profile.
 *
 * Loopback / token mode: `X-Hermes-Session-Token`
 * Gated basic/bearer: Authorization header (cookies also flow via CookieJar)
 */
class AuthInterceptor(
    private val connectionStore: SecureConnectionStore,
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
                val user = active.username.orEmpty()
                val pass = secrets?.password.orEmpty()
                if (user.isNotBlank()) {
                    req.header("Authorization", Credentials.basic(user, pass))
                }
                secrets?.sessionToken?.takeIf { it.isNotBlank() }?.let {
                    req.header(SESSION_HEADER, it)
                }
            }
            AuthMode.BEARER -> secrets?.bearerToken?.takeIf { it.isNotBlank() }?.let {
                req.header("Authorization", "Bearer $it")
            }
            AuthMode.OIDC_BROWSER, AuthMode.NONE, null -> {
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
    }
}
