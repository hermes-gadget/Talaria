/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.manage.sessions

import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.domain.model.BulkDeleteSessionsResponse
import com.hermesgadget.talaria.domain.model.EmptySessionCount
import com.hermesgadget.talaria.domain.model.EmptySessionsDeleteResponse
import com.hermesgadget.talaria.domain.model.SessionImportResponse
import com.hermesgadget.talaria.domain.model.SessionStats
import com.hermesgadget.talaria.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionAdminViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun refreshLoadsStatsEmptyCountAndScopedPins() = runTest {
        val gateway = FakeSessionAdminGateway()
        val pins = InMemorySessionPinStore().apply { setPinned("scope-1", "pinned", true) }
        val vm = viewModel(gateway, pins)
        advanceUntilIdle()

        val content = (vm.ui.value as SessionAdminUiState.Content).value
        assertEquals(8, content.stats?.total)
        assertEquals(2, content.emptyCount)
        assertEquals(setOf("pinned"), content.pinnedIds)
        assertEquals(1, gateway.statsCalls)
    }

    @Test
    fun selectionCanSetIndividualAllAndClear() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setSelected("a", true)
        vm.setSelected("b", true)
        assertEquals(setOf("a", "b"), content(vm).selectedIds)

        vm.selectAll(listOf("x", "y"))
        assertEquals(setOf("x", "y"), content(vm).selectedIds)

        vm.clearSelection()
        assertTrue(content(vm).selectedIds.isEmpty())
    }

    @Test
    fun bulkDeleteUsesSelectedIdsAndRefreshesWithNotice() = runTest {
        val gateway = FakeSessionAdminGateway()
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.setSelected("a", true)
        vm.setSelected("b", true)
        vm.bulkDeleteSelected()
        advanceUntilIdle()

        assertEquals(setOf("a", "b"), gateway.bulkDeletedIds)
        assertEquals("Deleted 2 session(s)", content(vm).message)
        assertFalse(content(vm).busy)
    }

    @Test
    fun bulkDeleteIntersectsTheVisibleFilterBeforeCallingHermes() = runTest {
        val gateway = FakeSessionAdminGateway()
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.setSelected("visible", true)
        vm.setSelected("hidden", true)
        vm.bulkDeleteSelected(visibleIds = listOf("visible"))
        advanceUntilIdle()

        assertEquals(setOf("visible"), gateway.bulkDeletedIds)
    }

    @Test
    fun emptyDeleteRefreshesWithDeletedCount() = runTest {
        val gateway = FakeSessionAdminGateway()
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.deleteEmpty()
        advanceUntilIdle()

        assertTrue(gateway.emptyDeleteCalled)
        assertEquals("Deleted 2 empty session(s)", content(vm).message)
    }

    @Test
    fun emptyImportIsRejectedWithoutCallingGateway() = runTest {
        val gateway = FakeSessionAdminGateway()
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.importSessions(JsonArray(emptyList()))

        assertEquals("The selected file contains no sessions", content(vm).message)
        assertFalse(gateway.importCalled)
    }

    @Test
    fun importPassesJsonArrayAndReportsSkippedRows() = runTest {
        val gateway = FakeSessionAdminGateway()
        val vm = viewModel(gateway)
        advanceUntilIdle()
        val sessions = JsonArray(listOf(JsonObject(emptyMap())))

        vm.importSessions(sessions)
        advanceUntilIdle()

        assertEquals(sessions, gateway.importedSessions)
        assertEquals("Imported 1 session(s), skipped 1", content(vm).message)
    }

    @Test
    fun pinTogglePersistsAndUpdatesVisibleState() = runTest {
        val pins = InMemorySessionPinStore()
        val vm = viewModel(pinStore = pins)
        advanceUntilIdle()

        vm.togglePinned("session-1")
        assertEquals(setOf("session-1"), content(vm).pinnedIds)
        assertEquals(setOf("session-1"), pins.load("scope-1"))

        vm.togglePinned("session-1")
        assertTrue(content(vm).pinnedIds.isEmpty())
        assertTrue(pins.load("scope-1").isEmpty())
    }

    @Test
    fun compactionUsesGatewayAndRefreshesWithResultMessage() = runTest {
        val gateway = FakeSessionAdminGateway()
        val compactor = FakeSessionCompactionGateway()
        val vm = viewModel(gateway, compactionGateway = compactor)
        advanceUntilIdle()

        vm.compactSession("session-1")
        advanceUntilIdle()

        assertEquals("session-1", compactor.compactedId)
        assertEquals("Compacted session-1", content(vm).message)
        assertFalse(content(vm).busy)
    }

    @Test
    fun failurePreservesPreviousContent() = runTest {
        val gateway = FakeSessionAdminGateway().apply { statsFailure = IllegalStateException("offline") }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        val failure = vm.ui.value as SessionAdminUiState.Failure
        assertEquals("offline", failure.message)
        assertEquals(null, failure.previous)
    }

    @Test
    fun compactionResponseMapsLockAndStatusMessages() {
        val locked = parseSessionCompaction(
            JsonConfig.json.parseToJsonElement("""{"lock_held":true}""").jsonObject,
        )
        assertTrue(locked.lockHeld)

        val aborted = parseSessionCompaction(
            JsonConfig.json.parseToJsonElement("""{"status":"aborted"}""").jsonObject,
        )
        assertEquals("aborted", aborted.status)
    }

    @Test
    fun `destructive admin mutations reconcile the transcript cache after acknowledgement`() = runTest {
        val api = mockk<HermesApi>()
        var reconciliations = 0
        coEvery { api.bulkDeleteSessionsRaw(any()) } returns buildJsonObject {
            put("ok", true)
            put("deleted", 2)
        }
        coEvery { api.deleteEmptySessionsRaw(null) } returns buildJsonObject {
            put("ok", true)
            put("deleted", 1)
        }
        val gateway = HermesSessionAdminGateway(
            api = api,
            profileProvider = { null },
            reconcileAfterMutation = { reconciliations++ },
        )

        gateway.bulkDelete(listOf("a", "b"))
        gateway.deleteEmpty()

        assertEquals(2, reconciliations)
    }

    private fun viewModel(
        gateway: FakeSessionAdminGateway = FakeSessionAdminGateway(),
        pinStore: InMemorySessionPinStore = InMemorySessionPinStore(),
        compactionGateway: SessionCompactionGateway? = null,
    ) = SessionAdminViewModel(
        gateway = gateway,
        pinStore = pinStore,
        scopeIdProvider = { "scope-1" },
        compactionGateway = compactionGateway,
    )

    private fun content(vm: SessionAdminViewModel): SessionAdminContent =
        (vm.ui.value as SessionAdminUiState.Content).value

    private class InMemorySessionPinStore : SessionPinStore {
        private val values = mutableMapOf<String, MutableSet<String>>()

        override fun load(scopeId: String): Set<String> = values[scopeId].orEmpty().toSet()

        override fun setPinned(scopeId: String, sessionId: String, pinned: Boolean) {
            val ids = values.getOrPut(scopeId) { mutableSetOf() }
            if (pinned) ids += sessionId else ids -= sessionId
        }
    }

    private class FakeSessionCompactionGateway : SessionCompactionGateway {
        var compactedId: String? = null

        override suspend fun compact(sessionId: String): SessionCompactionResult {
            compactedId = sessionId
            return SessionCompactionResult(status = "compressed", message = "Compacted $sessionId")
        }
    }

    private class FakeSessionAdminGateway : SessionAdminGateway {
        var statsCalls = 0
        var statsFailure: Throwable? = null
        var bulkDeletedIds: Set<String> = emptySet()
        var emptyDeleteCalled = false
        var importCalled = false
        var importedSessions: JsonArray? = null

        override suspend fun stats(): SessionStats {
            statsCalls += 1
            statsFailure?.let { throw it }
            return SessionStats(total = 8, activeStore = 5, archived = 3, messages = 21)
        }

        override suspend fun bulkDelete(ids: List<String>): BulkDeleteSessionsResponse {
            bulkDeletedIds = ids.toSet()
            return BulkDeleteSessionsResponse(ok = true, deleted = ids.size)
        }

        override suspend fun emptyCount(): EmptySessionCount = EmptySessionCount(count = 2)

        override suspend fun deleteEmpty(): EmptySessionsDeleteResponse {
            emptyDeleteCalled = true
            return EmptySessionsDeleteResponse(ok = true, deleted = 2)
        }

        override suspend fun importSessions(sessions: JsonArray): SessionImportResponse {
            importCalled = true
            importedSessions = sessions
            return SessionImportResponse(ok = true, imported = 1, skipped = 1)
        }
    }
}
