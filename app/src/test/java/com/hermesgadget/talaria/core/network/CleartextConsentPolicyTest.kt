/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.core.network

import com.hermesgadget.talaria.domain.model.ConnectionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class CleartextConsentPolicyTest {
    @Test
    fun approvalIsBoundToSchemeHostAndEffectivePort() {
        val original = "http://192.168.1.20:9119/api".toHttpUrl()
        val approved = ConnectionProfile(
            id = "one",
            name = "LAN",
            baseUrl = original.toString(),
            allowCleartext = true,
            cleartextConsentRecorded = true,
            cleartextConsentOrigin = ConnectionOrigin.normalize(original),
        )

        assertTrue(
            CleartextConsentPolicy.resolve(original, null, null, approved).recorded,
        )
        assertFalse(
            CleartextConsentPolicy.resolve(
                "http://192.168.1.21:9119".toHttpUrl(),
                null,
                null,
                approved,
            ).recorded,
        )
        assertFalse(
            CleartextConsentPolicy.resolve(
                "http://192.168.1.20:9120".toHttpUrl(),
                null,
                null,
                approved,
            ).recorded,
        )
        assertFalse(
            CleartextConsentPolicy.resolve(
                "https://192.168.1.20:9119".toHttpUrl(),
                null,
                null,
                approved,
            ).recorded,
        )
    }

    @Test
    fun explicitApprovalStoresTheNormalizedOriginAndRejectsSpoofedKey() {
        val url = "http://EXAMPLE.test:80".toHttpUrl()
        val origin = ConnectionOrigin.normalize(url)
        val decision = CleartextConsentPolicy.resolve(url, true, origin, null)
        assertTrue(decision.recorded)
        assertEquals("http://example.test:80", decision.origin)

        val spoofed = CleartextConsentPolicy.resolve(
            url,
            true,
            "http://other.example.test:80",
            null,
        )
        assertFalse(spoofed.recorded)
        assertEquals(null, spoofed.origin)

        val malformed = CleartextConsentPolicy.resolve(url, true, "not-an-origin", null)
        assertFalse(malformed.recorded)
    }

    @Test
    fun revokeIsAnExplicitUndecidedDecision() {
        val url = "http://10.0.0.5:9119".toHttpUrl()
        val previous = ConnectionProfile(
            id = "one",
            name = "LAN",
            baseUrl = url.toString(),
            allowCleartext = true,
            cleartextConsentRecorded = true,
            cleartextConsentOrigin = ConnectionOrigin.normalize(url),
        )
        val revoked = CleartextConsentPolicy.resolve(url, false, null, previous)
        assertFalse(revoked.recorded)
        assertEquals(null, revoked.origin)
    }

    @Test
    fun normalizedApprovalSurvivesAProfileRestartFixture() {
        val url = "http://[fd00::1]:9119".toHttpUrl()
        val profile = ConnectionProfile(
            id = "restart",
            name = "Restart",
            baseUrl = url.toString(),
            cleartextConsentRecorded = true,
            cleartextConsentOrigin = ConnectionOrigin.normalize(url),
            allowCleartext = true,
        )
        val encoded = JsonConfig.json.encodeToString(profile)
        val restored = JsonConfig.json.decodeFromString<ConnectionProfile>(encoded)
        assertEquals(profile.cleartextConsentOrigin, restored.cleartextConsentOrigin)
        assertTrue(restored.cleartextConsentRecorded == true)
    }
}
