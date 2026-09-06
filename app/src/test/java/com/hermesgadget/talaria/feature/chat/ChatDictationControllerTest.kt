/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.hermesgadget.talaria.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q07 regression tests: dictation ownership lives in ChatDictationController with
 * an independently testable scope guard and capability cache.
 */
class ChatDictationControllerTest {

    private fun controller(nowMillis: Long = 1_000L) =
        ChatDictationController(now = { nowMillis })

    @Test
    fun `server dictation session tracks tab and generation`() {
        val c = controller()
        assertFalse(c.isServerDictationActive)
        c.beginServerDictation("t1", 7L)
        assertTrue(c.isServerDictationActive)
        assertEquals("t1", c.serverTabId)
        assertEquals(7L, c.serverScopeGeneration)
        c.endServerDictation()
        assertFalse(c.isServerDictationActive)
        assertNull(c.serverTabId)
        assertNull(c.serverScopeGeneration)
    }

    @Test
    fun `voice scope guard matches scope, generation and live tab set`() {
        val c = controller()
        // Same scope, same generation, tab present -> allowed.
        assertTrue(
            c.isCurrentVoiceScope(
                scopeId = "scope-a", generation = 3L, tabId = "tab-1",
                activeScopeId = "scope-a", liveGeneration = 3L,
                activeTabIds = setOf("tab-1", "tab-2"),
            ),
        )
        // Scope changed -> blocked.
        assertFalse(
            c.isCurrentVoiceScope(
                scopeId = "scope-a", generation = 3L, tabId = "tab-1",
                activeScopeId = "scope-b", liveGeneration = 3L,
                activeTabIds = setOf("tab-1"),
            ),
        )
        // Generation changed (connection switch) -> blocked.
        assertFalse(
            c.isCurrentVoiceScope(
                scopeId = "scope-a", generation = 3L, tabId = "tab-1",
                activeScopeId = "scope-a", liveGeneration = 4L,
                activeTabIds = setOf("tab-1"),
            ),
        )
        // Tab closed -> blocked.
        assertFalse(
            c.isCurrentVoiceScope(
                scopeId = "scope-a", generation = 3L, tabId = "tab-9",
                activeScopeId = "scope-a", liveGeneration = 3L,
                activeTabIds = setOf("tab-1"),
            ),
        )
        // Null scope -> blocked.
        assertFalse(
            c.isCurrentVoiceScope(
                scopeId = null, generation = 3L, tabId = "tab-1",
                activeScopeId = "scope-a", liveGeneration = 3L,
                activeTabIds = setOf("tab-1"),
            ),
        )
    }

    @Test
    fun `capability cache honors TTL and invalidation`() {
        var now = 1_000L
        val c = ChatDictationController(now = { now })
        c.cacheCapability("scope", supported = true)
        assertEquals(true, c.cachedCapability("scope")?.supported)
        // Within TTL still valid.
        now += 4 * 60_000L
        assertTrue(c.cachedCapability("scope") != null)
        // Past TTL expires and self-cleans.
        now += 2 * 60_000L
        assertNull(c.cachedCapability("scope"))
        // Invalidate removes even fresh entries.
        c.cacheCapability("scope", supported = false)
        c.invalidateCapability("scope")
        assertNull(c.cachedCapability("scope"))
    }

    @Test
    fun `probe bookkeeping is one-shot per scope and clearable`() {
        val c = controller()
        assertFalse(c.isProbed("scope"))
        val genBefore = c.probeGeneration
        c.beginProbe("scope")
        assertTrue(c.isProbed("scope"))
        assertEquals(genBefore + 1, c.probeGeneration)
        c.clearProbes()
        assertFalse(c.isProbed("scope"))
    }
}
