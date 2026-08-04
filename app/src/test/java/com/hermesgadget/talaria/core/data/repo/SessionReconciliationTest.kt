/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.data.repo

import com.hermesgadget.talaria.core.data.db.CachedSessionEntity
import com.hermesgadget.talaria.domain.model.SessionMessage
import com.hermesgadget.talaria.domain.model.SessionMessagesResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReconciliationTest {
    @Test
    fun `stale cache ids are exactly the rows absent from the server`() {
        assertEquals(
            setOf("ghost", "old"),
            SessionReconciliation.staleSessionIds(
                cachedIds = setOf("keep", "ghost", "old"),
                serverIds = setOf("keep", "new"),
            ),
        )
    }

    @Test
    fun `unchanged session rows are not scheduled for Room writes`() {
        val old = CachedSessionEntity(
            id = "s1",
            connectionId = "c1",
            title = "Title",
            source = "cli",
            model = "model",
            preview = "hello",
            messageCount = 2,
            lastActive = "10",
            json = "{}",
        )
        val same = old.copy(updatedAt = old.updatedAt + 1000)
        val changed = same.copy(preview = "changed")
        assertTrue(SessionReconciliation.changedRows(mapOf("s1" to old), listOf(same)).isEmpty())
        assertEquals(listOf(changed), SessionReconciliation.changedRows(mapOf("s1" to old), listOf(changed)))
    }

    @Test
    fun `equal count and content hash suppress a revision-only transcript change`() {
        val messages = listOf(SessionMessage(role = "assistant", content = "same"))
        val first = TranscriptFingerprintFactory.from(
            SessionMessagesResponse(messages = messages, revision = "1", message_count = 1, hash = "h"),
        )
        val second = TranscriptFingerprintFactory.from(
            SessionMessagesResponse(messages = messages, revision = "2", message_count = 1, hash = "h"),
        )
        assertTrue(!TranscriptFingerprintFactory.contentChanged(first, second))
    }
}
