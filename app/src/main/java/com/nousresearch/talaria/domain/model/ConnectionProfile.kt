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

package com.nousresearch.talaria.domain.model

import kotlinx.serialization.Serializable

enum class AuthMode {
    NONE,
    SESSION_TOKEN,
    BASIC,
    BEARER,
    OIDC_BROWSER,
}

@Serializable
data class ConnectionProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val authMode: AuthMode = AuthMode.SESSION_TOKEN,
    val username: String? = null,
    /** Secret material is stored separately in encrypted prefs; only presence flags live here. */
    val hasPassword: Boolean = false,
    val hasSessionToken: Boolean = false,
    val hasBearerToken: Boolean = false,
    val managementProfile: String = "",
    val pinSha256: String? = null,
    val allowCleartext: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null,
)

@Serializable
data class ConnectionSecrets(
    val sessionToken: String? = null,
    val password: String? = null,
    val bearerToken: String? = null,
)
