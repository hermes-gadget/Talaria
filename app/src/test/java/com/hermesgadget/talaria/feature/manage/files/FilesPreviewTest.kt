/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.hermesgadget.talaria.feature.manage.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FilesPreviewTest {
    @Test
    fun `parses mime and base64 bytes from a data URL`() {
        val parsed = parseDataUrl("data:image/png;base64,aGVsbG8=")

        assertEquals("image/png", parsed.mimeType)
        assertArrayEquals("hello".toByteArray(), parsed.bytes)
    }

    @Test
    fun `parses data URL parameters and ignores base64 whitespace`() {
        val parsed = parseDataUrl("data:image/jpeg;charset=utf-8;base64, aG\nVsbG8=")

        assertEquals("image/jpeg", parsed.mimeType)
        assertArrayEquals("hello".toByteArray(), parsed.bytes)
    }

    @Test
    fun `rejects non-base64 data URLs`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseDataUrl("data:text/plain,hello")
        }
    }

    @Test
    fun `maps supported image extensions case insensitively`() {
        listOf("png", "jpg", "jpeg", "gif", "webp", "bmp").forEach { extension ->
            assertEquals(
                FilePreviewType.IMAGE,
                previewTypeFor("preview.${extension.uppercase()}"),
            )
        }
    }

    @Test
    fun `mime marks an extensionless image as an image`() {
        assertEquals(FilePreviewType.IMAGE, previewTypeFor("blob", "image/webp", isBinary = true))
        assertEquals(FilePreviewType.BINARY, previewTypeFor("archive.bin", "application/zip", isBinary = true))
        assertEquals(FilePreviewType.TEXT, previewTypeFor("notes.md"))
    }
}
