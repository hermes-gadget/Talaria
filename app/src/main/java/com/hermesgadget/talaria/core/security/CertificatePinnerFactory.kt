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


package com.hermesgadget.talaria.core.security

import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

object CertificatePinnerFactory {
    fun forPin(baseUrl: String, sha256Pin: String): CertificatePinner {
        val host = baseUrl.toHttpUrlOrNull()?.host
            ?: baseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
        val pin = normalizePin(sha256Pin)
        return CertificatePinner.Builder().add(host, pin).build()
    }

    internal fun normalizePin(raw: String): String {
        val value = raw.trim()
        val payload = value.removePrefix("sha256/")
        require(payload.isNotBlank() && !value.startsWith("sha1/")) {
            "TLS pin must be a SHA-256 certificate pin"
        }
        val decoded = runCatching { Base64.getDecoder().decode(payload) }.getOrNull()
        require(decoded?.size == 32) {
            "TLS pin must contain a base64-encoded 32-byte SHA-256 digest"
        }
        return "sha256/$payload"
    }
}
