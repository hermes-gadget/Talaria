/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.network

import com.hermesgadget.talaria.domain.model.MultiProfileSessionMerger
import com.hermesgadget.talaria.domain.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiProfileSessionMergerTest {
    @Test
    fun `merge orders every profile by numeric recency and keeps profile tags`() {
        val sessions = MultiProfileSessionMerger.merge(
            mapOf(
                "default" to listOf(
                    SessionSummary(id = "default-old", last_active = "100"),
                    SessionSummary(id = "default-new", last_active = "300"),
                ),
                "work" to listOf(
                    SessionSummary(id = "work-new", last_active = "250"),
                    SessionSummary(id = "work-started", started_at = "200"),
                ),
            ),
        )

        assertEquals(
            listOf("default:default-new", "work:work-new", "work:work-started", "default:default-old"),
            sessions.map { "${it.profileName}:${it.session.id}" },
        )
    }

    @Test
    fun `profile filter narrows merged projection without changing ordering`() {
        val sessions = MultiProfileSessionMerger.merge(
            mapOf(
                "default" to listOf(SessionSummary(id = "d", last_active = "20")),
                "work" to listOf(
                    SessionSummary(id = "w-old", last_active = "10"),
                    SessionSummary(id = "w-new", last_active = "30"),
                ),
            ),
            profileFilter = "work",
        )

        assertEquals(listOf("w-new", "w-old"), sessions.map { it.session.id })
        assertEquals(listOf("work", "work"), sessions.map { it.profileName })
    }
}
