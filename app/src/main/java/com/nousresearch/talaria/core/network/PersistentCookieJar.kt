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

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/** In-memory cookie jar for gated-mode `hermes_session_at` cookies. */
class PersistentCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val map = store.getOrPut(host) { ConcurrentHashMap() }
        // RFC 6265 cookie identity is name + domain + path. Gated deployments
        // behind two path-prefixed dashboards on the same host must not replace
        // one another's session cookies merely because the names match.
        cookies.forEach { map[cookieKey(it)] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val map = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        map.entries.removeIf { (_, cookie) -> cookie.expiresAt < now }
        // CookieJar implementations must return only cookies that match the
        // request URL. In particular, never leak Secure or path-scoped auth
        // cookies to cleartext/unrelated requests on the same host.
        return map.values.filter { it.matches(url) }
    }

    fun hasCookiesFor(url: HttpUrl): Boolean = loadForRequest(url).isNotEmpty()

    fun clear() = store.clear()

    private fun cookieKey(cookie: Cookie): String =
        "${cookie.name}\u0000${cookie.domain}\u0000${cookie.path}"
}
