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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val _refreshErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Per-profile failures do not masquerade as an empty session list. */
    val refreshErrors: StateFlow<Map<String, String>> = _refreshErrors.asStateFlow()
    private val _lastSuccessfulAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastSuccessfulAt: StateFlow<Map<String, Long>> = _lastSuccessfulAt.asStateFlow()
    private val profileFetchSemaphore = Semaphore(MAX_PROFILE_FETCH_CONCURRENCY)

    fun reset() {
        _state.value = ProfileRegistryState()
        _refreshErrors.value = emptyMap()
        _lastSuccessfulAt.value = emptyMap()
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
        preferredProfiles: Collection<String> = emptyList(),
    ): Result<ProfileRegistryState> = mutex.withLock {
        _state.updateLoading()
        try {
            val profiles = withContext(Dispatchers.IO) {
                api.getProfilesForMultiProfile().profiles
            }
            val names = (profiles.map(ProfileInfo::name) + HERMES_DEFAULT_PROFILE)
                .map(::normalizeProfile)
                .distinct()
            val orderedNames = orderProfiles(names, preferredProfiles)
            val fetched = coroutineScope {
                orderedNames.map { profile ->
                    async(Dispatchers.IO) {
                        val result = profileFetchSemaphore.withPermit {
                            try {
                                Result.success(
                                    decodeSessions(
                                        api.getSessionsForProfile(
                                            profile = profile,
                                            limit = limit,
                                            offset = 0,
                                            order = "recent",
                                        ),
                                    ),
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                Result.failure(failure)
                            }
                        }
                        profile to result
                    }
                }.awaitAll()
            }
            val previousSessions = _state.value.sessionsByProfile
            val successful = fetched.mapNotNull { (profile, result) ->
                result.getOrNull()?.let { profile to it }
            }.toMap()
            // A failed request keeps the last-good list for that profile. Only
            // profiles returned by the current profile catalog are reconciled.
            val sessionsByProfile = names.associateWith { profile ->
                successful[profile] ?: previousSessions[profile].orEmpty()
            }
            val failures = fetched.mapNotNull { (profile, result) ->
                result.exceptionOrNull()?.let { "$profile: ${it.message ?: "request failed"}" }
            }
            val failureByProfile = fetched.mapNotNull { (profile, result) ->
                result.exceptionOrNull()?.let { profile to (it.message ?: "request failed") }
            }.toMap()
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
            if (_refreshErrors.value != failureByProfile) _refreshErrors.value = failureByProfile
            val successfulAt = _lastSuccessfulAt.value + successful.keys.associateWith {
                System.currentTimeMillis()
            }
            if (_lastSuccessfulAt.value != successfulAt) _lastSuccessfulAt.value = successfulAt
            _state.publishIfChanged(next)
            Result.success(_state.value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            _state.publishIfChanged(_state.value.copy(
                loading = false,
                error = failure.message ?: "Profile registry refresh failed",
            ))
            Result.failure(failure)
        }
    }

    private fun MutableStateFlow<ProfileRegistryState>.updateLoading() {
        publishIfChanged(value.copy(loading = true, error = null))
    }

    private fun MutableStateFlow<ProfileRegistryState>.publishIfChanged(next: ProfileRegistryState) {
        if (value != next) value = next
    }

    /** Preferred/visible profiles are submitted first; the semaphore caps the rest. */
    internal fun orderProfiles(
        names: List<String>,
        preferredProfiles: Collection<String>,
    ): List<String> {
        val preferred = preferredProfiles.map(::normalizeProfile).distinct()
            .withIndex()
            .associate { it.value to it.index }
        return names.sortedWith(
            compareBy<String> { preferred[it] ?: Int.MAX_VALUE }
                .thenBy { it },
        )
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
    private const val MAX_PROFILE_FETCH_CONCURRENCY = 3
}
