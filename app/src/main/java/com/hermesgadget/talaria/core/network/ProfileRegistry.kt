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

import com.hermesgadget.talaria.domain.model.HERMES_DEFAULT_PROFILE
import com.hermesgadget.talaria.domain.model.ProfileInfo
import com.hermesgadget.talaria.domain.model.ProfileRegistryState
import com.hermesgadget.talaria.domain.model.ProfileStreamState
import com.hermesgadget.talaria.domain.model.ProfileStreamingState
import com.hermesgadget.talaria.domain.model.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

/**
 * Process-wide registry for the Hermes management profiles served by one
 * connection. It deliberately does not own sockets: chat runtimes own their
 * channels, while this registry records their profile-scoped lifecycle.
 */
object ProfileRegistry {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ProfileRegistryState())
    val state: StateFlow<ProfileRegistryState> = _state.asStateFlow()

    fun reset() {
        _state.value = ProfileRegistryState()
    }

    /** Record a local transport transition without touching another profile. */
    fun transition(
        profileName: String,
        sessionId: String,
        next: ProfileStreamState,
    ): ProfileRegistryState {
        val profile = normalizeProfile(profileName)
        if (sessionId.isBlank()) return _state.value
        _state.update { state ->
            val current = state.streamingStates[profile]
                ?: ProfileStreamingState(profileName = profile)
            state.copy(
                streamingStates = state.streamingStates + (
                    profile to current.copy(sessions = current.sessions + (sessionId to next))
                    ),
            )
        }
        return _state.value
    }

    fun markConnecting(profileName: String, sessionId: String) =
        transition(profileName, sessionId, ProfileStreamState.CONNECTING)

    fun markActive(profileName: String, sessionId: String) =
        transition(profileName, sessionId, ProfileStreamState.ACTIVE)

    fun markStreaming(profileName: String, sessionId: String) =
        transition(profileName, sessionId, ProfileStreamState.STREAMING)

    fun markIdle(profileName: String, sessionId: String) =
        transition(profileName, sessionId, ProfileStreamState.ACTIVE)

    fun markDisconnected(profileName: String, sessionId: String) =
        transition(profileName, sessionId, ProfileStreamState.DISCONNECTED)

    /**
     * Fetch every profile and its recent sessions. Explicit profile query
     * parameters are used so the active-profile interceptor cannot collapse
     * the requests back to the foreground profile.
     */
    suspend fun refresh(
        api: HermesApi,
        limit: Int = DEFAULT_SESSION_LIMIT,
    ): Result<ProfileRegistryState> = mutex.withLock {
        _state.updateLoading()
        runCatching {
            val profiles = withContext(Dispatchers.IO) {
                api.getProfilesForMultiProfile().profiles
            }
            val names = (profiles.map(ProfileInfo::name) + HERMES_DEFAULT_PROFILE)
                .map(::normalizeProfile)
                .distinct()
            val fetched = coroutineScope {
                names.map { profile ->
                    async(Dispatchers.IO) {
                        profile to runCatching {
                            decodeSessions(
                                api.getSessionsForProfile(
                                    profile = profile,
                                    limit = limit,
                                    offset = 0,
                                    order = "recent",
                                ),
                            )
                        }
                    }
                }.awaitAll()
            }
            val sessionsByProfile = fetched.mapNotNull { (profile, result) ->
                result.getOrNull()?.let { profile to it }
            }.toMap()
            val failures = fetched.mapNotNull { (profile, result) ->
                result.exceptionOrNull()?.let { "$profile: ${it.message ?: "request failed"}" }
            }
            val streams = mergeServerActivity(
                existing = _state.value.streamingStates,
                sessionsByProfile = sessionsByProfile,
            )
            val next = ProfileRegistryState(
                profiles = profiles,
                sessionsByProfile = sessionsByProfile,
                streamingStates = streams,
                loading = false,
                error = failures.takeIf { it.isNotEmpty() }?.joinToString("; "),
            )
            _state.value = next
            next
        }.onFailure { failure ->
            _state.value = _state.value.copy(
                loading = false,
                error = failure.message ?: "Profile registry refresh failed",
            )
        }
    }

    private fun MutableStateFlow<ProfileRegistryState>.updateLoading() {
        value = value.copy(loading = true, error = null)
    }

    private fun mergeServerActivity(
        existing: Map<String, ProfileStreamingState>,
        sessionsByProfile: Map<String, List<SessionSummary>>,
    ): Map<String, ProfileStreamingState> {
        val profiles = (existing.keys + sessionsByProfile.keys).distinct()
        return profiles.associateWith { profile ->
            val previous = existing[profile]?.sessions.orEmpty()
            val serverActive = sessionsByProfile[profile].orEmpty()
                .filter { it.is_active == true || it.live == true }
                .map { it.id }
                .toSet()
            val sessions = previous.toMutableMap()
            serverActive.forEach { id ->
                if (sessions[id] == null || sessions[id] == ProfileStreamState.DISCONNECTED) {
                    sessions[id] = ProfileStreamState.ACTIVE
                }
            }
            sessions.entries
                .filter { it.key !in serverActive && it.value == ProfileStreamState.ACTIVE }
                .forEach { sessions[it.key] = ProfileStreamState.DISCONNECTED }
            ProfileStreamingState(profileName = profile, sessions = sessions)
        }
    }

    private fun decodeSessions(raw: JsonElement): List<SessionSummary> {
        val array: JsonArray? = when (raw) {
            is JsonArray -> raw
            is JsonObject -> raw["sessions"]?.jsonArray ?: raw["results"]?.jsonArray
            else -> null
        }
        return array.orEmpty().mapNotNull { element ->
            runCatching { JsonConfig.json.decodeFromJsonElement<SessionSummary>(element) }.getOrNull()
        }
    }

    private fun normalizeProfile(profileName: String): String =
        profileName.trim().ifBlank { HERMES_DEFAULT_PROFILE }

    private const val DEFAULT_SESSION_LIMIT = 100
}
