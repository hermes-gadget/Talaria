/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S02 regression: share-intake URI normalization must reject schemes that
 * grant this process local read access without a real system share grant
 * (file://, odd schemes) while keeping http(s) links and content URIs.
 */
class ShareIntakeUriPolicyTest {
    @Test
    fun `https urls are accepted`() {
        assertEquals("https://example.com/a.png", ShareIntakePolicy.normalizeUri(" https://example.com/a.png "))
    }

    @Test
    fun `content uris are accepted for grant-probe enforcement at open`() {
        val uri = "content://com.android.providers.downloads.documents/document/42"
        assertEquals(uri, ShareIntakePolicy.normalizeUri(uri))
    }

    @Test
    fun `file scheme is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShareIntakePolicy.normalizeUri("file:///data/data/com.hermesgadget.talaria/databases/ukmesh.db")
        }
    }

    @Test
    fun `odd schemes are rejected`() {
        listOf("intent://x", "javascript:alert(1)", "ws://host").forEach { raw ->
            assertThrows("scheme of $raw", IllegalArgumentException::class.java) {
                ShareIntakePolicy.normalizeUri(raw)
            }
        }
    }

    @Test
    fun `malformed and oversized uris are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            // Space in the scheme prefix is invalid per RFC 3986.
            ShareIntakePolicy.normalizeUri("ht tp://example.com")
        }
        val huge = "https://example.com/" + "a".repeat(5 * 1024)
        assertThrows(IllegalArgumentException::class.java) {
            ShareIntakePolicy.normalizeUri(huge)
        }
        assertTrue(ShareIntakePolicy.MAX_URI_CHARS == 4 * 1024)
    }

    @Test
    fun `dedupe normalizes before comparing`() {
        val list = listOf(
            "https://example.com/x",
            " https://example.com/x ",
            "https://example.com/y",
        )
        assertEquals(2, ShareIntentParser.dedupeUris(list).size)
    }
}
