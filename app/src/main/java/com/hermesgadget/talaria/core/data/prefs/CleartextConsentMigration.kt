/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.core.data.prefs

import com.hermesgadget.talaria.core.network.ConnectionOrigin
import com.hermesgadget.talaria.domain.model.ConnectionProfile

/** Versioned, fail-closed migration for the origin-bound cleartext decision. */
internal object CleartextConsentMigration {
    const val CURRENT_VERSION = 1

    /**
     * Pre-N0.3 profiles have no trustworthy origin binding. Even an old
     * `true` value is therefore converted to undecided instead of approved.
     */
    fun migrate(profiles: List<ConnectionProfile>, persistedVersion: Int): List<ConnectionProfile> =
        if (persistedVersion < CURRENT_VERSION) {
            profiles.map(::clearDecision)
        } else {
            profiles.map(::normalizeCurrent)
        }

    /** Keep the compatibility bit synchronized with a valid exact-origin approval. */
    fun normalizeCurrent(profile: ConnectionProfile): ConnectionProfile {
        val currentOrigin = ConnectionOrigin.normalize(profile.baseUrl)
        val validApproval = profile.cleartextConsentRecorded == true &&
            !profile.baseUrl.startsWith("https://", ignoreCase = true) &&
            currentOrigin != null &&
            profile.cleartextConsentOrigin == currentOrigin
        return if (validApproval) {
            profile.copy(allowCleartext = true, cleartextConsentRecorded = true)
        } else {
            clearDecision(profile)
        }
    }

    private fun clearDecision(profile: ConnectionProfile): ConnectionProfile = profile.copy(
        allowCleartext = false,
        cleartextConsentRecorded = false,
        cleartextConsentOrigin = null,
    )
}
