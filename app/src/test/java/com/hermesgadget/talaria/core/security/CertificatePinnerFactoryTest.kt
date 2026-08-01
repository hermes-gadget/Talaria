/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.core.security

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CertificatePinnerFactoryTest {
    private val digest = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun normalizesValidSha256Digest() {
        assertEquals("sha256/$digest", CertificatePinnerFactory.normalizePin(digest))
        assertEquals("sha256/$digest", CertificatePinnerFactory.normalizePin("sha256/$digest"))
    }

    @Test
    fun rejectsWrongAlgorithmMalformedAndWrongLengthPins() {
        assertThrows(IllegalArgumentException::class.java) {
            CertificatePinnerFactory.normalizePin("sha1/$digest")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CertificatePinnerFactory.normalizePin("not-base64")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CertificatePinnerFactory.normalizePin(Base64.getEncoder().encodeToString(ByteArray(31)))
        }
    }
}
