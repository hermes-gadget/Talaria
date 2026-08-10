/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.feature.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScopeBoundDraftSaverTest {
    @Test
    fun `profile switch during debounce never crosses draft scope`() =
        runTest(StandardTestDispatcher()) {
            val writes = mutableListOf<Pair<String, String>>()
            val saver = ScopeBoundDraftSaver(
                coroutineScope = this,
                persist = { scopeId, text -> writes += scopeId to text },
            )
            val profileA = DraftPersistenceScope("profile-a", generation = 1L)
            val profileB = DraftPersistenceScope("profile-b", generation = 2L)

            saver.rebind(profileA)
            saver.schedule(profileA, "A-secret")
            advanceTimeBy(349)

            saver.rebind(profileB)
            saver.schedule(profileB, "B-draft")
            advanceTimeBy(349)
            assertEquals(emptyList<Pair<String, String>>(), writes)

            advanceTimeBy(1)
            advanceUntilIdle()

            assertEquals(listOf(profileB.scopeId to "B-draft"), writes)
        }
}
