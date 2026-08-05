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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pasted session token may carry control characters (trailing newline from
 * terminal copy, CRLF from Windows, etc.). OkHttp throws IllegalArgumentException
 * on control characters in header values, and that exception surfaces on the
 * OkHttp dispatcher thread — outside the app's coroutine try/catch — so an
 * unsanitized token used to crash the process at the first authenticated call
 * and again on every relaunch (a launch crash loop until app data was cleared).
 */
class AuthInterceptorSanitizeTokenTest {

    @Test
    fun `plain token is unchanged`() {
        val token = "abcDEF123+xyz/ghi=jkl"
        assertEquals(token, AuthInterceptor.sanitizeToken(token))
    }

    @Test
    fun `trailing newline is stripped`() {
        assertEquals("abc123", AuthInterceptor.sanitizeToken("abc123\n"))
        assertEquals("abc123", AuthInterceptor.sanitizeToken("abc123\r\n"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("abc123", AuthInterceptor.sanitizeToken("  abc123  "))
        assertEquals("abc123", AuthInterceptor.sanitizeToken("\tabc123\t"))
    }

    @Test
    fun `embedded control characters are removed`() {
        assertEquals("abc123", AuthInterceptor.sanitizeToken("abc\u0000\n123"))
        assertEquals("abc123", AuthInterceptor.sanitizeToken("abc\u0001123"))
        assertEquals("abc123", AuthInterceptor.sanitizeToken("abc\u007f123"))
    }

    @Test
    fun `surrounding control chars with content survive`() {
        assertEquals("abc123", AuthInterceptor.sanitizeToken("\n\r abc123 \r\n"))
    }

    @Test
    fun `control-only value collapses to empty`() {
        assertTrue(AuthInterceptor.sanitizeToken("\n\r\n").isEmpty())
        assertTrue(AuthInterceptor.sanitizeToken("\u0000").isEmpty())
    }

    @Test
    fun `sanitized value is safe for okhttp headers`() {
        // Regression guard: a header built from the sanitized value must not
        // throw, while the raw value must (this is the crash mechanism).
        val raw = "token\n"
        val sanitized = AuthInterceptor.sanitizeToken(raw)
        assertFalse(sanitized.contains('\n'))
        assertTrue(raw.contains('\n'))
        // Building a request with the sanitized value succeeds.
        okhttp3.Request.Builder()
            .url("https://example.invalid/")
            .header(AuthInterceptor.SESSION_HEADER, sanitized)
            .build()
    }
}
