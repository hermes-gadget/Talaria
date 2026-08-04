/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class CleartextPolicyTest {

    @Test
    fun verifiedPrivateIpv4Destinations() {
        assertTrue(CleartextPolicy.isVerifiedDestination("10.0.0.5"))
        assertTrue(CleartextPolicy.isVerifiedDestination("172.16.0.1"))
        assertTrue(CleartextPolicy.isVerifiedDestination("172.31.255.255"))
        assertTrue(CleartextPolicy.isVerifiedDestination("192.168.1.5"))
        assertTrue(CleartextPolicy.isVerifiedDestination("169.254.1.1"))
        // CGNAT / Tailscale range 100.64.0.0/10.
        assertTrue(CleartextPolicy.isVerifiedDestination("100.64.0.1"))
        assertTrue(CleartextPolicy.isVerifiedDestination("100.127.255.254"))
    }

    @Test
    fun rejectsPublicOrMalformedIpv4() {
        assertFalse(CleartextPolicy.isVerifiedDestination("100.128.0.1")) // outside CGNAT /10
        assertFalse(CleartextPolicy.isVerifiedDestination("172.32.0.1"))
        assertFalse(CleartextPolicy.isVerifiedDestination("8.8.8.8"))
        assertFalse(CleartextPolicy.isVerifiedDestination("192.0.2.1"))
        assertFalse(CleartextPolicy.isVerifiedDestination("300.1.1.1"))
        assertFalse(CleartextPolicy.isVerifiedDestination("192.168.1")) // 3 octets
        assertFalse(CleartextPolicy.isVerifiedDestination("192.168.1.5.6"))
    }

    @Test
    fun neverTrustsDnsNamesAsPrivate() {
        assertFalse(CleartextPolicy.isVerifiedDestination("hermes.example.com"))
        assertFalse(CleartextPolicy.isVerifiedDestination("my-hermes.local"))
        assertFalse(CleartextPolicy.isVerifiedDestination("tailscale-host"))
    }

    @Test
    fun verifiedIpv6PrivateRanges() {
        // ULA fc00::/7 (Tailscale IPv6, self-hosted mesh).
        assertTrue(CleartextPolicy.isVerifiedDestination("fd00::1"))
        assertTrue(CleartextPolicy.isVerifiedDestination("fc00::abcd"))
        // Link-local fe80::/10.
        assertTrue(CleartextPolicy.isVerifiedDestination("fe80::1"))
    }

    @Test
    fun rejectsPublicOrInvalidIpv6() {
        assertFalse(CleartextPolicy.isVerifiedDestination("2001:db8::1"))
        assertFalse(CleartextPolicy.isVerifiedDestination("2606:4700:4700::1111"))
        assertFalse(CleartextPolicy.isVerifiedDestination("not-ipv6"))
    }

    @Test
    fun autoApprovedLocalHosts() {
        assertTrue(CleartextPolicy.isAutoApprovedLocalHost("localhost"))
        assertTrue(CleartextPolicy.isAutoApprovedLocalHost("ip6-localhost"))
        assertTrue(CleartextPolicy.isAutoApprovedLocalHost("::1"))
        assertTrue(CleartextPolicy.isAutoApprovedLocalHost("127.0.0.1"))
        assertTrue(CleartextPolicy.isAutoApprovedLocalHost("10.0.2.2"))
        assertFalse(CleartextPolicy.isAutoApprovedLocalHost("192.168.1.5"))
        assertFalse(CleartextPolicy.isAutoApprovedLocalHost("100.64.0.1"))
    }

    @Test
    fun checkAllowsOnlyConsentedPrivateHttp() {
        val snapshot = ConnectionSnapshot.anonymous().copy(profile = ConnectionSnapshot.anonymous().profile.copy(allowCleartext = false))
        // Without consent, private http must fail closed.
        val blocked = assertThrowsOrNull {
            CleartextPolicy.check(snapshot, "http://192.168.1.5:9119".toHttpUrl())
        }
        assertTrue(blocked)

        val consented = snapshot.copy(profile = snapshot.profile.copy(allowCleartext = true))
        CleartextPolicy.check(consented, "http://192.168.1.5:9119".toHttpUrl())
        CleartextPolicy.check(consented, "http://100.64.0.1:9119".toHttpUrl())
        CleartextPolicy.check(consented, "http://[fd00::1]:9119".toHttpUrl())
    }

    private fun assertThrowsOrNull(block: () -> Unit): Boolean =
        try {
            block()
            false
        } catch (expected: java.io.IOException) {
            true
        }
}
