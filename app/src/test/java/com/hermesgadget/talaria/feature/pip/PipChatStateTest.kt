/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */

package com.hermesgadget.talaria.feature.pip

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PipChatStateTest {
    @Test
    fun userLeaveEntersOnlyBeforeTheActivityIsInPip() {
        val initial = PipModeState()
        assertTrue(initial.shouldEnterOnUserLeave(supportsPictureInPicture = true, isFinishing = false))

        val entered = initial.onPictureInPictureModeChanged(true)
        assertFalse(entered.shouldEnterOnUserLeave(supportsPictureInPicture = true, isFinishing = false))
        assertFalse(entered.shouldEnterOnUserLeave(supportsPictureInPicture = false, isFinishing = false))
    }

    @Test
    fun leavingPipRequestsTheFullApp() {
        val state = PipModeState().onPictureInPictureModeChanged(true)
            .onPictureInPictureModeChanged(false)

        assertTrue(state.shouldReturnToMain(isFinishing = false))
        assertFalse(state.shouldReturnToMain(isFinishing = true))
    }

    @Test
    fun snapshotIntentRoundTripsReadOnlyMessagesAndStreamingText() {
        val snapshot = PipChatSnapshot(
            title = "Build agent",
            messages = listOf(
                PipChatMessage("user", "hello"),
                PipChatMessage("assistant", "working"),
            ),
            streamingText = "still streaming",
        )

        val intent = PipChatIntent.create(
            context = ApplicationProvider.getApplicationContext(),
            snapshot = snapshot,
        )

        assertEquals(snapshot, PipChatIntent.read(intent))
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0)
    }
}
