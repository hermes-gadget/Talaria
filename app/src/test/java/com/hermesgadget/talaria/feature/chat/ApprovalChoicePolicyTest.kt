/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** N0.2 — exact, informed approval choices (fail-closed). */
class ApprovalChoicePolicyTest {

    @Test
    fun `broad-first choices are never auto-selected by a generic approve`() {
        // Server lists "always" before "once" — the old bug granted "always".
        val choices = listOf("always", "once", "deny")
        // Generic approve (no explicit tap) must resolve to the safe one-shot.
        assertEquals("once", ApprovalChoicePolicy.resolveChoice(choices, "once"))
        // An explicit tap on a broad choice still resolves to that exact value.
        assertEquals("always", ApprovalChoicePolicy.resolveChoice(choices, "always"))
    }

    @Test
    fun `explicit selection is returned exactly as offered`() {
        assertEquals("once", ApprovalChoicePolicy.resolveChoice(listOf("once", "deny"), "once"))
        assertEquals("always", ApprovalChoicePolicy.resolveChoice(listOf("always"), "always"))
        // Case/whitespace tolerance on the tap, exact value semantics preserved.
        assertEquals("always", ApprovalChoicePolicy.resolveChoice(listOf("ALWAYS"), " Always "))
    }

    @Test
    fun `unknown or unoffered choices fail closed`() {
        assertNull(ApprovalChoicePolicy.resolveChoice(listOf("once"), "always"))
        assertNull(ApprovalChoicePolicy.resolveChoice(listOf("always"), "once"))
        assertNull(ApprovalChoicePolicy.resolveChoice(emptyList(), "always"))
        assertNull(ApprovalChoicePolicy.resolveChoice(listOf("once"), "once,always"))
        assertNull(ApprovalChoicePolicy.resolveChoice(listOf("once"), ""))
        assertNull(ApprovalChoicePolicy.resolveChoice(listOf("once"), null))
    }

    @Test
    fun `deny passes through`() {
        assertEquals("deny", ApprovalChoicePolicy.resolveChoice(listOf("once", "always", "deny"), "deny"))
        assertEquals("deny", ApprovalChoicePolicy.resolveChoice(emptyList(), "DENY"))
    }

    @Test
    fun `no server choices defaults only to the safe one-shot`() {
        assertEquals("once", ApprovalChoicePolicy.resolveChoice(emptyList(), "once"))
        assertNull(ApprovalChoicePolicy.resolveChoice(emptyList(), "always"))
    }

    @Test
    fun `broad confirmation is required for anything beyond one-shot`() {
        assertTrue(ApprovalChoicePolicy.requiresExplicitBroadConfirm("always"))
        assertTrue(ApprovalChoicePolicy.requiresExplicitBroadConfirm("Always"))
        assertTrue(ApprovalChoicePolicy.requiresExplicitBroadConfirm("session"))
        assertTrue(ApprovalChoicePolicy.requiresExplicitBroadConfirm("unknown"))
        assertTrue(!ApprovalChoicePolicy.requiresExplicitBroadConfirm("once"))
        assertTrue(!ApprovalChoicePolicy.requiresExplicitBroadConfirm("Once"))
        assertTrue(!ApprovalChoicePolicy.requiresExplicitBroadConfirm("deny"))
    }
}
