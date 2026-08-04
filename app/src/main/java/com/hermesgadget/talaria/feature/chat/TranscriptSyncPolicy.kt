/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.feature.chat

import com.hermesgadget.talaria.core.network.HermesSideEvent

internal enum class TranscriptDirtyReason {
    MESSAGE_COMPLETE,
    SESSION_ENDED,
    SESSIONS_CHANGED,
    EVENT_GAP,
    TRANSPORT_ERROR,
    TAB_SELECTED,
    FOREGROUND_RESUME,
}

internal data class TranscriptDirtySignal(
    val reason: TranscriptDirtyReason,
    val sessionId: String? = null,
    val refreshSessionRegistry: Boolean = false,
    val eventsHealthy: Boolean? = null,
)

internal object TranscriptSyncPolicy {
    const val IMMEDIATE_FALLBACK_DELAY_MS = 2_500L
    const val MIN_FALLBACK_DELAY_MS = 15_000L
    const val MAX_FALLBACK_DELAY_MS = 60_000L

    fun dirtySignal(event: HermesSideEvent): TranscriptDirtySignal? = when (event) {
        is HermesSideEvent.MessageComplete -> TranscriptDirtySignal(
            reason = TranscriptDirtyReason.MESSAGE_COMPLETE,
            sessionId = event.sessionId,
            eventsHealthy = true,
        )
        is HermesSideEvent.SessionEnded -> TranscriptDirtySignal(
            reason = TranscriptDirtyReason.SESSION_ENDED,
            sessionId = event.sessionId,
            refreshSessionRegistry = true,
            eventsHealthy = true,
        )
        is HermesSideEvent.SessionsChanged -> TranscriptDirtySignal(
            reason = TranscriptDirtyReason.SESSIONS_CHANGED,
            sessionId = event.sessionId,
            refreshSessionRegistry = true,
            eventsHealthy = true,
        )
        is HermesSideEvent.EventGap -> TranscriptDirtySignal(
            reason = TranscriptDirtyReason.EVENT_GAP,
            sessionId = event.sessionId,
            refreshSessionRegistry = true,
            eventsHealthy = false,
        )
        is HermesSideEvent.TransportError -> TranscriptDirtySignal(
            reason = TranscriptDirtyReason.TRANSPORT_ERROR,
            eventsHealthy = false,
        )
        else -> null
    }

    fun shouldRunFallback(
        lifecycleStarted: Boolean,
        activeTab: Boolean,
        visible: Boolean,
        working: Boolean,
        eventsHealthy: Boolean,
    ): Boolean = lifecycleStarted && activeTab && visible && working && !eventsHealthy

    fun nextFallbackDelay(previousDelayMs: Long, refreshSucceeded: Boolean): Long {
        val previous = previousDelayMs.coerceIn(IMMEDIATE_FALLBACK_DELAY_MS, MAX_FALLBACK_DELAY_MS)
        val next = if (previous <= IMMEDIATE_FALLBACK_DELAY_MS) {
            MIN_FALLBACK_DELAY_MS
        } else {
            (previous * 2L).coerceAtMost(MAX_FALLBACK_DELAY_MS)
        }
        // A failed read must never reset to the fast loop. Both success and
        // failure back off after the one immediate 2.5s recovery attempt.
        return if (refreshSucceeded) next else next.coerceAtLeast(MIN_FALLBACK_DELAY_MS)
    }
}
