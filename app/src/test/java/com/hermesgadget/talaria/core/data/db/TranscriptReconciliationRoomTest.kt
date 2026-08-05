/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptReconciliationRoomTest {
    private lateinit var database: TalariaDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TalariaDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `authoritative reconciliation removes sessions and messages in bounded batches`() =
        runTest(StandardTestDispatcher()) {
        val connectionId = "connection-a"
        val otherConnection = "connection-b"
        val deletedIds = (0 until 1_001).map { "stale-$it" }
        val keep = session("keep", connectionId)
        val sameIdOnOtherConnection = session(deletedIds.first(), otherConnection)

        database.sessions().upsertAll(
            deletedIds.map { session(it, connectionId) } + keep + sameIdOnOtherConnection,
        )
        database.messages().upsertAll(
            deletedIds.map { message(it, connectionId) } +
                message("keep", connectionId) +
                message(deletedIds.first(), otherConnection),
        )

        database.reconcileSessionCache(connectionId, deletedIds, emptyList())

        assertEquals(listOf(keep), database.sessions().getAll(connectionId))
        assertEquals(listOf(message("keep", connectionId)), database.messages().getSessionMessages(connectionId, "keep"))
        assertEquals(
            listOf(sameIdOnOtherConnection),
            database.sessions().getAll(otherConnection),
        )
        assertEquals(
            listOf(message(deletedIds.first(), otherConnection)),
            database.messages().getSessionMessages(otherConnection, deletedIds.first()),
        )
    }

    @Test
    fun `hot cache predicates have matching composite indices`() {
        val expected = setOf(
            "index_cached_sessions_connectionId_updatedAt",
            "index_cached_messages_connectionId_sessionId_ordinal",
            "index_activity_events_connectionId_createdAt_id",
        )
        val actual = buildSet {
            database.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type = 'index'")
                .use { cursor ->
                    val nameColumn = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameColumn))
                }
        }
        assertTrue(actual.containsAll(expected))
    }

    @Test
    fun `replacement failure rolls back the clear and preserves the last good transcript`() =
        runTest(StandardTestDispatcher()) {
        val connectionId = "connection-a"
        val sessionId = "session-1"
        val old = message(sessionId, connectionId, content = "last-good")
        val replacement = message(sessionId, connectionId, content = "boom")
        database.messages().upsertAll(listOf(old))
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_transcript_replacement
            BEFORE INSERT ON cached_messages
            WHEN NEW.content = 'boom'
            BEGIN
                SELECT RAISE(ABORT, 'injected replacement failure');
            END
            """.trimIndent(),
        )

        var failed = false
        try {
            database.messages().replaceSessionMessages(connectionId, sessionId, listOf(replacement))
        } catch (_: Throwable) {
            // The trigger is the deterministic failure between clear and insert.
            failed = true
        } finally {
            database.openHelper.writableDatabase.execSQL(
                "DROP TRIGGER fail_transcript_replacement",
            )
        }

        assertTrue("replacement should fail at the injected trigger", failed)
        assertEquals(
            listOf(old),
            database.messages().getSessionMessages(connectionId, sessionId),
        )

        // A resumed read can immediately retry after the failed replacement.
        database.messages().replaceSessionMessages(connectionId, sessionId, listOf(replacement.copy(content = "recovered")))
        assertEquals(
            "recovered",
            database.messages().getSessionMessages(connectionId, sessionId).single().content,
        )
    }

    private fun session(id: String, connectionId: String) = CachedSessionEntity(
        id = id,
        connectionId = connectionId,
        title = id,
        source = "cli",
        model = "model",
        preview = id,
        messageCount = 1,
        lastActive = "now",
        json = "{}",
    )

    private fun message(
        sessionId: String,
        connectionId: String,
        content: String = sessionId,
    ) = CachedMessageEntity(
        key = "$sessionId-0",
        sessionId = sessionId,
        connectionId = connectionId,
        role = "assistant",
        content = content,
        timestamp = "now",
        ordinal = 0,
    )
}
