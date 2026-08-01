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
import com.nousresearch.talaria.domain.model.effectiveManagementProfile
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Appends `?profile=` for Hermes management-profile-scoped endpoint families,
 * matching the dashboard SPA (`PROFILE_SCOPED_PREFIXES` in web/src/lib/api.ts).
 */
class ProfileQueryInterceptor(
    private val connectionStore: SecureConnectionStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val profile = connectionStore.activeProfile()?.effectiveManagementProfile().orEmpty()
        val request = chain.request()
        if (profile.isEmpty() || request.url.queryParameter("profile") != null) {
            return chain.proceed(request)
        }
        val path = "/" + request.url.pathSegments.joinToString("/")
        if (PROFILE_SCOPED.none { path.startsWith(it) }) {
            return chain.proceed(request)
        }
        val url: HttpUrl = request.url.newBuilder().addQueryParameter("profile", profile).build()
        return chain.proceed(request.newBuilder().url(url).build())
    }

    companion object {
        private val PROFILE_SCOPED = listOf(
            "/api/status", "/api/gateway", "/api/analytics", "/api/skills",
            "/api/tools/toolsets", "/api/config", "/api/env", "/api/mcp",
            "/api/messaging/platforms", "/api/model/", "/api/pairing",
            "/api/sessions", "/api/logs", "/api/memory", "/api/portal",
            "/api/cron", "/api/webhooks", "/api/ops", "/api/hermes",
            "/api/curator", "/api/system", "/api/fs", "/api/learning",
        )
    }
}
