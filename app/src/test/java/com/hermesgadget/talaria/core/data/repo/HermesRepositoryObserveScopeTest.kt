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
import com.hermesgadget.talaria.core.data.db.TalariaDatabase
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.network.ConnectionScope
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.HermesClientFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M2: observeActivity/observeSessions must follow the ACTIVE scope, not the
 * scope captured when the flow was created. A collector that outlives a
 * profile switch (widget, test, non-keyed host) sees the new server's
 * timeline instead of freezing the old one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HermesRepositoryObserveScopeTest {
    private lateinit var database: TalariaDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TalariaDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun scope(connectionId: String, generation: Long): ConnectionScope {
        val profile = com.hermesgadget.talaria.domain.model.ConnectionProfile(
            id = connectionId,
            name = connectionId,
            baseUrl = "https://$connectionId.example",
            authMode = com.hermesgadget.talaria.domain.model.AuthMode.NONE,
            managementProfile = "",
        )
        return ConnectionScope(
            snapshot = ConnectionSnapshot.from(profile, com.hermesgadget.talaria.domain.model.ConnectionSecrets()),
            generation = generation,
        )
    }

    @Test
    fun `activity observation follows the active scope across a switch`() = runTest {
        val scopeFlow = MutableStateFlow<ConnectionScope?>(scope("conn-a", 1L))
        val store = mockk<SecureConnectionStore>()
        every { store.scope } returns scopeFlow
        val clientFactory = mockk<HermesClientFactory>(relaxed = true)
        val repository = HermesRepository(clientFactory, database, store, null)

        database.activity().insert(
            ActivityEventEntity(connectionId = "conn-a", type = "chat", title = "A", body = "a", createdAt = 1L),
        )
        database.activity().insert(
            ActivityEventEntity(connectionId = "conn-b", type = "chat", title = "B", body = "b", createdAt = 2L),
        )

        val seenB = CompletableDeferred<Unit>()
        val emissions = mutableListOf<List<ActivityEventEntity>>()
        val job = launch {
            repository.observeActivity().collect { rows ->
                emissions += rows
                if (rows.any { it.title == "B" }) seenB.complete(Unit)
            }
        }
        pumpUntil { emissions.isNotEmpty() }
        assertEquals(listOf("A"), emissions.first().map { it.title })

        // Switch the active connection while the collector is still subscribed.
        scopeFlow.value = scope("conn-b", 2L)
        pumpUntil { seenB.isCompleted }
        job.cancel()

        assertTrue(emissions.any { rows -> rows.any { it.title == "B" } })
    }

    private suspend fun TestScope.pumpUntil(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                while (!condition()) {
                    testScheduler.runCurrent()
                    delay(1L)
                }
            }
        }
    }
}
