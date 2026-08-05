/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.feature.chat

import com.hermesgadget.talaria.core.network.HermesSideEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptSyncPolicyTest {
    @Test
    fun `completion is scoped while session changes also refresh the registry`() {
        val completion = TranscriptSyncPolicy.dirtySignal(
            HermesSideEvent.MessageComplete("session-1", "done", "complete", null, null),
        )
        assertEquals(TranscriptDirtyReason.MESSAGE_COMPLETE, completion?.reason)
        assertEquals("session-1", completion?.sessionId)
        assertFalse(completion?.refreshSessionRegistry == true)

        val changed = TranscriptSyncPolicy.dirtySignal(
            HermesSideEvent.SessionsChanged("session-2", revision = "7", sequence = 12),
        )
        assertEquals(TranscriptDirtyReason.SESSIONS_CHANGED, changed?.reason)
        assertTrue(changed?.refreshSessionRegistry == true)
    }

    @Test
    fun `event gaps and transport failures select unhealthy fallback`() {
        val gap = TranscriptSyncPolicy.dirtySignal(HermesSideEvent.EventGap("missed", "s1"))
        assertEquals(false, gap?.eventsHealthy)
        assertTrue(
            TranscriptSyncPolicy.shouldRunFallback(
                lifecycleStarted = true,
                activeTab = true,
                visible = true,
                working = true,
                eventsHealthy = false,
            ),
        )
        assertFalse(
            TranscriptSyncPolicy.shouldRunFallback(
                lifecycleStarted = false,
                activeTab = true,
                visible = true,
                working = true,
                eventsHealthy = false,
            ),
        )
        assertFalse(
            TranscriptSyncPolicy.shouldRunFallback(
                lifecycleStarted = true,
                activeTab = false,
                visible = true,
                working = true,
                eventsHealthy = false,
            ),
        )
    }

    @Test
    fun `fallback backs off after the one fast recovery attempt and caps`() {
        assertEquals(
            TranscriptSyncPolicy.MIN_FALLBACK_DELAY_MS,
            TranscriptSyncPolicy.nextFallbackDelay(
                TranscriptSyncPolicy.IMMEDIATE_FALLBACK_DELAY_MS,
                refreshSucceeded = false,
            ),
        )
        assertEquals(
            TranscriptSyncPolicy.MAX_FALLBACK_DELAY_MS,
            TranscriptSyncPolicy.nextFallbackDelay(
                TranscriptSyncPolicy.MAX_FALLBACK_DELAY_MS,
                refreshSucceeded = true,
            ),
        )
    }

    @Test
    fun `five tabs keep inactive work quiet and resume emits one active scoped refresh`() =
        runTest(StandardTestDispatcher()) {
            val tabs = (1..5).map { "tab-$it" }
            val activeTab = tabs[2]

            assertTrue(
                tabs.all { tab ->
                    !TranscriptSyncPolicy.shouldRunFallback(
                        lifecycleStarted = false,
                        activeTab = tab == activeTab,
                        visible = false,
                        working = true,
                        eventsHealthy = false,
                    )
                },
            )
            assertEquals(
                listOf(activeTab),
                tabs.filter {
                    TranscriptSyncPolicy.shouldEmitRefreshSignal(
                        lifecycleStarted = true,
                        activeTab = it == activeTab,
                    )
                },
            )
            assertFalse(
                TranscriptSyncPolicy.shouldEmitRefreshSignal(
                    lifecycleStarted = false,
                    activeTab = true,
                ),
            )
        }
}
