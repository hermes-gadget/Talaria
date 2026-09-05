/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.feature.manage.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B49: a committed config save must be verified against the readback. A
 * semantically-rejected PUT (server keeps old values) no longer reports
 * success, so the user's draft is never silently discarded.
 */
class ConfigSaveCoordinatorB49Test {

    private suspend fun run(
        accepted: Boolean,
        text: String,
    ): ConfigSaveResult = ConfigSaveCoordinator(
        putConfig = { Result.success(Unit) }, // HTTP 200 either way
        getConfig = {
            Result.success(
                buildJsonObject {
                    // Readback reflects what the server ACTUALLY stored.
                    put("mcpTimeout", JsonPrimitive(if (accepted) 30 else 10))
                    put("other", JsonPrimitive("x"))
                },
            )
        },
    ).save(ConfigSaveRequest(text = text, draftGeneration = 1L))

    @Test
    fun `server echo matching request commits`() = runTest {
        val result = run(accepted = true, text = """{"mcpTimeout":30,"other":"x"}""")
        assertTrue(result is ConfigSaveResult.Committed)
    }

    @Test
    fun `semantically-rejected write fails instead of faking success`() = runTest {
        val result = run(accepted = false, text = """{"mcpTimeout":30,"other":"x"}""")
        assertTrue(result is ConfigSaveResult.Failed)
        assertTrue((result as ConfigSaveResult.Failed).message.contains("did not persist"))
    }

    @Test
    fun `missing key in readback counts as uncommitted`() = runTest {
        var readback: JsonObject = buildJsonObject { put("other", JsonPrimitive("x")) }
        val coordinator = ConfigSaveCoordinator(
            putConfig = { Result.success(Unit) },
            getConfig = { Result.success(readback) },
        )
        val result = coordinator.save(ConfigSaveRequest(text = """{"mcpTimeout":30,"other":"x"}""", draftGeneration = 1L))
        assertTrue(result is ConfigSaveResult.Failed)
    }
}
