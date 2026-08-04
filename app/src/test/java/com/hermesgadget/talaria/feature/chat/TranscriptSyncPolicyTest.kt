/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.feature.chat

import com.hermesgadget.talaria.core.network.HermesSideEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
