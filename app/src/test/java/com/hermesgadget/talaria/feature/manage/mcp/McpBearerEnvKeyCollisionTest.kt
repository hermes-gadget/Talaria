/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.feature.manage.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * S07 regression: punctuation-normalized server names must not fold onto the
 * same environment key and overwrite each other's credentials.
 */
class McpBearerEnvKeyCollisionTest {
    @Test
    fun `distinct names keep distinct keys when occupied set is passed`() {
        val first = mcpBearerEnvKey("a-b")
        val second = mcpBearerEnvKey("a_b", occupiedKeys = setOf(first))
        assertEquals("MCP_A_B_API_KEY", first)
        assertEquals("MCP_A_B_2_API_KEY", second)
        assertNotEquals(first, second)
    }

    @Test
    fun `unoccupied name keeps the stable base key`() {
        assertEquals("MCP_ML_KIT_API_KEY", mcpBearerEnvKey("ml.kit", occupiedKeys = setOf("MCP_OTHER_API_KEY")))
    }

    @Test
    fun `re-saving the same server keeps its own key stable`() {
        val own = "MCP_A_B_API_KEY"
        // The edit flow excludes the target server's own previous key.
        assertEquals(own, mcpBearerEnvKey("a-b", occupiedKeys = emptySet()))
    }

    @Test
    fun `three-way collisions escalate numerically`() {
        val occupied = setOf("MCP_X_API_KEY", "MCP_X_2_API_KEY", "MCP_X_3_API_KEY")
        assertEquals("MCP_X_4_API_KEY", mcpBearerEnvKey("x", occupied))
    }

    @Test
    fun `legacy single-arg overload unchanged`() {
        assertEquals("MCP_A_B_API_KEY", mcpBearerEnvKey("a-b"))
    }
}
