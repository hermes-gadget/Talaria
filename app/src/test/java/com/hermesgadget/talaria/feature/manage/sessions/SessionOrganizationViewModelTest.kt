/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.feature.manage.sessions

import com.hermesgadget.talaria.core.data.db.LocalSessionCollectionKind
import com.hermesgadget.talaria.core.data.repo.SavedSessionFilter
import com.hermesgadget.talaria.core.data.repo.SessionOrganizationSnapshot
import com.hermesgadget.talaria.core.data.repo.SessionOrganizationStore
import com.hermesgadget.talaria.util.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionOrganizationViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun favoriteMutationUsesTheCapturedConnectionScope() = runTest {
        val store = FakeStore()
        val vm = SessionOrganizationViewModel(store, connectionIdProvider = { "scope-a" })
        advanceUntilIdle()

        vm.toggleFavorite("session-1")
        advanceUntilIdle()

        assertEquals("scope-a", store.favoriteConnectionId)
        assertEquals("session-1", store.favoriteSessionId)
        assertTrue(vm.ui.value.organization.favoriteSessionIds.contains("session-1"))
    }

    @Test
    fun savedFilterMutationIsReboundToTheCapturedScope() = runTest {
        val store = FakeStore()
        val vm = SessionOrganizationViewModel(store, connectionIdProvider = { "scope-a" })
        advanceUntilIdle()

        vm.saveFilter(
            SavedSessionFilter(
                connectionId = "wrong-scope",
                name = "CLI",
                source = "cli",
            ),
        )
        advanceUntilIdle()

        assertEquals("scope-a", store.savedFilter?.connectionId)
    }

    private class FakeStore : SessionOrganizationStore {
        private val state = MutableStateFlow(SessionOrganizationSnapshot())
        var favoriteConnectionId: String? = null
        var favoriteSessionId: String? = null
        var savedFilter: SavedSessionFilter? = null

        override fun observe(connectionId: String): Flow<SessionOrganizationSnapshot> = state

        override suspend fun createCollection(
            connectionId: String,
            name: String,
            kind: LocalSessionCollectionKind,
        ): Long = 1

        override suspend fun deleteCollection(connectionId: String, collectionId: Long) = Unit

        override suspend fun setCollectionMembership(
            connectionId: String,
            sessionId: String,
            collectionId: Long,
            assigned: Boolean,
        ) = Unit

        override suspend fun setFavorite(connectionId: String, sessionId: String, favorite: Boolean) {
            favoriteConnectionId = connectionId
            favoriteSessionId = sessionId
            state.value = state.value.copy(
                favoriteSessionIds = if (favorite) setOf(sessionId) else emptySet(),
            )
        }

        override suspend fun saveFilter(filter: SavedSessionFilter): Long {
            savedFilter = filter
            return 1
        }

        override suspend fun deleteFilter(connectionId: String, filterId: Long) = Unit
    }
}
