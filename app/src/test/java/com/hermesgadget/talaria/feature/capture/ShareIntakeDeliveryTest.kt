/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.feature.capture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntakeDeliveryTest {
    @Test
    fun `delivery plan contains one managed upload per non-inline item`() {
        val image = ShareIntakeItem(
            id = "image",
            sourceUri = "content://image",
            localPath = "/cache/image.png",
            displayName = "image.png",
            mimeType = "image/png",
            sizeBytes = 10L,
            kind = ShareItemKind.IMAGE,
        )
        val pdf = ShareIntakeItem(
            id = "pdf",
            sourceUri = "content://pdf",
            localPath = "/cache/report.pdf",
            displayName = "report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 20L,
            kind = ShareItemKind.DOCUMENT,
        )
        val binary = ShareIntakeItem(
            id = "binary",
            sourceUri = "content://binary",
            localPath = "/cache/archive.bin",
            displayName = "archive.bin",
            mimeType = "application/octet-stream",
            sizeBytes = 30L,
            kind = ShareItemKind.BINARY,
        )
        val draft = ShareIntakeDraft(
            scopeId = "connection|profile|default",
            connectionId = "connection",
            managementProfile = "default",
            items = listOf(image, pdf, binary),
            createdAt = 1L,
            updatedAt = 1L,
        )

        val plan = buildShareDeliveryPlan(
            draft = draft,
            files = listOf(File(image.localPath), File(pdf.localPath), File(binary.localPath)),
        )

        assertEquals(listOf("image"), plan.inlineImages.map { it.first.id })
        assertEquals(listOf("pdf", "binary"), plan.managedUploads.map { it.item.id })
        assertEquals(
            plan.managedUploads.map { it.item.id }.distinct().size,
            plan.managedUploads.size,
        )
    }

    @Test
    fun `prompt keeps mixed item order and labels managed alternatives`() {
        val image = ShareIntakeItem(
            id = "image",
            sourceUri = "content://image",
            localPath = "/cache/image.png",
            displayName = "image.png",
            mimeType = "image/png",
            sizeBytes = 10L,
            kind = ShareItemKind.IMAGE,
        )
        val pdf = ShareIntakeItem(
            id = "pdf",
            sourceUri = "content://pdf",
            localPath = "/cache/report.pdf",
            displayName = "report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 20L,
            kind = ShareItemKind.DOCUMENT,
        )
        val draft = ShareIntakeDraft(
            scopeId = "connection|profile|default",
            connectionId = "connection",
            managementProfile = "default",
            items = listOf(image, pdf),
            createdAt = 1L,
            updatedAt = 1L,
        )

        val prompt = SharePromptBuilder.build(
            draft = draft,
            inlineImages = listOf(image),
            managedReferences = listOf(ManagedReference(pdf, "/talaria-share-intake-task-report.pdf")),
        )

        assertTrue(prompt.indexOf("Inline image: image.png") < prompt.indexOf("Managed file reference:"))
        assertTrue(prompt.contains("uploaded alternative; this is not an inline attachment"))
    }
}
