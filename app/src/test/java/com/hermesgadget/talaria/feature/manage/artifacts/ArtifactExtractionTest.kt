/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.manage.artifacts

import com.hermesgadget.talaria.domain.model.SessionMessage
import com.hermesgadget.talaria.domain.model.SessionSummary
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactExtractionTest {

    @Test
    fun `extracts markdown paths and deduplicates repeated tool output`() {
        val session = session()
        val messages = listOf(
            SessionMessage(
                role = "assistant",
                content = "![preview](/tmp/render.png) and [notes](./notes.md) /tmp/render.png",
                timestamp = "100",
            ),
            SessionMessage(
                role = "tool",
                content = "rendered /tmp/render.png",
                timestamp = "200",
            ),
        )

        val artifacts = extractArtifacts(session, messages)

        assertEquals(2, artifacts.size)
        assertEquals("/tmp/render.png", artifacts[0].path)
        assertEquals(ArtifactKind.IMAGE, artifacts[0].kind)
        assertEquals("100", artifacts[0].timestamp)
        assertEquals("notes.md", artifacts[1].label)
        assertEquals(ArtifactKind.TEXT, artifacts[1].kind)
    }

    @Test
    fun `extracts nested tool payload paths and classifies archives`() {
        val calls = buildJsonObject {
            put("path", "report.txt")
            put(
                "result",
                buildJsonObject {
                    put("output_file", "/work/exports/bundle.tar.gz")
                    put("thumbnail", "/work/exports/thumbnail.webp")
                },
            )
        }

        val artifacts = extractArtifacts(
            session(),
            listOf(
                SessionMessage(role = "assistant", content = "done", tool_calls = calls),
                SessionMessage(role = "tool", content = "{\"download\":\"/work/report.json\"}"),
            ),
        )

        assertEquals(
            listOf("report.txt", "/work/exports/bundle.tar.gz", "/work/exports/thumbnail.webp", "/work/report.json"),
            artifacts.map { it.path },
        )
        assertEquals(ArtifactKind.ARCHIVE, artifacts[1].kind)
        assertEquals(ArtifactKind.IMAGE, artifacts[2].kind)
        assertEquals(ArtifactKind.TEXT, artifacts[3].kind)
    }

    @Test
    fun `ignores user messages and unsupported extensions`() {
        val artifacts = extractArtifacts(
            session(),
            listOf(
                SessionMessage(role = "user", content = "please inspect /tmp/private.png"),
                SessionMessage(role = "assistant", content = "logs at /tmp/run.log and unknown /tmp/blob.exe"),
            ),
        )

        assertEquals(listOf("/tmp/run.log"), artifacts.map { it.path })
        assertNull(artifactKindForPath("/tmp/blob.exe"))
        assertFalse(filterArtifacts(artifacts, ArtifactKind.IMAGE).isNotEmpty())
    }

    @Test
    fun `filters by artifact kind and uses session title fallback`() {
        val session = SessionSummary(id = "session-42", preview = "Build preview")
        val artifacts = extractArtifacts(
            session,
            listOf(SessionMessage(role = "assistant", content = "/tmp/a.png /tmp/b.zip /tmp/c.txt")),
        )

        assertEquals("Build preview", artifacts.first().sessionTitle)
        assertEquals(1, filterArtifacts(artifacts, ArtifactKind.IMAGE).size)
        assertEquals(1, filterArtifacts(artifacts, ArtifactKind.ARCHIVE).size)
        assertEquals(3, filterArtifacts(artifacts, null).size)
        assertTrue(artifacts.all { it.sessionId == "session-42" })
    }

    @Test
    fun `stringified object and array alternation is depth bounded`() {
        var nested = "/tmp/deep.txt"
        repeat(MAX_ARTIFACT_STRUCTURAL_DEPTH + 2) { index ->
            nested = if (index % 2 == 0) {
                buildJsonObject { put("payload", nested) }.toString()
            } else {
                "[$nested]"
            }
        }

        val artifacts = extractArtifacts(
            session(),
            listOf(
                SessionMessage(
                    role = "assistant",
                    tool_calls = buildJsonObject { put("result", nested) },
                ),
            ),
        )

        assertTrue(artifacts.size <= 1)
    }

    @Test
    fun `candidate and JSON node overflow stays bounded`() {
        val payload = JsonObject(
            (0..(MAX_ARTIFACT_CANDIDATES + 64)).associate { index ->
                "output_$index" to JsonPrimitive("/tmp/output-$index.txt")
            },
        )

        val artifacts = extractArtifacts(
            session(),
            listOf(SessionMessage(role = "assistant", tool_calls = payload)),
        )

        assertTrue(artifacts.size <= MAX_ARTIFACT_CANDIDATES)
    }

    @Test
    fun `transcript byte budget drops content after the bounded prefix`() {
        val text = "/tmp/first.txt " + "x".repeat(MAX_ARTIFACT_INPUT_BYTES.toInt()) + " /tmp/late.txt"

        val artifacts = extractArtifacts(
            session(),
            listOf(SessionMessage(role = "assistant", content = text)),
        )

        assertEquals(listOf("/tmp/first.txt"), artifacts.map { it.path })
    }

    private fun session() = SessionSummary(
        id = "session-1",
        title = "Artifact build",
        source = "cli",
    )
}
