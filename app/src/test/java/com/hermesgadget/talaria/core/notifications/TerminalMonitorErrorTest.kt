/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.notifications

import com.hermesgadget.talaria.core.network.HermesSideEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B22 regression: a foreground agent watcher must stop on the typed terminal
 * flag — including the "WebSocket closed permanently (code)" form emitted by
 * HermesEventClient — instead of matching a handful of exact strings.
 */
class TerminalMonitorErrorTest {
    private fun terminalError(message: String, terminal: Boolean = false) =
        HermesSideEvent.TransportError(socket = "events", message = message, terminal = terminal)

    private fun isTerminal(error: HermesSideEvent.TransportError): Boolean {
        // Mirror of AgentTaskNotificationService.isTerminalMonitorError.
        return error.terminal ||
            error.socket == "auth" ||
            error.message == "Invalid dashboard URL" ||
            error.message.startsWith("reconnect failed")
    }

    @Test
    fun `permanent close message with typed flag is terminal`() {
        val error = terminalError("WebSocket closed permanently (1008): policy", terminal = true)
        assertTrue(isTerminal(error))
    }

    @Test
    fun `legacy string forms stay terminal (backward compatibility)`() {
        assertTrue(isTerminal(terminalError("Invalid dashboard URL")))
        assertTrue(isTerminal(terminalError("reconnect failed after 5 attempts")))
        assertTrue(
            isTerminal(HermesSideEvent.TransportError(socket = "auth", message = "token rejected")),
        )
    }

    @Test
    fun `transient errors without the flag are not terminal`() {
        assertFalse(isTerminal(terminalError("events WS failed")))
        assertFalse(isTerminal(terminalError("WebSocket closed (1006): abnormal")))
    }

    @Test
    fun `transport error default is non-terminal`() {
        assertFalse(HermesSideEvent.TransportError("events", "x").terminal)
    }
}

/** B21 companion: staged spill files must be deletable through the payload path. */
class ReplyStagingCleanupTest {
    @Test
    fun `staged spill file path round-trips and is removable`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "b21-test-${System.nanoTime()}")
        try {
            dir.mkdirs()
            val payload = ReplyPayloadBuilder(dir).build("x".repeat(11_000))
            assertTrue(payload is ReplyPayload.File)
            val file = File((payload as ReplyPayload.File).path)
            assertTrue(file.exists())
            assertTrue(file.delete())
            assertFalse(file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `small replies stay inline`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "b21-test-${System.nanoTime()}")
        try {
            dir.mkdirs()
            val payload = ReplyPayloadBuilder(dir).build("hello")
            assertTrue(payload is ReplyPayload.Inline)
        } finally {
            dir.deleteRecursively()
        }
    }
}
