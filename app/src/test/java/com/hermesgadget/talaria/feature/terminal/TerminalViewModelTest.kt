/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.hermesgadget.talaria.feature.terminal

import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.repo.ChatRepository
import com.hermesgadget.talaria.core.network.PtySendException
import com.hermesgadget.talaria.core.network.PtySendReceipt
import com.hermesgadget.talaria.di.AppContainer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalViewModelTest {

    @After
    fun tearDown() {
        unmockkObject(TalariaApp.Companion)
    }

    @Test
    fun `rejected input is preserved and partial delivery is reported`() {
        val viewModel = viewModel()
        viewModel.updateInput("echo hello")

        viewModel.handleInputSendResult(
            line = "echo hello",
            result = Result.failure(
                PtySendException(
                    "PTY rejected the Enter frame",
                    PtySendReceipt(bodyAccepted = true),
                ),
            ),
        )

        assertEquals("echo hello", viewModel.ui.value.input)
        assertTrue(viewModel.ui.value.sidecarError.orEmpty().contains("partial"))
    }

    @Test
    fun `accepted input is recorded and cleared`() {
        val viewModel = viewModel()
        viewModel.updateInput("echo hello")

        viewModel.handleInputSendResult(
            line = "echo hello",
            result = Result.success(PtySendReceipt(bodyAccepted = true, enterAccepted = true)),
        )

        assertEquals("", viewModel.ui.value.input)
        assertEquals(null, viewModel.ui.value.sidecarError)
        assertTrue(viewModel.historyUp())
        assertEquals("echo hello", viewModel.ui.value.input)
    }

    private fun viewModel(): TerminalViewModel {
        val app = mockk<TalariaApp>()
        val container = mockk<AppContainer>()
        mockkObject(TalariaApp.Companion)
        every { TalariaApp.instance } returns app
        every { app.container } returns container
        return TerminalViewModel(chatRepository = mockk<ChatRepository>())
    }
}
