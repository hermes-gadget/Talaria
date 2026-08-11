/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.hermesgadget.talaria.feature.manage.models

import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.core.network.ConnectionScope
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import com.hermesgadget.talaria.domain.model.ModelInfo
import com.hermesgadget.talaria.domain.model.ModelProvider
import io.mockk.coEvery
import io.mockk.every
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
 * A->B profile-switch regression net for the Models destination (#54, follow-up to #22):
 * a provider list answered for connection A after the switch must never land in the
 * B-bound UI state.
 */
class ModelsScopeSwitchTest {

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
    fun `stale A provider list never lands in B UI state`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val scopeA = scope("a", 1)
        val scopeB = scope("b", 1)
        flow = MutableStateFlow(scopeA)
        val gateA = CompletableDeferred<Unit>()

        val repo = mockk<HermesRepository>()
        coEvery { repo.getModelProviders(any()) } coAnswers {
            if (firstArg<ConnectionSnapshot?>()?.connectionId == "a") {
                gateA.await()
                Result.success(listOf(ModelProvider(slug = "stale-a", name = "Provider A")))
            } else {
                Result.success(listOf(ModelProvider(slug = "fresh-b", name = "Provider B")))
            }
        }
        coEvery { repo.getModelInfo(any()) } returns Result.success(ModelInfo(model = "m", provider = "p"))

        val vm = ModelsViewModel(
            repo = repo,
            apiProvider = { mockk() },
            profileProvider = { null },
            scopeFlow = flow,
        )
        // refresh() starts one request against A; the switch happens while it is in flight.
        vm.refresh()
        flow.value = scopeB
        gateA.complete(Unit)

        withTimeout(5_000) {
            val state = vm.ui.value
            assertFalse(state.loading)
            assertEquals(listOf("fresh-b"), state.providers.map { it.slug })
            assertTrue("stale A provider rendered under B", state.providers.none { it.slug == "stale-a" })
            assertNull(state.error)
        }
    }

    @Test
    fun `stale A model info never overwrites B current model`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val scopeA = scope("a", 1)
        val scopeB = scope("b", 1)
        flow = MutableStateFlow(scopeA)
        val gateA = CompletableDeferred<Unit>()

        val repo = mockk<HermesRepository>()
        coEvery { repo.getModelInfo(any()) } coAnswers {
            if (firstArg<ConnectionSnapshot?>()?.connectionId == "a") {
                gateA.await()
                Result.success(ModelInfo(model = "model-from-a", provider = "provider-a"))
            } else {
                Result.success(ModelInfo(model = "model-from-b", provider = "provider-b"))
            }
        }
        coEvery { repo.getModelProviders(any()) } returns Result.success(emptyList())

        val vm = ModelsViewModel(
            repo = repo,
            apiProvider = { mockk() },
            profileProvider = { null },
            scopeFlow = flow,
        )
        vm.refresh()
        flow.value = scopeB
        gateA.complete(Unit)

        withTimeout(5_000) {
            val state = vm.ui.value
            assertEquals("model-from-b", state.currentModel)
            assertTrue("A model info leaked into B", state.currentModel != "model-from-a")
        }
    }
}
