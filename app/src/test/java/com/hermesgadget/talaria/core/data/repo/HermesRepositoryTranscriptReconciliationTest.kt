/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.data.repo

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hermesgadget.talaria.core.data.db.CachedMessageEntity
import com.hermesgadget.talaria.core.data.db.CachedSessionEntity
import com.hermesgadget.talaria.core.data.db.TalariaDatabase
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.core.network.HermesClientFactory
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import com.hermesgadget.talaria.domain.model.OkResponse
import com.hermesgadget.talaria.domain.model.SessionMessage
import com.hermesgadget.talaria.domain.model.SessionMessagesResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
class HermesRepositoryTranscriptReconciliationTest {
    private lateinit var database: TalariaDatabase
    private lateinit var api: HermesApi
    private lateinit var factory: HermesClientFactory
    private lateinit var connectionStore: SecureConnectionStore
    private lateinit var repository: HermesRepository

    private val snapshot = ConnectionSnapshot(
        profile = ConnectionProfile(
            id = "connection-a",
            name = "Connection A",
            baseUrl = "https://hermes.example",
            authMode = AuthMode.NONE,
            managementProfile = "profile-a",
        ),
        secrets = ConnectionSecrets(),
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TalariaDatabase::class.java,
        ).allowMainThreadQueries().build()
        api = mockk()
        factory = mockk()
        connectionStore = mockk()
        every { factory.snapshot() } returns snapshot
        every { factory.api(snapshot) } returns api
        every { connectionStore.activeProfile() } returns snapshot.profile
        repository = HermesRepository(factory, database, connectionStore)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `explicit server delete removes the matching cached transcript atomically`() =
        runTest(StandardTestDispatcher()) {
            seed("deleted")
            coEvery {
                api.deleteSession("deleted", profile = snapshot.managementProfile)
            } returns OkResponse(ok = true)

            repository.deleteSession("deleted").getOrThrow()

            assertEquals(emptyList<CachedSessionEntity>(), database.sessions().getAll(snapshot.scopeId))
            assertEquals(
                emptyList<CachedMessageEntity>(),
                database.messages().getSessionMessages(snapshot.scopeId, "deleted"),
            )
        }

    @Test
    fun `authoritative session refresh removes a server-missing transcript`() =
        runTest(StandardTestDispatcher()) {
            seed("server-removed")
            coEvery {
                api.getSessions(
                    limit = 50,
                    offset = 0,
                    order = "recent",
                    profile = snapshot.managementProfile,
                    source = null,
                )
            } returns JsonArray(emptyList())

            repository.refreshSessions().getOrThrow()

            assertEquals(emptyList<CachedSessionEntity>(), database.sessions().getAll(snapshot.scopeId))
            assertEquals(
                emptyList<CachedMessageEntity>(),
                database.messages().getSessionMessages(snapshot.scopeId, "server-removed"),
            )
        }

    @Test
    fun `prune re-reads the authoritative session set before succeeding`() =
        runTest(StandardTestDispatcher()) {
            seed("pruned")
            coEvery {
                api.pruneSessions(any(), profile = snapshot.managementProfile)
            } returns buildJsonObject { put("deleted", 1) }
            coEvery {
                api.getSessions(
                    limit = 50,
                    offset = 0,
                    order = "recent",
                    profile = snapshot.managementProfile,
                    source = null,
                )
            } returns JsonArray(emptyList())

            repository.pruneSessions().getOrThrow()

            assertEquals(emptyList<CachedSessionEntity>(), database.sessions().getAll(snapshot.scopeId))
            assertEquals(
                emptyList<CachedMessageEntity>(),
                database.messages().getSessionMessages(snapshot.scopeId, "pruned"),
            )
        }

    @Test
    fun `failed replacement preserves last-good data and the next read retries immediately`() =
        runTest(StandardTestDispatcher()) {
            val sessionId = "recover"
            seed(sessionId)
            val response = SessionMessagesResponse(
                messages = listOf(SessionMessage(role = "assistant", content = "recovered")),
                revision = "2",
                message_count = 1,
                hash = "recovered-hash",
            )
            coEvery {
                api.getSessionMessages(sessionId, profile = snapshot.managementProfile)
            } returns response
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER fail_repository_replacement
                BEFORE INSERT ON cached_messages
                WHEN NEW.content = 'recovered'
                BEGIN
                    SELECT RAISE(ABORT, 'injected repository replacement failure');
                END
                """.trimIndent(),
            )

            assertTrue(repository.loadMessagesSnapshot(sessionId).isFailure)
            assertEquals(
                "last good",
                database.messages().getSessionMessages(snapshot.scopeId, sessionId).single().content,
            )

            database.openHelper.writableDatabase.execSQL(
                "DROP TRIGGER fail_repository_replacement",
            )
            val resumed = repository.loadMessagesSnapshot(sessionId).getOrThrow()

            assertTrue(resumed.contentChanged)
            assertEquals(
                "recovered",
                database.messages().getSessionMessages(snapshot.scopeId, sessionId).single().content,
            )
            coVerify(exactly = 2) {
                api.getSessionMessages(sessionId, profile = snapshot.managementProfile)
            }
        }

    @Test
    fun `unchanged transcript payload skips the Room replacement entirely`() =
        runTest(StandardTestDispatcher()) {
            val sessionId = "unchanged"
            seed(sessionId)
            coEvery {
                api.getSessionMessages(sessionId, profile = snapshot.managementProfile)
            } returns SessionMessagesResponse(
                messages = listOf(
                    SessionMessage(
                        role = "assistant",
                        content = "last good",
                        timestamp = "now",
                    ),
                ),
                revision = "same",
                message_count = 1,
                hash = "same-hash",
            )
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER fail_unchanged_transcript_write
                BEFORE DELETE ON cached_messages
                WHEN OLD.sessionId = 'unchanged'
                BEGIN
                    SELECT RAISE(ABORT, 'unchanged transcript was rewritten');
                END
                """.trimIndent(),
            )

            val result = repository.loadMessagesSnapshot(sessionId).getOrThrow()

            database.openHelper.writableDatabase.execSQL(
                "DROP TRIGGER fail_unchanged_transcript_write",
            )
            assertTrue(result.contentChanged)
            assertEquals(
                "last good",
                database.messages().getSessionMessages(snapshot.scopeId, sessionId).single().content,
            )
        }

    private suspend fun seed(sessionId: String, content: String = "last good") {
        database.sessions().upsertAll(
            listOf(
                CachedSessionEntity(
                    id = sessionId,
                    connectionId = snapshot.scopeId,
                    title = sessionId,
                    source = "cli",
                    model = "model",
                    preview = sessionId,
                    messageCount = 1,
                    lastActive = "now",
                    json = "{}",
                ),
            ),
        )
        database.messages().upsertAll(
            listOf(
                CachedMessageEntity(
                    key = "$sessionId-0",
                    sessionId = sessionId,
                    connectionId = snapshot.scopeId,
                    role = "assistant",
                    content = content,
                    timestamp = "now",
                    ordinal = 0,
                ),
            ),
        )
    }
}
