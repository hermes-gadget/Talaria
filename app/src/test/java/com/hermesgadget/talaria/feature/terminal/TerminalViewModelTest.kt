/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.hermesgadget.talaria.feature.terminal

import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.repo.ChatRepository
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.core.network.HermesClientFactory
import com.hermesgadget.talaria.di.AppContainer
import com.hermesgadget.talaria.domain.model.TerminalBackendRow
import com.hermesgadget.talaria.domain.model.TerminalBackendsResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalViewModelTest {

    @After
    fun tearDown() {
        unmockkObject(TalariaApp.Companion)
        Dispatchers.resetMain()
    }

    @Test
    fun `backend selection does not strand an in-flight backend load`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = mockk<TalariaApp>()
        val container = mockk<AppContainer>()
        val factory = mockk<HermesClientFactory>()
        val api = mockk<HermesApi>()
        val chatRepository = mockk<ChatRepository>()
        val snapshot = ConnectionSnapshot.anonymous()
        val loadGate = CompletableDeferred<Unit>()
        val response = TerminalBackendsResponse(
            active = "pty",
            backends = listOf(TerminalBackendRow(name = "pty", active = true)),
        )

        mockkObject(TalariaApp.Companion)
        every { TalariaApp.instance } returns app
        every { app.container } returns container
        every { container.clientFactory } returns factory
        every { factory.snapshot() } returns snapshot
        every { factory.api(snapshot) } returns api
        coEvery { api.getTerminalBackends(any()) } coAnswers {
            loadGate.await()
            response
        }
        coEvery { api.selectTerminalBackend(any(), any()) } returns buildJsonObject { }

        val viewModel = TerminalViewModel(chatRepository = chatRepository)
        viewModel.loadBackends()
        assertTrue(viewModel.ui.value.backendsLoading)

        viewModel.selectBackend("pty")
        assertFalse(viewModel.ui.value.backendSelecting != null)

        loadGate.complete(Unit)
        withTimeout(5_000) {
            while (viewModel.ui.value.backendsLoading) {
                kotlinx.coroutines.yield()
            }
        }
        assertFalse(viewModel.ui.value.backendsLoading)
        assertTrue(viewModel.ui.value.backends?.backends?.single()?.active == true)
    }
}
