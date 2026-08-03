/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.feature.chat

import com.hermesgadget.talaria.domain.model.MultiProfileSessionMerger
import com.hermesgadget.talaria.domain.model.SessionSummary
import com.hermesgadget.talaria.feature.manage.sessions.SessionFilters
import com.hermesgadget.talaria.feature.manage.sessions.SessionTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the public session projections used by the auto-open rail.
 *
 * ChatViewModel.syncActiveSessions is private and constructs its transport from
 * the process singleton, so its claim/creation race is not directly injectable
 * without changing a production interface. These tests still lock down the
 * ownership invariants available through current public APIs: only live,
 * interactive sessions are candidates, and equal server ids remain distinct
 * when they belong to different management profiles.
 */
class SessionAutoOpenOwnershipBehaviorTest {
    @Test
    fun `live interactive sessions are distinct from ended and automation sessions`() {
        val sessions = listOf(
            SessionSummary(id = "live-chat", source = "discord"),
            SessionSummary(id = "ended-chat", source = "cli", end_reason = "agent_close"),
            SessionSummary(id = "cron-chat", source = "cron"),
            SessionSummary(id = "webhook-chat", source = "webhook"),
        )

        val candidates = sessions.filter { session ->
            session.end_reason == null &&
                session.ended_at == null &&
                SessionFilters.matchesTab(session.source, SessionTab.Chats)
        }

        assertEquals(listOf("live-chat"), candidates.map { it.id })
    }

    @Test
    fun `same session id in two profiles retains two ownership keys`() {
        val merged = MultiProfileSessionMerger.merge(
            mapOf(
                "default" to listOf(SessionSummary(id = "shared-id", source = "cli")),
                "research" to listOf(SessionSummary(id = "shared-id", source = "cli")),
            ),
        )

        assertEquals(
            setOf("default\u0000shared-id", "research\u0000shared-id"),
            merged.map { it.key }.toSet(),
        )
        assertTrue(merged.all { it.session.id == "shared-id" })
    }
}
