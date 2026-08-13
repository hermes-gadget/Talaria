/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.feature.manage

import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.feature.manage.kanban.KanbanUiState
import com.hermesgadget.talaria.feature.manage.kanban.KanbanViewModel
import com.hermesgadget.talaria.feature.manage.plugins.PluginsUiState
import com.hermesgadget.talaria.feature.manage.plugins.PluginsViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H1: refresh/runAction must be generation-guarded — a slow response for one
 * board/plugin must never replace the content the user is currently looking
 * at, and a double-tap must not run a destructive action twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManageOverlapGuardsTest {

    private fun boardJson(columnName: String) = buildJsonObject {
        put("columns", JsonArray(listOf(buildJsonObject { put("name", columnName) })))
    }

    @Test
    fun `slow kanban board load cannot overwrite the newer board`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val api = mockk<HermesApi>()
        val gate = CompletableDeferred<Unit>()
        coEvery { api.getKanbanBoard(board = "a", includeArchived = false) } coAnswers {
            gate.await()
            boardJson("BOARD_A_ONLY")
        }
        coEvery { api.getKanbanBoard(board = "b", includeArchived = false) } coAnswers { boardJson("BOARD_B_ONLY") }
        coEvery { api.getKanbanBoards() } returns buildJsonObject { put("current", JsonPrimitive("b")) }
        coEvery { api.getKanbanStats() } returns JsonObject(emptyMap())
        coEvery { api.getKanbanAssignees() } returns JsonArray(emptyList())
        coEvery { api.getKanbanActiveWorkers() } returns JsonArray(emptyList())

        val vm = KanbanViewModel(api)
        testScheduler.runCurrent()
        vm.refresh("a")
        testScheduler.runCurrent()
        vm.refresh("b")
        testScheduler.runCurrent()
        // B's content is visible immediately (its fetch completes first).
        assertTrue(
            (vm.ui.value as KanbanUiState.Content).value.columns.any { it.name == "BOARD_B_ONLY" },
        )
        // Release A's slow fetch: it must NOT replace B's board.
        gate.complete(Unit)
        testScheduler.runCurrent()
        val finalContent = (vm.ui.value as KanbanUiState.Content).value
        assertTrue(finalContent.columns.any { it.name == "BOARD_B_ONLY" })
        assertTrue(finalContent.columns.none { it.name == "BOARD_A_ONLY" })
    }

    @Test
    fun `double-tapped plugin action runs only once`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val api = mockk<HermesApi>()
        val gate = CompletableDeferred<Unit>()
        coEvery { api.getDashboardPlugins() } returns buildJsonObject { put("plugins", JsonArray(emptyList())) }
        coEvery { api.getDashboardPluginsHub() } returns JsonObject(emptyMap())
        var installs = 0
        coEvery { api.installAgentPlugin(any()) } coAnswers {
            installs += 1
            gate.await()
            JsonObject(emptyMap())
        }

        val vm = PluginsViewModel(api)
        testScheduler.runCurrent()
        vm.installAgentPlugin("com.example", force = false, enable = true)
        testScheduler.runCurrent()
        // Second tap while busy: re-entry guard must reject it.
        vm.installAgentPlugin("com.example", force = false, enable = true)
        testScheduler.runCurrent()
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertTrue("install must run exactly once", installs == 1)
    }
}
