/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntakePolicyTest {
    @Test
    fun `image and pdf signatures are accepted only with matching claims`() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        val pdf = "%PDF-1.7".encodeToByteArray()

        assertEquals(
            ClassifiedShareFile(ShareItemKind.IMAGE, "image/png"),
            ShareIntakePolicy.classify("photo.png", "image/*", png),
        )
        assertEquals(
            ClassifiedShareFile(ShareItemKind.DOCUMENT, "application/pdf"),
            ShareIntakePolicy.classify("report.pdf", "application/pdf", pdf),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ShareIntakePolicy.classify("photo.png", "image/png", "not-png".encodeToByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShareIntakePolicy.classify("report.pdf", "application/pdf", "not-pdf".encodeToByteArray())
        }
    }

    @Test
    fun `generic binary remains an explicit managed-file alternative`() {
        val result = ShareIntakePolicy.classify(
            filename = "archive.bin",
            declaredMimeType = "application/octet-stream",
            prefix = byteArrayOf(0x50, 0x4b, 0x03, 0x04),
        )

        assertEquals(ShareItemKind.BINARY, result.kind)
        assertEquals("application/octet-stream", result.mimeType)
    }

    @Test
    fun `uri dedupe preserves first-seen order`() {
        assertEquals(
            listOf("content://one", "content://two"),
            ShareIntentParser.dedupeUris(
                listOf(" content://one ", "content://two", "content://one"),
            ),
        )
    }

    @Test
    fun `budgets reject item count, item size, and aggregate overflow`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShareIntakePolicy.checkItemBudget(ShareIntakePolicy.MAX_ITEMS, 0L, 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShareIntakePolicy.checkItemBudget(0, 0L, ShareIntakePolicy.MAX_ITEM_BYTES + 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShareIntakePolicy.checkItemBudget(0, ShareIntakePolicy.MAX_TOTAL_BYTES, 1L)
        }
    }

    @Test
    fun `only a single exact http url gets local suggestions`() {
        assertEquals(
            listOf("summarize", "compare", "extract"),
            ShareIntakePolicy.urlSuggestions("https://example.com/article"),
        )
        assertTrue(ShareIntakePolicy.urlSuggestions("https://example.com/article\n").isEmpty())
        assertTrue(ShareIntakePolicy.urlSuggestions("not a url").isEmpty())
    }

    @Test
    fun `filenames are safe and bounded`() {
        val safe = ShareIntakePolicy.safeFilename("../bad/name?with*chars.pdf")
        assertEquals("name_with_chars.pdf", safe)
        assertTrue(ShareIntakePolicy.safeFilename("x".repeat(500)).length <= ShareIntakePolicy.MAX_FILENAME_CHARS)
    }
}
