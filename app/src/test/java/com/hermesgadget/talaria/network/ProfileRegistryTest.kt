/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.network

import com.hermesgadget.talaria.core.network.ProfileRegistry
import com.hermesgadget.talaria.domain.model.ProfileStreamState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProfileRegistryTest {
    @Before
    fun setUp() = ProfileRegistry.reset()

    @After
    fun tearDown() = ProfileRegistry.reset()

    @Test
    fun `transitions stay isolated per profile and expose active streams`() {
        ProfileRegistry.markConnecting("work", "work-session")
        ProfileRegistry.markStreaming("work", "work-session")
        ProfileRegistry.markActive("default", "default-session")

        var state = ProfileRegistry.state.value
        assertEquals(ProfileStreamState.STREAMING, state.streamingStates["work"]?.sessions?.get("work-session"))
        assertTrue(state.streamingStates["work"]?.hasStreams == true)
        assertTrue(state.streamingStates["default"]?.hasActiveSessions == true)
        assertFalse(state.streamingStates["default"]?.hasStreams == true)

        ProfileRegistry.markIdle("work", "work-session")
        state = ProfileRegistry.state.value
        assertFalse(state.streamingStates["work"]?.hasStreams == true)
        assertTrue(state.streamingStates["work"]?.hasActiveSessions == true)

        ProfileRegistry.markDisconnected("work", "work-session")
        state = ProfileRegistry.state.value
        assertFalse(state.streamingStates["work"]?.hasActiveSessions == true)
        assertTrue(state.streamingStates["default"]?.hasActiveSessions == true)
    }

    @Test
    fun `visible profiles are ordered before background profiles`() {
        assertEquals(
            listOf("research", "default", "work"),
            ProfileRegistry.orderProfiles(
                names = listOf("work", "default", "research"),
                preferredProfiles = listOf("research", "default"),
            ),
        )
    }
}
