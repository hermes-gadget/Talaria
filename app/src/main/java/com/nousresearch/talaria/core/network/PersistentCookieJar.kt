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
        cookies.forEach { map[it.name] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val map = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        return map.values.filter { !it.persistent || it.expiresAt >= now }
    }

    fun clear() = store.clear()
}
