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

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Builds encoded HTTP upgrade URLs for Hermes WebSocket endpoints. */
object HermesWebSocketUrlBuilder {
    fun build(
        baseUrl: String,
        endpoint: String,
        authQuery: String = "",
        query: List<Pair<String, String?>> = emptyList(),
    ): HttpUrl? {
        val base = baseUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
        val builder = base.newBuilder().addPathSegments(endpoint.trim('/'))

        val separator = authQuery.indexOf('=')
        if (separator > 0) {
            val name = authQuery.substring(0, separator)
            val value = authQuery.substring(separator + 1)
            if (name in setOf("ticket", "token") && value.isNotBlank()) {
                builder.addQueryParameter(name, value)
            }
        }
        query.forEach { (name, value) ->
            value?.takeIf { it.isNotBlank() }?.let { builder.addQueryParameter(name, it) }
        }
        return builder.build()
    }
}
