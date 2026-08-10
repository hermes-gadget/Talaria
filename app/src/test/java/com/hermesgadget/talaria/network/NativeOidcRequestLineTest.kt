/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.hermesgadget.talaria.network

import com.hermesgadget.talaria.core.network.NativeOidcRequestLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InputStream

class NativeOidcRequestLineTest {
    @Test
    fun acceptsBoundedAsciiGetRequest() {
        val parsed = NativeOidcRequestLine.read(
            "GET /callback?code=one-time-code&state=expected HTTP/1.1\r\n".byteInputStream(),
        )

        assertEquals("/callback?code=one-time-code&state=expected", parsed.target)
    }

    @Test
    fun rejectsNonGetOrNonHttp11Request() {
        assertThrows(NativeOidcRequestLine.Malformed::class.java) {
            NativeOidcRequestLine.read("POST /callback HTTP/1.1\r\n".byteInputStream())
        }
        assertThrows(NativeOidcRequestLine.Malformed::class.java) {
            NativeOidcRequestLine.read("GET /callback HTTP/2\r\n".byteInputStream())
        }
    }

    @Test
    fun rejectsAnUnterminatedLineAtTheFixedByteLimit() {
        val input = RepeatingInputStream('x'.code)

        assertThrows(NativeOidcRequestLine.TooLarge::class.java) {
            NativeOidcRequestLine.read(input)
        }
        assertEquals(NativeOidcRequestLine.MAX_BYTES, input.reads)
    }

    private class RepeatingInputStream(private val value: Int) : InputStream() {
        var reads = 0
            private set

        override fun read(): Int {
            reads++
            return value
        }
    }
}
