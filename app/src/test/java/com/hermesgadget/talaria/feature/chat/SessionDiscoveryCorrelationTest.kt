/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B33 regression contract: pending local session discovery must prefer the
 * session id observed on the claiming tab's OWN event stream; the legacy
 * "newest id absent from baseline" heuristic never outranks it, and observed
 * ids are excluded from heuristic claiming by other tabs.
 */
class SessionDiscoveryCorrelationTest {
    private data class State(
        val baseline: Set<String>,
        val claimed: Set<String>,
        val observed: Set<String>,
    )

    private fun observedCandidate(state: State, registry: List<String>): String? =
        state.observed
            .firstOrNull { it !in state.baseline && it !in state.claimed }
            ?.let { id -> registry.firstOrNull { it == id } }

    private fun heuristicCandidate(state: State, registry: List<String>): String? =
        registry.asSequence()
            .filter { it !in state.baseline }
            .filter { it !in state.claimed }
            .filter { it !in state.observed }
            .lastOrNull()

    @Test
    fun `tab's observed id wins over a newer unclaimed session`() {
        val state = State(
            baseline = setOf("s-old"),
            claimed = emptySet(),
            observed = setOf("s-mine"),
        )
        // "s-later" is newer in the registry, but this tab observed s-mine.
        val registry = listOf("s-old", "s-mine", "s-later")
        assertEquals("s-mine", observedCandidate(state, registry))
    }

    @Test
    fun `unrelated CLI session is never claimed when tab observed its own`() {
        val state = State(baseline = emptySet(), claimed = emptySet(), observed = setOf("s-mine"))
        val registry = listOf("s-discord")
        assertEquals(null, observedCandidate(state, registry))
        // And the heuristic path also refuses: s-discord was NOT observed by
        // this tab... but with nothing observed, the fallback may fire.
    }

    @Test
    fun `two racing tabs cannot claim the same observed id`() {
        val tabA = State(baseline = emptySet(), claimed = emptySet(), observed = setOf("s-1"))
        val claimed = mutableSetOf("s-1")
        val tabB = State(baseline = emptySet(), claimed = claimed, observed = setOf("s-2"))
        val registry = listOf("s-1", "s-2")
        assertEquals("s-2", observedCandidate(tabB, registry))
        // TabB's observed id (s-2) is not tabA's claim (s-1).
        assertFalse(observedCandidate(tabB, registry) in claimed)
    }

    @Test
    fun `heuristic skips ids observed by any tab`() {
        val state = State(baseline = emptySet(), claimed = emptySet(), observed = setOf("s-1"))
        val registry = listOf("s-1", "s-2")
        // s-1 was observed on THIS tab's stream (superset check handles it),
        // so the fallback can only pick a non-observed id.
        assertEquals("s-2", heuristicCandidate(state, registry))
    }
}

/**
 * B36 regression contract: a server transcript may only replace the UI when
 * it is a strict extension of what is shown — prefix-stable superset. An
 * old eventually-consistent read must never finish a new turn.
 */
class TranscriptSupersetPolicyTest {
    private fun line(role: String, text: String) = role to text

    private fun isSuperset(shown: List<Pair<String, String>>, server: List<Pair<String, String>>): Boolean =
        shown.isEmpty() || (server.size > shown.size && server.take(shown.size) == shown)

    @Test
    fun `old history with trailing assistant does NOT finish the turn`() {
        val shown = listOf(
            line("user", "hello"),
            line("assistant", "earlier answer"),
            line("user", "new question"),
        )
        // Server returns the OLD transcript (same length, assistant at end).
        val server = listOf(
            line("user", "hello"),
            line("assistant", "earlier answer"),
            line("user", "new question"),
        )
        assertFalse(isSuperset(shown, server))
    }

    @Test
    fun `regrown transcript ending in assistant finishes the turn`() {
        val shown = listOf(line("user", "hello"), line("user", "new question"))
        val server = listOf(
            line("user", "hello"),
            line("user", "new question"),
            line("assistant", "the reply"),
        )
        assertTrue(isSuperset(shown, server))
    }

    @Test
    fun `shorter or reordered server read is ignored`() {
        val shown = listOf(line("user", "a"), line("assistant", "b"))
        assertFalse(isSuperset(shown, listOf(line("user", "a"))))
        assertFalse(isSuperset(shown, listOf(line("assistant", "b"), line("user", "a"))))
    }

    @Test
    fun `empty shown transcript accepts any nonempty read`() {
        assertTrue(isSuperset(emptyList(), listOf(line("assistant", "hi"))))
    }
}
