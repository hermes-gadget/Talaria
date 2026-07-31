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


package com.nousresearch.talaria.core.security

import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object CertificatePinnerFactory {
    fun forPin(baseUrl: String, sha256Pin: String): CertificatePinner {
        val host = baseUrl.toHttpUrlOrNull()?.host
            ?: baseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
        val pin = if (sha256Pin.startsWith("sha256/")) sha256Pin else "sha256/$sha256Pin"
        return CertificatePinner.Builder().add(host, pin).build()
    }
}
