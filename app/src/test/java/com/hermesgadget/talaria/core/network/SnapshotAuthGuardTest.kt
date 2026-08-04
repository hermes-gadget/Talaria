/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */

package com.hermesgadget.talaria.core.network

import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SnapshotAuthGuardTest {
    @Test
    fun `origin guard canonicalizes host and effective port but rejects every origin change`() {
        val snapshot = snapshot(baseUrl = "https://EXAMPLE.test")

        assertTrue(SnapshotAuthGuard.hasSameOrigin(snapshot, "https://example.test/api/status".toHttpUrl()))
        assertTrue(SnapshotAuthGuard.hasSameOrigin(snapshot, "https://example.test:443/api/status".toHttpUrl()))
        assertFalse(SnapshotAuthGuard.hasSameOrigin(snapshot, "http://example.test/api/status".toHttpUrl()))
        assertFalse(SnapshotAuthGuard.hasSameOrigin(snapshot, "https://other.test/api/status".toHttpUrl()))
        assertFalse(SnapshotAuthGuard.hasSameOrigin(snapshot, "https://example.test:444/api/status".toHttpUrl()))

        try {
            SnapshotAuthGuard.requireSameOrigin(snapshot, "https://other.test/api/status".toHttpUrl())
            fail("cross-origin request should have been canceled")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("operation was safely canceled"))
        }
    }

    @Test
    fun `snapshot mismatch fails closed with the safe cancellation error`() {
        val saved = snapshot()
        val renamed = saved.copy(profile = saved.profile.copy(name = "Renamed"))
        val edited = saved.copy(profile = saved.profile.copy(baseUrl = "https://other.test"))
        val rotated = saved.copy(secrets = saved.secrets.copy(bearerToken = "token-b"))

        assertTrue(SnapshotAuthGuard.isCurrent(saved, renamed))
        assertFalse(SnapshotAuthGuard.isCurrent(saved, edited))
        assertFalse(SnapshotAuthGuard.isCurrent(saved, rotated))
        assertFalse(SnapshotAuthGuard.isCurrent(saved, null))

        try {
            SnapshotAuthGuard.requireCurrent(saved, rotated)
            fail("credential rotation should have canceled the operation")
        } catch (expected: IOException) {
            assertEquals(SnapshotAuthGuard.CHANGED_MESSAGE, expected.message)
        }

        try {
            SnapshotAuthGuard.requireExactCurrent(
                saved,
                renamed,
                SnapshotAuthGuard.OIDC_CHANGED_MESSAGE,
            )
            fail("OIDC completion should reject an edited saved record")
        } catch (expected: IOException) {
            assertEquals(SnapshotAuthGuard.OIDC_CHANGED_MESSAGE, expected.message)
        }
    }

    @Test
    fun `native OIDC routes suppress stored credentials for every auth mode`() {
        AuthMode.entries.forEach { mode ->
            val snapshot = snapshot().copy(profile = snapshot().profile.copy(authMode = mode))
            assertTrue(mode.name, SnapshotAuthGuard.suppressCredentials("/auth/native/token"))
            assertTrue(mode.name, SnapshotAuthGuard.suppressCredentials("/auth/native/refresh"))
            assertFalse(mode.name, SnapshotAuthGuard.suppressCredentials("/api/auth/ws-ticket"))
            assertEquals(mode, snapshot.authMode)
        }
    }

    private fun snapshot(baseUrl: String = "https://example.test"): ConnectionSnapshot =
        ConnectionSnapshot(
            profile = ConnectionProfile(
                id = "connection-a",
                name = "A",
                baseUrl = baseUrl,
                authMode = AuthMode.BEARER,
                managementProfile = "default",
                allowCleartext = false,
            ),
            secrets = ConnectionSecrets(bearerToken = "token-a"),
        )
}
