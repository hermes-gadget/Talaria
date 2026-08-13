/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.data.repo

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hermesgadget.talaria.core.data.db.ActivityEventEntity
import com.hermesgadget.talaria.core.data.db.CachedMessageEntity
import com.hermesgadget.talaria.core.data.db.CachedSessionEntity
import com.hermesgadget.talaria.core.data.db.ChatDraftEntity
import com.hermesgadget.talaria.core.data.db.LocalSessionCollectionEntity
import com.hermesgadget.talaria.core.data.db.LocalSessionCollectionLinkEntity
import com.hermesgadget.talaria.core.data.db.LocalSessionFavoriteEntity
import com.hermesgadget.talaria.core.data.db.SavedSessionFilterEntity
import com.hermesgadget.talaria.core.data.db.TalariaDatabase
import com.hermesgadget.talaria.core.data.prefs.PersistedAgentWatch
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.data.prefs.SettingsStore
import com.hermesgadget.talaria.core.data.prefs.PersistedChatState
import com.hermesgadget.talaria.core.network.HermesClientFactory
import com.hermesgadget.talaria.core.network.WsAuthHelper
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionPurgeTest {
    private lateinit var database: TalariaDatabase
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TalariaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsStore(context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seed(connectionId: String, scopeSuffix: String = "") {
        val scope = connectionId + scopeSuffix
        database.sessions().upsertAll(listOf(session(scope)))
        database.messages().upsertAll(listOf(message(scope)))
        database.activity().insert(activity(scope))
        database.drafts().upsert(draft(scope))
        database.sessionOrganization().insertCollection(collection(scope))
        database.sessionOrganization().addCollectionLink(collectionLink(scope))
        database.sessionOrganization().addFavorite(favorite(scope))
        database.sessionOrganization().upsertSavedFilter(filter(scope))
        settings.saveChatState(scope, PersistedChatState())
        settings.setCachedStatusLine(scope, "cached")
    }

    @Test
    fun `purgeConnection removes every table for the connection and its profile scopes`() =
        runTest(StandardTestDispatcher()) {
            seed("connection-a")
            seed("connection-a", "|profile|work")
            seed("connection-b")

            database.purgeConnection("connection-a")

            assertEquals(0, database.sessions().getAll("connection-a").size)
            assertEquals(0, database.sessions().getAll("connection-a|profile|work").size)
            // Connection-b is untouched.
            assertEquals(1, database.sessions().getAll("connection-b").size)
            assertEquals(1, database.messages().getSessionMessages("connection-b", "s").size)
            assertEquals("draft", database.drafts().get("connection-b")?.text)
        }

    @Test
    fun `settings purge removes scoped keys and watches but keeps other connections`() =
        runTest(StandardTestDispatcher()) {
            seed("connection-a")
            seed("connection-a", "|profile|work")
            seed("connection-b")
        settings.saveAgentWatches(
            listOf(
                PersistedAgentWatch("w1", "alice", "lane", connectionId = "connection-a"),
                PersistedAgentWatch("w2", "bob", "lane", connectionId = "connection-a", managementProfile = "work"),
                PersistedAgentWatch("w3", "carol", "lane", connectionId = "connection-b"),
            ),
        )

        settings.purgeScopedKeys("connection-a")

        // A and its management-profile variant are gone; B survives.
        assertTrue(settings.loadChatState("connection-a").tabs.isEmpty())
        assertTrue(settings.loadChatState("connection-a|profile|work").tabs.isEmpty())
        assertEquals("cached", settings.cachedStatusLine("connection-b"))
        assertNull(settings.cachedStatusLine("connection-a"))
        assertNull(settings.cachedStatusLine("connection-a|profile|work"))
        // Watches for connection-a (all profiles) dropped; connection-b kept.
        val remaining = settings.loadAgentWatches()
        assertEquals(listOf("w3"), remaining.map { it.watcherId })
        }

    @Test
    fun `repository delete purges room and settings through the full chain`() =
        runTest(StandardTestDispatcher()) {
            seed("connection-a")
            seed("connection-b")
            val store = mockk<SecureConnectionStore>(relaxed = true)
            val clientFactory = mockk<HermesClientFactory>(relaxed = true)
            val wsAuthHelper = mockk<WsAuthHelper>(relaxed = true)
            val hermesRepository = mockk<HermesRepository>(relaxed = true)
            val repo = ConnectionRepository(store, clientFactory, wsAuthHelper, database, settings, hermesRepository)

            repo.delete("connection-a")

            assertEquals(0, database.sessions().getAll("connection-a").size)
            assertEquals(1, database.sessions().getAll("connection-b").size)
            assertNull(settings.cachedStatusLine("connection-a"))
            assertEquals("cached", settings.cachedStatusLine("connection-b"))
        }

    private fun session(connectionId: String) = CachedSessionEntity(
        connectionId = connectionId,
        id = "s",
        title = "t",
        source = "x",
        model = null,
        preview = null,
        messageCount = 1,
        lastActive = "now",
        json = "{}",
        updatedAt = 1L,
    )

    private fun message(connectionId: String) = CachedMessageEntity(
        key = "k",
        sessionId = "s",
        connectionId = connectionId,
        role = "user",
        content = "hi",
        timestamp = "1",
        ordinal = 0,
    )

    private fun activity(connectionId: String) = ActivityEventEntity(
        connectionId = connectionId,
        type = "approval",
        title = "t",
        body = "b",
        createdAt = 1L,
        read = false,
    )

    private fun draft(connectionId: String) = ChatDraftEntity(connectionId = connectionId, text = "draft")

    private fun collection(connectionId: String) = LocalSessionCollectionEntity(
        connectionId = connectionId,
        kind = "LABEL",
        name = "n",
        createdAt = 1L,
    )

    private fun collectionLink(connectionId: String) = LocalSessionCollectionLinkEntity(
        connectionId = connectionId,
        sessionId = "s",
        collectionId = 1L,
    )

    private fun favorite(connectionId: String) = LocalSessionFavoriteEntity(
        connectionId = connectionId,
        sessionId = "s",
    )

    private fun filter(connectionId: String) = SavedSessionFilterEntity(
        connectionId = connectionId,
        name = "f",
        updatedAt = 1L,
    )
}
