/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.feature.chat

import com.hermesgadget.talaria.core.network.SidecarSlashCompletion
import com.hermesgadget.talaria.domain.model.SlashArgumentMode
import com.hermesgadget.talaria.domain.model.SlashCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSlashCompleterTest {
    private val completer = ChatSlashCompleter(
        catalog = listOf(
            SlashCommand("/help", "Show commands"),
            SlashCommand("/research", "Search sources", category = "Skills", argumentMode = SlashArgumentMode.MIXED),
        ),
    )

    @Test
    fun `known commands inherit catalog metadata`() {
        val merged = completer.mergeRemote(
            listOf(
                SidecarSlashCompletion(replacement = "/help", description = "", kind = null),
                SidecarSlashCompletion(replacement = "/research", description = "", kind = null),
            ),
        )
        assertEquals("Show commands", merged.first { it.command == "/help" }.description)
        val research = merged.first { it.command == "/research" }
        assertEquals("Skills", research.category)
        assertEquals(SlashArgumentMode.MIXED, research.argumentMode)
    }

    @Test
    fun `unknown commands fall back to kind and space inference`() {
        val merged = completer.mergeRemote(
            listOf(
                SidecarSlashCompletion(replacement = "/custom", description = "From TUI", kind = "skill"),
                SidecarSlashCompletion(replacement = "/verbose ", description = "", kind = null),
            ),
        )
        val custom = merged.first { it.command == "/custom" }
        assertEquals("From TUI", custom.description)
        assertEquals("Skills", custom.category)
        assertEquals(SlashArgumentMode.NONE, custom.argumentMode)
        // Trailing space implies MIXED argument mode.
        assertEquals(SlashArgumentMode.MIXED, merged.first { it.command == "/verbose" }.argumentMode)
    }

    @Test
    fun `duplicates collapse and the list is capped`() {
        val many = (0 until 20).map { SidecarSlashCompletion(replacement = "/dup$it", description = "", kind = null) }
        val dupes = many + SidecarSlashCompletion(replacement = "/dup0", description = "", kind = null)
        val merged = completer.mergeRemote(dupes)
        assertEquals(12, merged.size)
        assertEquals(1, merged.count { it.command == "/dup0" })
    }
}
