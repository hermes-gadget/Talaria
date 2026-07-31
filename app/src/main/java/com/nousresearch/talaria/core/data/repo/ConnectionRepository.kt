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


package com.nousresearch.talaria.core.data.repo

import com.nousresearch.talaria.core.data.prefs.SecureConnectionStore
import com.nousresearch.talaria.core.data.prefs.SettingsStore
import com.nousresearch.talaria.core.network.HermesClientFactory
import com.nousresearch.talaria.domain.model.AuthMode
import com.nousresearch.talaria.domain.model.ConnectionProfile
import com.nousresearch.talaria.domain.model.ConnectionSecrets
import com.nousresearch.talaria.domain.model.StatusResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import java.util.UUID

class ConnectionRepository(
    private val store: SecureConnectionStore,
    private val clientFactory: HermesClientFactory,
    private val settingsStore: SettingsStore,
) {
    val profiles: StateFlow<List<ConnectionProfile>> = store.profiles
    val activeId: StateFlow<String?> = store.activeId

    fun active(): ConnectionProfile? = store.activeProfile()

    suspend fun save(
        name: String,
        baseUrl: String,
        authMode: AuthMode,
        username: String?,
        sessionToken: String?,
        password: String?,
        bearerToken: String?,
        managementProfile: String,
        pinSha256: String?,
        existingId: String? = null,
    ): ConnectionProfile = withContext(Dispatchers.IO) {
        val id = existingId ?: UUID.randomUUID().toString()
        val profile = ConnectionProfile(
            id = id,
            name = name.ifBlank { baseUrl },
            baseUrl = baseUrl.trim().trimEnd('/'),
            authMode = authMode,
            username = username,
            hasPassword = !password.isNullOrBlank(),
            hasSessionToken = !sessionToken.isNullOrBlank(),
            hasBearerToken = !bearerToken.isNullOrBlank(),
            managementProfile = managementProfile,
            pinSha256 = pinSha256,
        )
        val prev = if (existingId != null) store.secretsFor(id) else ConnectionSecrets()
        val secrets = ConnectionSecrets(
            sessionToken = sessionToken?.takeIf { it.isNotBlank() } ?: prev.sessionToken,
            password = password?.takeIf { it.isNotBlank() } ?: prev.password,
            bearerToken = bearerToken?.takeIf { it.isNotBlank() } ?: prev.bearerToken,
        )
        store.upsert(profile, secrets)
        clientFactory.invalidate()
        profile
    }

    fun setActive(id: String) {
        store.setActive(id)
        clientFactory.invalidate()
    }

    fun delete(id: String) {
        store.delete(id)
        clientFactory.invalidate()
    }

    suspend fun testConnection(): Result<StatusResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val api = clientFactory.api()
            val profile = store.activeProfile()
            if (profile?.authMode == AuthMode.BASIC && !profile.username.isNullOrBlank()) {
                // Attempt password-login to establish cookie session when gated.
                val secrets = store.secretsFor(profile.id)
                val form = FormBody.Builder()
                    .add("username", profile.username.orEmpty())
                    .add("password", secrets.password.orEmpty())
                    .build()
                runCatching { api.passwordLogin(form) }
            }
            api.getStatus()
        }
    }
}
