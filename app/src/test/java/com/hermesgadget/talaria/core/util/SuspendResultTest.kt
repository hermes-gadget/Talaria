/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.core.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertSame
import org.junit.Test

class SuspendResultTest {
    @Test
    fun `cancellation is rethrown instead of returned as a failure`() = runTest {
        val cancellation = CancellationException("lifecycle stopped")

        val thrown = try {
            suspendResult { throw cancellation }
            fail("suspendResult must rethrow cancellation")
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `ordinary failures remain result failures`() = runTest {
        val result = suspendResult<String> { error("network down") }

        assertEquals("network down", result.exceptionOrNull()?.message)
    }
}
