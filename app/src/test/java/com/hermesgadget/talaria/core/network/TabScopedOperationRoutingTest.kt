/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.network

import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * B34 regression at the snapshot-routing layer: chat mutations carry an
 * explicit tab-bound snapshot, so a rename/delete/branch executed against
 * tab T's profile can never be satisfied by the ACTIVE-scope bundle when
 * profiles differ. The routing key is the snapshot's scope identity.
 */
class TabScopedOperationRoutingTest {
    private fun snapshot(
        profile: String,
        token: String = "tok-$profile",
    ): ConnectionSnapshot = ConnectionSnapshot(
        profile = ConnectionProfile(
            id = "conn-1",
            name = "Hermes",
            baseUrl = "https://hermes.local",
            authMode = AuthMode.SESSION_TOKEN,
            hasSessionToken = true,
            managementProfile = profile,
        ),
        secrets = ConnectionSecrets(sessionToken = token),
    )

    @Test
    fun `tab snapshot differs from active snapshot when profiles differ`() {
        val tab = snapshot(profile = "work")
        val active = snapshot(profile = "personal")
        assertNotEquals(tab.managementProfile, active.managementProfile)
        assertNotEquals(
            tab.scopeId,
            active.scopeId,
            "different management profiles must not share a routing scope",
        )
    }

    @Test
    fun `token rotation keeps the same routing scope`() {
        val tab = snapshot(profile = "work")
        val refreshed = snapshot(profile = "work", token = "tok-rotated")
        assertEquals(tab.managementProfile, refreshed.managementProfile)
        assertEquals(tab.scopeId, refreshed.scopeId)
    }
}
