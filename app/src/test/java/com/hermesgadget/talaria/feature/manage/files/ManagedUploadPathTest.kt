/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.feature.manage.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * S06 regression: joinManagedPath must keep rejecting traversal/absolute
 * names — and callers must sanitize, never fall back to the raw display name.
 */
class ManagedUploadPathTest {
    @Test
    fun `absolute path display name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            joinManagedPath("/docs", "/etc/passwd")
        }
    }

    @Test
    fun `traversal display name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            joinManagedPath("/docs", "../secrets.txt")
        }
    }

    @Test
    fun `embedded slash name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            joinManagedPath("/docs", "sub/dir/file.txt")
        }
    }

    @Test
    fun `plain names still join`() {
        assertEquals("/docs/report.pdf", joinManagedPath("/docs", "report.pdf"))
        assertEquals("/report.pdf", joinManagedPath("/", "report.pdf"))
        assertEquals("/report.pdf", joinManagedPath("", "report.pdf"))
    }

    @Test
    fun `sanitizer strips separators and traversal to a leaf name`() {
        // Mirrors FilesViewModel.sanitizeManagedFileName behavior.
        fun sanitize(name: String): String = name.trim()
            .replace(Regex("[/\\\\]"), "_")
            .filterNot(Char::isISOControl)
            .takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: "upload"

        assertEquals("_etc_passwd", sanitize("/etc/passwd"))
        assertEquals(".._secrets.txt", sanitize("../secrets.txt"))
        assertEquals("sub_dir_file.txt", sanitize("sub/dir/file.txt"))
        assertEquals("upload", sanitize(".."))
        // The sanitized form must survive joinManagedPath.
        assertEquals("/docs/.._secrets.txt", joinManagedPath("/docs", sanitize("../secrets.txt")))
    }
}
