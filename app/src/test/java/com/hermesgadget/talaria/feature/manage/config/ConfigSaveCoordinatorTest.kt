/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.manage.config

import com.hermesgadget.talaria.core.network.JsonConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigSaveCoordinatorTest {
    @Test
    fun `rapid saves are written in submission order and newest draft becomes clean`() = runTest {
        val server = FakeConfigServer()
        val coordinator = ConfigSaveCoordinator(
            putConfig = server::putConfig,
            getConfig = server::getConfig,
        )

        var editor = ConfigEditorState().loaded(configText("initial"))
        editor = editor.edit(configText("A"))
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.save(
                ConfigSaveRequest(
                    text = editor.text,
                    draftGeneration = editor.draftGeneration,
                ),
            )
        }
        server.firstPutStarted.await()

        editor = editor.edit(configText("B"))
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.save(
                ConfigSaveRequest(
                    text = editor.text,
                    draftGeneration = editor.draftGeneration,
                ),
            )
        }

        // The second request is queued behind the delayed first PUT, not sent
        // independently where it could overtake the first write.
        assertEquals(listOf(configText("A")), server.writes)

        server.releaseFirstPut.complete(Unit)
        val firstResult = first.await()
        assertEquals(1L, firstResult.generation)
        editor = editor.applySaveResult(firstResult)
        assertEquals(configText("A"), editor.savedText)
        assertEquals(configText("B"), editor.text)
        assertTrue(editor.isDirty)

        val secondResult = second.await()
        assertEquals(2L, secondResult.generation)
        editor = editor.applySaveResult(secondResult)

        assertEquals(listOf(configText("A"), configText("B")), server.writes)
        assertEquals(configText("B"), server.currentText())
        assertEquals(configText("B"), editor.text)
        assertEquals(configText("B"), editor.savedText)
        assertFalse(editor.isDirty)
        assertEquals(2, server.getCalls)
    }

    @Test
    fun `verification failure does not mark the submitted draft clean`() = runTest {
        val server = FakeConfigServer().apply { verificationFailure = IllegalStateException("read failed") }
        val coordinator = ConfigSaveCoordinator(
            putConfig = server::putConfig,
            getConfig = server::getConfig,
        )
        val editor = ConfigEditorState()
            .loaded(configText("initial"))
            .edit(configText("draft"))

        // This fake delays its first PUT for the ordering test; release it so
        // this case reaches the verification read immediately.
        server.releaseFirstPut.complete(Unit)
        val result = coordinator.save(
            ConfigSaveRequest(editor.text, editor.draftGeneration),
        )
        val after = editor.applySaveResult(result)

        assertTrue(after.isDirty)
        assertEquals(configText("initial"), after.savedText)
        assertTrue(after.message?.contains("could not verify") == true)
    }

    private class FakeConfigServer {
        private var current: JsonObject = config("initial")
        private var putCalls = 0
        var verificationFailure: Throwable? = null
        var getCalls = 0
        val writes = mutableListOf<String>()
        val firstPutStarted = CompletableDeferred<Unit>()
        val releaseFirstPut = CompletableDeferred<Unit>()

        suspend fun putConfig(config: JsonObject): Result<Unit> {
            putCalls += 1
            writes += JsonConfig.json.encodeToString(config)
            if (putCalls == 1) {
                firstPutStarted.complete(Unit)
                releaseFirstPut.await()
            }
            current = config
            return Result.success(Unit)
        }

        suspend fun getConfig(): Result<JsonObject> {
            getCalls += 1
            verificationFailure?.let { return Result.failure(it) }
            return Result.success(current)
        }

        fun currentText(): String = JsonConfig.json.encodeToString(current)
    }

    private fun configText(value: String): String = JsonConfig.json.encodeToString(config(value))

    private fun config(value: String): JsonObject =
        JsonConfig.json.parseToJsonElement("{\"value\":\"$value\"}").jsonObject
}
