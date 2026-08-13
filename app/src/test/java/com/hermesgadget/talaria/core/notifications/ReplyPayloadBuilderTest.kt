/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReplyPayloadBuilderTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `short replies stay inline`() {
        val builder = ReplyPayloadBuilder(temp.newFolder("replies"))
        val payload = builder.build("short reply")
        assertTrue(payload is ReplyPayload.Inline)
        assertEquals("short reply", payload.text)
        assertNull(payload.filePath)
    }

    @Test
    fun `replies at the inline byte budget stay inline`() {
        val builder = ReplyPayloadBuilder(temp.newFolder("replies"))
        val atBudget = "a".repeat(MAX_INLINE_REPLY_BYTES)
        assertTrue(builder.build(atBudget) is ReplyPayload.Inline)
    }

    @Test
    fun `oversized replies spill to a cache file`() {
        val dir = temp.newFolder("replies")
        val builder = ReplyPayloadBuilder(dir)
        val longReply = "a".repeat(MAX_INLINE_REPLY_BYTES + 1)
        val payload = builder.build(longReply, nowMillis = 1234L)

        assertTrue(payload is ReplyPayload.File)
        assertNull(payload.text)
        val file = File(payload.filePath!!)
        assertTrue(file.isFile)
        assertEquals(longReply, file.readText())
        assertEquals(1, dir.listFiles()?.size ?: 0)
    }
}
