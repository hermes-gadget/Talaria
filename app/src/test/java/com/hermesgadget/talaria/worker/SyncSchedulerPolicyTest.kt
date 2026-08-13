/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.worker

import androidx.work.ExistingPeriodicWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSchedulerPolicyTest {
    @Test
    fun `same interval keeps the existing periodic work`() {
        assertEquals(
            ExistingPeriodicWorkPolicy.KEEP,
            periodicSyncPolicy(lastEnqueuedMinutes = 30, minutes = 30),
        )
    }

    @Test
    fun `changed interval replaces the periodic work`() {
        assertEquals(
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicSyncPolicy(lastEnqueuedMinutes = 15, minutes = 30),
        )
    }

    @Test
    fun `never-enqueued interval replaces the periodic work`() {
        assertEquals(
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicSyncPolicy(lastEnqueuedMinutes = null, minutes = 30),
        )
    }
}
