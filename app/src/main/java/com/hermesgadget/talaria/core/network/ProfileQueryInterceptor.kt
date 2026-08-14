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

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Appends `?profile=` to Hermes API requests by default. Only authentication,
 * profile-catalog, static-schema, and OAuth-flow routes are exempt. Keeping
 * the policy default-scoped means new files/media/audio endpoint variants do
 * not silently fall back to Hermes' default profile.
 */
class ProfileQueryInterceptor(
    private val snapshotProvider: () -> ConnectionSnapshot?,
) : Interceptor {
    constructor(snapshot: ConnectionSnapshot) : this({ snapshot })

    override fun intercept(chain: Interceptor.Chain): Response {
        val profile = snapshotProvider()?.managementProfile.orEmpty()
        val request = chain.request()
        if (profile.isEmpty() || request.url.queryParameter("profile") != null) {
            return chain.proceed(request)
        }
        val path = "/" + request.url.pathSegments.joinToString("/")
        if (!path.startsWith("/api/") || PROFILE_UNSCOPED.any { path.startsWith(it) }) {
            return chain.proceed(request)
        }
        val url: HttpUrl = request.url.newBuilder().addQueryParameter("profile", profile).build()
        return chain.proceed(request.newBuilder().url(url).build())
    }

    companion object {
        private val PROFILE_UNSCOPED = listOf(
            "/api/auth/",
            "/api/profiles",
            "/api/config/defaults",
            "/api/config/schema",
            "/api/mcp/oauth/flows",
        )
    }
}
