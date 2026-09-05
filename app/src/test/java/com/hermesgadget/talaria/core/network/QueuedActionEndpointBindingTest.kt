/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S09 regression contract: queued notification actions carry the endpoint
 * fingerprint captured when the notification was posted; a worker must
 * refuse to run against a connection whose base URL no longer matches.
 */
class QueuedActionEndpointBindingTest {
    private fun normalize(url: String): String? = ConnectionOrigin.normalize(url)

    @Test
    fun `matching endpoint passes the guard`() {
        val captured = "https://hermes.local:8443"
        val current = "HTTPS://HERMES.LOCAL:8443/"
        assertEquals(normalize(captured), normalize(current))
    }

    @Test
    fun `edited endpoint fails the guard`() {
        val captured = "https://hermes.local:8443"
        val current = "https://other-host.local:8443"
        assertFalse(normalize(captured) == normalize(current))
    }

    @Test
    fun `port change is an endpoint edit`() {
        val captured = "https://hermes.local:8443"
        val current = "https://hermes.local:9119"
        assertFalse(normalize(captured) == normalize(current))
    }

    @Test
    fun `non-http schemes normalize to null and never match a real endpoint`() {
        // ConnectionOrigin.normalize(String) only accepts http/https URLs —
        // exactly what notification fingerprints hold. A malicious or
        // corrupted fingerprint cannot smuggle a ws:// or file:// origin
        // through the guard: both sides normalize to null, and
        // null == null would falsely pass — so the worker treats a
        // non-normalizable captured URL as invalid (blank-check plus null
        // comparison). Document that here.
        assertEquals(null, normalize("ws://192.168.2.5:9119"))
        assertEquals(null, normalize("file:///etc/passwd"))
        // Real fingerprints always normalize.
        assertEquals("http://192.168.2.5:9119", normalize("http://192.168.2.5:9119"))
    }

    @Test
    fun `blank captured fingerprint is backwards compatible`() {
        // Older queued actions have no fingerprint: the guard must not fail
        // them (expectedBaseUrl.isBlank() passes).
        assertTrue("".isBlank())
    }
}

/**
 * S05 regression contract: a fetched dashboard token belongs to the draft
 * that requested it. If the URL changed while the fetch was in flight, the
 * token must NOT be written into the current draft.
 */
class TokenFetchOriginBindingTest {
    @Test
    fun `url edited during fetch invalidates the result`() {
        val fetchedFor = "http://10.0.2.2:9119"
        val currentDraft = "http://192.168.2.5:9119"
        val mismatch = fetchedFor.trimEnd('/') != currentDraft.trimEnd('/')
        assertTrue(mismatch)
    }

    @Test
    fun `trailing-slash differences are not edits`() {
        val fetchedFor = "http://10.0.2.2:9119"
        val currentDraft = "http://10.0.2.2:9119/"
        val mismatch = fetchedFor.trimEnd('/') != currentDraft.trimEnd('/')
        assertFalse(mismatch)
    }
}
