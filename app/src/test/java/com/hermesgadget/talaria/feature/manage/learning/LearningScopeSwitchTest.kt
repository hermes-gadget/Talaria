/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.hermesgadget.talaria.feature.manage.learning

import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.core.network.ConnectionScope
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A->B profile-switch regression net for the Learning destination (#54, follow-up to #22):
 * a graph answered for connection A after the switch must never be rendered in the
 * B-bound UI state.
 *
 * Main is installed inside runTest sharing its scheduler so ViewModel coroutines are
 * owned by the test scope: a leaked coroutine from an earlier test class can otherwise
 * report an exception into this test's scheduler (UncaughtExceptionsBeforeTest flake).
 */
class LearningScopeSwitchTest {

    private lateinit var flow: MutableStateFlow<ConnectionScope?>

    private fun scope(id: String, generation: Long) =
        ConnectionScope(
            ConnectionSnapshot(
                ConnectionProfile(id = id, name = id, baseUrl = "http://$id.test", createdAt = 0L),
                ConnectionSecrets(),
            ),
            generation,
        )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `stale A graph never lands in B UI state`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val scopeA = scope("a", 1)
        val scopeB = scope("b", 1)
        flow = MutableStateFlow(scopeA)
        val gateA = CompletableDeferred<Unit>()

        val graphSource = mockk<LearningGraphSource>()
        coEvery { graphSource.load(any()) } coAnswers {
            if (firstArg<ConnectionSnapshot?>()?.connectionId == "a") {
                gateA.await()
                Result.success(
                    LearningGraphSnapshot(
                        nodes = listOf(
                            LearningMapNode(
                                id = "stale-node-a",
                                label = "Node from A",
                                kind = "concept",
                                category = "test",
                                useCount = 1,
                                state = "fresh",
                                createdBy = null,
                                pinned = false,
                                timestamp = null,
                            ),
                        ),
                    ),
                )
            } else {
                Result.success(
                    LearningGraphSnapshot(
                        nodes = listOf(
                            LearningMapNode(
                                id = "fresh-node-b",
                                label = "Node from B",
                                kind = "concept",
                                category = "test",
                                useCount = 1,
                                state = "fresh",
                                createdBy = null,
                                pinned = false,
                                timestamp = null,
                            ),
                        ),
                    ),
                )
            }
        }
        val repo = mockk<HermesRepository>()

        val vm = LearningViewModel(repo = repo, graphSource = graphSource, scopeFlow = flow)
        vm.refresh()
        flow.value = scopeB
        gateA.complete(Unit)

        withTimeout(5_000) {
            val state = vm.ui.value
            assertFalse(state.loading)
            assertEquals(listOf("fresh-node-b"), state.graph?.nodes?.map { it.id })
            assertTrue("stale A node rendered under B", state.graph?.nodes?.none { it.id == "stale-node-a" } ?: false)
            assertNull(state.error)
        }
    }
}
