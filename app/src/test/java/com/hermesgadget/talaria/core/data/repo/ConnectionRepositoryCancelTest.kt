/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.data.repo

import com.hermesgadget.talaria.core.data.db.TalariaDatabase
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.data.prefs.SettingsStore
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.core.network.HermesClientFactory
import com.hermesgadget.talaria.core.network.WsAuthHelper
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * H2/M8: ConnectionRepository.testConnection must propagate cancellation —
 * a cancelled test must never surface as a phantom Result.failure.
 */
class ConnectionRepositoryCancelTest {
    @Test
    fun `testConnection propagates cancellation instead of wrapping it`() = runTest {
        val snapshot = ConnectionSnapshot.from(
            ConnectionProfile(
                id = "cancel-test",
                name = "cancel",
                baseUrl = "https://cancel.example",
                authMode = AuthMode.NONE,
                managementProfile = "",
            ),
            ConnectionSecrets(),
        )
        val store = mockk<SecureConnectionStore>()
        every { store.snapshotFor(snapshot.connectionId, null) } returns snapshot
        every { store.profiles } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        every { store.activeId } returns kotlinx.coroutines.flow.MutableStateFlow(null)
        every { store.state } returns kotlinx.coroutines.flow.MutableStateFlow(
            com.hermesgadget.talaria.core.data.prefs.SecureConnectionStoreState.Available(0),
        )
        val api = mockk<HermesApi>()
        coEvery { api.getStatus(any()) } throws CancellationException("test canceled")
        val clientFactory = mockk<HermesClientFactory>()
        every { clientFactory.api(snapshot) } returns api
        val repo = ConnectionRepository(
            store,
            clientFactory,
            mockk(relaxed = true),
            mockk<TalariaDatabase>(relaxed = true),
            mockk<SettingsStore>(relaxed = true),
            mockk<HermesRepository>(relaxed = true),
        )

        val thrown = try {
            repo.testConnection(snapshot)
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }
        assertNotNull("cancellation must propagate, not become a failure", thrown)
    }
}
