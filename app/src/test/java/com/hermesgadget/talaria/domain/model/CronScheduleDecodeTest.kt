/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B27: CronJob.schedule must survive dashboard v0.19.1's object shape
 * without the whole decode failing.
 */
class CronScheduleDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy string schedule still decodes`() {
        val job = json.decodeFromString<CronJob>("""{"id":"j1","schedule":"0 9 * * *"}""")
        assertEquals("0 9 * * *", job.schedule)
    }

    @Test
    fun `object schedule renders as readable text`() {
        val payload = buildJsonObject {
            put("id", "j2")
            putJsonObject("schedule") {
                put("method", "cron")
                put("expression", "30 8 * * 1-5")
            }
        }
        val job = json.decodeFromString<CronJob>(payload.toString())
        assertEquals("cron: 30 8 * * 1-5", job.schedule)
    }

    @Test
    fun `object schedule with unknown keys does not crash`() {
        val payload = """{"id":"j3","schedule":{"kind":"interval","at":"hourly","extra":true}}"""
        val job = json.decodeFromString<CronJob>(payload)
        assertEquals(true, job.schedule?.contains("kind=interval"))
    }
}
