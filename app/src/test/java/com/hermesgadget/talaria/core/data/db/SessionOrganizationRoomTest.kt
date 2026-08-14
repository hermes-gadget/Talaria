/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hermesgadget.talaria.core.data.repo.SessionOrganizationRepository
import kotlinx.coroutines.flow.first
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
class SessionOrganizationRoomTest {
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
    fun localOrganizationStaysConnectionScoped() = runTest {
        val dao = database.sessionOrganization()
        val labelA = dao.insertCollection(
            LocalSessionCollectionEntity(
                connectionId = "scope-a",
                name = "Needs review",
                kind = LocalSessionCollectionKind.LABEL.name,
            ),
        )
        val labelB = dao.insertCollection(
            LocalSessionCollectionEntity(
                connectionId = "scope-b",
                name = "Needs review",
                kind = LocalSessionCollectionKind.LABEL.name,
            ),
        )
        dao.addCollectionLink(
            LocalSessionCollectionLinkEntity("scope-a", "same-session-id", labelA),
        )
        dao.addFavorite(LocalSessionFavoriteEntity("scope-a", "same-session-id"))
        dao.addFavorite(LocalSessionFavoriteEntity("scope-b", "same-session-id"))
        dao.upsertSavedFilter(
            SavedSessionFilterEntity(
                connectionId = "scope-a",
                name = "Review",
                labelId = labelA,
            ),
        )

        assertEquals(listOf(labelA), dao.getCollections("scope-a").map { it.id })
        assertEquals(listOf(labelB), dao.getCollections("scope-b").map { it.id })
        assertEquals(
            listOf("same-session-id"),
            dao.observeFavorites("scope-a").first().map { it.sessionId },
        )
        assertEquals(
            listOf("Review"),
            dao.observeSavedFilters("scope-a").first().map { it.name },
        )
        assertTrue(dao.observeSavedFilters("scope-b").first().isEmpty())
    }

    @Test
    fun deletingCachedSessionRemovesOnlyItsLocalMetadata() = runTest {
        val scopeA = "scope-a"
        val scopeB = "scope-b"
        database.sessions().upsertAll(
            listOf(
                cachedSession("session-1", scopeA),
                cachedSession("session-1", scopeB),
            ),
        )
        val collectionId = database.sessionOrganization().insertCollection(
            LocalSessionCollectionEntity(
                connectionId = scopeA,
                name = "Keep",
                kind = LocalSessionCollectionKind.LABEL.name,
            ),
        )
        database.sessionOrganization().addCollectionLink(
            LocalSessionCollectionLinkEntity(scopeA, "session-1", collectionId),
        )
        database.sessionOrganization().addFavorite(
            LocalSessionFavoriteEntity(scopeA, "session-1"),
        )
        database.sessionOrganization().addFavorite(
            LocalSessionFavoriteEntity(scopeB, "session-1"),
        )

        database.reconcileSessionCache(scopeA, listOf("session-1"), emptyList())

        assertTrue(database.sessionOrganization().observeFavorites(scopeA).first().isEmpty())
        assertEquals(
            listOf("session-1"),
            database.sessionOrganization().observeFavorites(scopeB).first().map { it.sessionId },
        )
        assertTrue(database.sessionOrganization().observeCollectionLinks(scopeA).first().isEmpty())
    }

    @Test
    fun collectionMembershipRejectsForeignCollections() = runTest {
        val dao = database.sessionOrganization()
        val foreignCollection = dao.insertCollection(
            LocalSessionCollectionEntity(
                connectionId = "scope-b",
                name = "Foreign",
                kind = LocalSessionCollectionKind.LABEL.name,
            ),
        )
        val repository = SessionOrganizationRepository(dao)

        // scope-a tries to link its session to scope-b's collection: denied.
        repository.setCollectionMembership(
            connectionId = "scope-a",
            sessionId = "session-1",
            collectionId = foreignCollection,
            assigned = true,
        )
        assertTrue(dao.observeCollectionLinks("scope-a").first().isEmpty())

        // A collection owned by the same connection is linkable.
        val ownCollection = dao.insertCollection(
            LocalSessionCollectionEntity(
                connectionId = "scope-a",
                name = "Mine",
                kind = LocalSessionCollectionKind.LABEL.name,
            ),
        )
        repository.setCollectionMembership(
            connectionId = "scope-a",
            sessionId = "session-1",
            collectionId = ownCollection,
            assigned = true,
        )
        assertEquals(1, dao.observeCollectionLinks("scope-a").first().size)
    }

    private fun cachedSession(id: String, connectionId: String) = CachedSessionEntity(
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
}
