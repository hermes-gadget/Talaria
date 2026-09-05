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

package com.hermesgadget.talaria.domain.model

import java.time.Instant
import com.hermesgadget.talaria.core.util.suspendResult

const val HERMES_DEFAULT_PROFILE = "default"

/** A session plus the Hermes management profile that owns it. */
data class MultiProfileSession(
    val profileName: String,
    val session: SessionSummary,
) {
    /** Stable identity for UI lists where two profiles can have the same session id. */
    val key: String get() = "$profileName\u0000${session.id}"

    /** Numeric recency used by [MultiProfileSessionMerger]. */
    val recency: Long get() = sessionRecency(session)
}

/** Pure session-list projection shared by the ViewModel and unit tests. */
object MultiProfileSessionMerger {
    fun merge(
        sessionsByProfile: Map<String, List<SessionSummary>>,
        profileFilter: String? = null,
    ): List<MultiProfileSession> {
        val filter = profileFilter?.trim()?.takeIf { it.isNotEmpty() }
        return sessionsByProfile.asSequence()
            .filter { (profileName, _) -> filter == null || profileName == filter }
            .flatMap { (profileName, sessions) ->
                sessions.asSequence().map { MultiProfileSession(profileName, it) }
            }
            .sortedWith(
                compareByDescending<MultiProfileSession> { it.recency }
                    .thenBy { it.profileName }
                    .thenBy { it.session.id },
            )
            .toList()
    }
}

/** Local/server view of one session's transport lifecycle. */
enum class ProfileStreamState {
    DISCONNECTED,
    CONNECTING,
    ACTIVE,
    STREAMING,
}

data class ProfileStreamingState(
    val profileName: String,
    val sessions: Map<String, ProfileStreamState> = emptyMap(),
) {
    val activeSessionIds: Set<String>
        get() = sessions.filterValues { it != ProfileStreamState.DISCONNECTED }.keys

    val streamingSessionIds: Set<String>
        get() = sessions.filterValues { it == ProfileStreamState.STREAMING }.keys

    val hasActiveSessions: Boolean get() = activeSessionIds.isNotEmpty()
    val hasStreams: Boolean get() = streamingSessionIds.isNotEmpty()
}

data class ProfileRegistryState(
    val profiles: List<ProfileInfo> = emptyList(),
    val sessionsByProfile: Map<String, List<SessionSummary>> = emptyMap(),
    val streamingStates: Map<String, ProfileStreamingState> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val profileNames: List<String>
        get() = (profiles.map { it.name } + sessionsByProfile.keys + streamingStates.keys)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(compareBy<String> { it != HERMES_DEFAULT_PROFILE }.thenBy { it })

    val mergedSessions: List<MultiProfileSession>
        get() = MultiProfileSessionMerger.merge(sessionsByProfile)
}

/**
 * B26: legacy rows carry epoch SECONDS; current rows carry epoch MILLIS or
 * ISO strings. Comparing them raw misorders an old ISO session ahead of a
 * newer numeric one. Normalize everything to epoch milliseconds and reject
 * absurd/non-finite values.
 */
internal fun normalizeTimestampMillis(value: String?): Long? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val numeric = raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: run {
        // ISO path — invalid strings fall through as null.
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }
    // Heuristic: values below 1e12 cannot be epoch millis (that is
    // 335-01-24 in millis). Treat sub-second epoch values as seconds and
    // scale up. Non-finite already rejected above.
    val ms = if (numeric < 1e12) numeric * 1000.0 else numeric
    // Sanity window: timestamps after 1970 and before 2286 keep; anything
    // else (negative, or > 1e13) is garbage and treated as unknown.
    if (ms < 0.0 || ms > 1e13) return null
    return ms.toLong()
}

private fun sessionRecency(session: SessionSummary): Long {
    return normalizeTimestampMillis(session.last_active)
        ?: normalizeTimestampMillis(session.started_at)
        ?: Long.MIN_VALUE
}
