package com.hermesgadget.talaria.feature.manage.system

import com.hermesgadget.talaria.domain.model.OpsDebugShareResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S08: the debug-share bundle is sanitized server-side and the receipt must be
 * surfaced verbatim. The UI treats a missing/`false` sanitization receipt as
 * requiring an explicit, deliberate user decision before any link is acted on —
 * fail-closed against accidentally leaking unredacted debug payloads.
 */
class DebugShareConsentTest {

    @Test
    fun `default receipt is fail-closed (unredacted until server confirms otherwise)`() {
        val response = OpsDebugShareResponse()
        assertFalse(response.ok)
        assertFalse(response.redacted.not() && response.ok)
        // Default construction must not look like a successful, sanitized share.
        assertTrue(response.urls.isEmpty())
    }

    @Test
    fun `redacted successful share carries links without consent gate`() {
        val response = OpsDebugShareResponse(
            ok = true,
            redacted = true,
            urls = mapOf("trace" to "https://example.test/t"),
        )
        assertTrue(response.ok)
        assertTrue(response.redacted)
        assertEquals("https://example.test/t", response.urls["trace"])
        // Consent gate condition (used by the screen): NOT(ok && redacted).
        assertFalse(!(response.ok && response.redacted))
    }

    @Test
    fun `unredacted bundle requires deliberate confirmation`() {
        val response = OpsDebugShareResponse(
            ok = true,
            redacted = false,
            urls = mapOf("trace" to "https://example.test/raw"),
        )
        // Consent gate condition (used by the screen): NOT(ok && redacted) → gate on.
        assertTrue(!(response.ok && response.redacted))
    }

    @Test
    fun `server-side refusal keeps zero urls`() {
        val response = OpsDebugShareResponse(
            ok = false,
            failures = listOf("redaction failed"),
        )
        assertFalse(response.ok)
        assertTrue(response.urls.isEmpty())
        assertEquals(listOf("redaction failed"), response.failures)
    }
}
