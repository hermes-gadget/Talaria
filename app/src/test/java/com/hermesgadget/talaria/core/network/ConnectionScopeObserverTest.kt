/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.hermesgadget.talaria.core.network

import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression net for the #22 scope-observer semantics every switcher-bearing destination relies on. */
class ConnectionScopeObserverTest {

    private fun profile(id: String, baseUrl: String = "http://$id.test") =
        ConnectionProfile(id = id, name = id, baseUrl = baseUrl, createdAt = 0L)

    private fun scope(id: String, generation: Long, baseUrl: String = "http://$id.test") =
        ConnectionScope(ConnectionSnapshot(profile(id, baseUrl), ConnectionSecrets()), generation)

    @Test
    fun `observer starts at the flow value and fires only on key change`() = runTest {
        val flow = MutableStateFlow<ConnectionScope?>(scope("a", 1))
        val seen = mutableListOf<ConnectionScope?>()
        val observer = ConnectionScopeObserver(flow, CoroutineScope(Dispatchers.Unconfined)) { seen += it }

        assertEquals(scope("a", 1), observer.current)
        assertTrue(observer.isCurrent(scope("a", 1)))

        // Same key re-emitted: no callback.
        flow.value = scope("a", 1)
        assertEquals(emptyList<ConnectionScope?>(), seen)

        // Different scope: callback.
        flow.value = scope("b", 1)
        assertEquals(listOf(scope("b", 1)), seen)
        assertTrue(observer.isCurrent(scope("b", 1)))
        assertFalse(observer.isCurrent(scope("a", 1)))
        observer.cancel()
    }

    @Test
    fun `generation bump for same connection id is a scope change`() = runTest {
        val flow = MutableStateFlow<ConnectionScope?>(scope("a", 1))
        val seen = mutableListOf<ConnectionScope?>()
        val observer = ConnectionScopeObserver(flow, CoroutineScope(Dispatchers.Unconfined)) { seen += it }

        // Credential/URL edit keeps connectionId but bumps generation -> new scope.
        flow.value = scope("a", 2)
        assertEquals(listOf(scope("a", 2)), seen)
        assertFalse(observer.isCurrent(scope("a", 1)))
        assertTrue(observer.isCurrent(scope("a", 2)))
        observer.cancel()
    }

    @Test
    fun `URL edit on same id is a scope change`() = runTest {
        val flow = MutableStateFlow<ConnectionScope?>(scope("a", 1))
        val seen = mutableListOf<ConnectionScope?>()
        val observer = ConnectionScopeObserver(flow, CoroutineScope(Dispatchers.Unconfined)) { seen += it }

        flow.value = scope("a", 2, baseUrl = "http://edited.test")
        assertEquals(listOf(scope("a", 2, baseUrl = "http://edited.test")), seen)
        assertFalse(observer.isCurrent(scope("a", 1)))
        observer.cancel()
    }

    @Test
    fun `cancel stops further callbacks`() = runTest {
        val flow = MutableStateFlow<ConnectionScope?>(scope("a", 1))
        val seen = mutableListOf<ConnectionScope?>()
        val observer = ConnectionScopeObserver(flow, CoroutineScope(Dispatchers.Unconfined)) { seen += it }

        observer.cancel()
        flow.value = scope("b", 1)
        assertEquals(emptyList<ConnectionScope?>(), seen)
        // isCurrent reads the live flow even after cancel (reads are always safe).
        assertTrue(observer.isCurrent(scope("b", 1)))
    }

    @Test
    fun `null flow keeps legacy seam permissive`() = runTest {
        val observer = ConnectionScopeObserver(null, CoroutineScope(Dispatchers.Unconfined)) { }
        assertNull(observer.current)
        assertTrue(observer.isCurrent(scope("a", 1)))
        assertTrue(observer.isCurrent(null))
        observer.cancel()
    }
}
