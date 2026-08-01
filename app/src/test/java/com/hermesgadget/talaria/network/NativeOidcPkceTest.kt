/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.hermesgadget.talaria.network

import com.hermesgadget.talaria.core.network.NativeOidcPkce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class NativeOidcPkceTest {
    @Test
    fun generatedPairUsesS256AndRfcLength() {
        val pair = NativeOidcPkce.generate()
        assertTrue(pair.verifier.length in 43..128)
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(pair.verifier.toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(expected, pair.challenge)
    }

    @Test
    fun stateIsHighEntropyAndChanges() {
        val first = NativeOidcPkce.state()
        val second = NativeOidcPkce.state()
        assertTrue(first.length >= 32)
        assertNotEquals(first, second)
    }

    @Test
    fun callbackRequiresMatchingState() {
        assertEquals(
            "one-time-code",
            NativeOidcPkce.parseCallback("/callback?code=one-time-code&state=expected", "expected"),
        )
        assertThrows(IllegalStateException::class.java) {
            NativeOidcPkce.parseCallback("/callback?code=attacker&state=wrong", "expected")
        }
    }

    @Test
    fun callbackSurfacesProviderError() {
        val error = assertThrows(IllegalStateException::class.java) {
            NativeOidcPkce.parseCallback(
                "/callback?error=access_denied&error_description=cancelled&state=expected",
                "expected",
            )
        }
        assertTrue(error.message.orEmpty().contains("access_denied"))
    }

    @Test
    fun callbackRejectsUnexpectedLoopbackPath() {
        assertThrows(IllegalStateException::class.java) {
            NativeOidcPkce.parseCallback("/other?code=one-time-code&state=expected", "expected")
        }
    }
}
