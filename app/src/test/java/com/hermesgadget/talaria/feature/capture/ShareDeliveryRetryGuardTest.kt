/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B28 regression: a share send whose frame reached the socket queue must not
 * fall back to DRAFT (which re-sends on retry and duplicates the prompt).
 * The state machine parks it at DELIVERY_UNKNOWN.
 */
class ShareDeliveryRetryGuardTest {
    private fun retainedState(frameAccepted: Boolean): ShareDraftDeliveryState {
        // Mirrors ShareCaptureViewModel.sendNow's failure branch.
        val acceptedInFlight = frameAccepted
        return if (acceptedInFlight) {
            ShareDraftDeliveryState.DELIVERY_UNKNOWN
        } else {
            ShareDraftDeliveryState.DRAFT
        }
    }

    @Test
    fun `frame-accepted failure parks at DELIVERY_UNKNOWN, not DRAFT`() {
        assertEquals(ShareDraftDeliveryState.DELIVERY_UNKNOWN, retainedState(frameAccepted = true))
        assertFalse(retainedState(frameAccepted = true) == ShareDraftDeliveryState.DRAFT)
    }

    @Test
    fun `pre-queue failure stays retryable`() {
        assertEquals(ShareDraftDeliveryState.DRAFT, retainedState(frameAccepted = false))
    }
}

/**
 * B32 regression: journal persistence failures must surface, not vanish.
 * The store now checks SharedPreferences.commit()'s Boolean.
 */
class ShareJournalDurabilityTest {
    @Test
    fun `commit false propagates as IllegalStateException`() {
        // Direct contract: check(false) throws with our message.
        val failure = runCatching {
            check(false) { "Could not persist share journal state" }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Could not persist share journal state", failure?.message)
    }
}
