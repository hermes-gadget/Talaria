/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.network

import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.PtyBackoffPolicy
import com.hermesgadget.talaria.core.network.PtyEvent
import com.hermesgadget.talaria.core.network.PtyGenerationGate
import com.hermesgadget.talaria.core.network.PtyTransportFactory
import com.hermesgadget.talaria.core.network.PtyTransportState
import com.hermesgadget.talaria.core.network.PtyTransportSupervisor
import com.hermesgadget.talaria.core.network.PtyWebSocketTransportConnection
import com.hermesgadget.talaria.core.network.PtyWebSocketSession
import com.hermesgadget.talaria.core.network.WebSocketFrameBudget
import com.hermesgadget.talaria.core.network.WsAuthHelper
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** MockWebServer coverage for the current PTY event/send contract. */
class PtyDeliveryBehaviorTest {
    private lateinit var server: MockWebServer
    private lateinit var snapshot: ConnectionSnapshot
    private lateinit var wsAuth: WsAuthHelper

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        wsAuth = mockk()
        snapshot = ConnectionSnapshot(
            profile = ConnectionProfile(
                id = "connection-1",
                name = "Dashboard",
                baseUrl = server.url("/").toString(),
                authMode = AuthMode.NONE,
                managementProfile = "research",
            ),
            secrets = ConnectionSecrets(),
        )
        coEvery { wsAuth.authQueryParam(snapshot) } returns ""
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `PTY reports connected before output and sends body and enter as separate frames`() = runBlocking {
        val frames = Channel<String>(Channel.UNLIMITED)
        val serverSocket = CompletableDeferred<WebSocket>()
        val serverClosed = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.complete(webSocket)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    serverClosed.complete(Unit)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Complete the close handshake from the server side —
                    // MockWebServer does not auto-reply to close frames.
                    webSocket.close(code, reason)
                    serverClosed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    serverClosed.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.trySend(text)
                }
            }),
        )

        val events = Channel<PtyEvent>(Channel.UNLIMITED)
        val session = PtyWebSocketSession(OkHttpClient(), wsAuth, snapshot)
        val collector = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            session.connect(
                resumeSessionId = "session-1",
                channelId = "channel-1",
                attachToken = "attach-1",
            ).collect { events.send(it) }
        }

        val connected = withTimeout(5_000) { events.receive() }
        assertTrue(connected is PtyEvent.Connected)
        assertNull(frames.tryReceive().getOrNull())

        serverSocket.await().send("TUI ready")
        val output = withTimeout(5_000) { events.receive() }
        assertEquals("TUI ready", (output as PtyEvent.Output).text)

        session.sendText("hello\r\n")
        assertEquals("hello", withTimeout(5_000) { frames.receive() })
        assertEquals("\r", withTimeout(5_000) { frames.receive() })

        val request = withTimeout(5_000) { server.takeRequest() }
        assertEquals("research", request.requestUrl!!.queryParameter("profile"))
        assertEquals("session-1", request.requestUrl!!.queryParameter("resume"))
        assertEquals("attach-1", request.requestUrl!!.queryParameter("attach"))

        session.close()
        session.sendText("late")
        assertNull(frames.tryReceive().getOrNull())

        // Wait for the close handshake to complete server-side before the
        // mock server shuts down, otherwise its queue never drains.
        withTimeout(5_000) { serverClosed.await() }
        collector.cancelAndJoin()
    }

    @Test
    fun `oversized text PTY frame is rejected before output conversion and closes with 1009`() = runBlocking {
        assertOversizedFrame(binary = false)
    }

    @Test
    fun `oversized binary PTY frame is rejected before UTF-8 conversion and closes with 1009`() = runBlocking {
        assertOversizedFrame(binary = true)
    }

    @Test
    fun `PTY retries a failed ticket mint without opening an unauthenticated socket`() = runBlocking {
        val attempts = AtomicInteger(0)
        coEvery { wsAuth.authQueryParam(snapshot) } coAnswers {
            if (attempts.incrementAndGet() == 1) {
                throw IOException("temporary ticket outage")
            }
            "ticket=pty-retry"
        }
        val serverOpened = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverOpened.complete(Unit)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }),
        )

        val transportScope = CoroutineScope(Dispatchers.Default)
        val supervisor = PtyTransportSupervisor(
            scope = transportScope,
            factory = PtyTransportFactory { generation, gate: PtyGenerationGate ->
                val session = PtyWebSocketSession(
                    client = OkHttpClient(),
                    wsAuth = wsAuth,
                    snapshot = snapshot,
                    generationId = generation,
                    generationGate = gate,
                )
                PtyWebSocketTransportConnection(
                    session = session,
                    events = session.connect(channelId = "channel-ticket-retry"),
                )
            },
            backoff = PtyBackoffPolicy(
                maxAttempts = 2,
                baseDelayMs = 25L,
                maxDelayMs = 25L,
                jitterRatio = 0.0,
            ),
        )

        try {
            supervisor.start()
            withTimeout(5_000) {
                while (supervisor.state.value !is PtyTransportState.Connected) delay(5L)
            }
            serverOpened.await()
            assertEquals(2, attempts.get())
            val request = withTimeout(5_000) {
                server.takeRequest(5, TimeUnit.SECONDS)
                    ?: error("PTY WebSocket request did not arrive")
            }
            assertEquals("pty-retry", request.requestUrl?.queryParameter("ticket"))
        } finally {
            supervisor.stop()
            transportScope.cancel()
        }
    }

    private suspend fun assertOversizedFrame(binary: Boolean) {
        val serverSocket = CompletableDeferred<WebSocket>()
        val closeCode = CompletableDeferred<Int>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.complete(webSocket)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closeCode.complete(code)
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closeCode.complete(code)
                }
            }),
        )

        val events = Channel<PtyEvent>(Channel.UNLIMITED)
        val session = PtyWebSocketSession(OkHttpClient(), wsAuth, snapshot)
        val collector = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            session.connect(
                resumeSessionId = "session-oversized",
                channelId = "channel-oversized",
                attachToken = "attach-oversized",
            ).collect { events.send(it) }
        }

        assertTrue(withTimeout(5_000) { events.receive() } is PtyEvent.Connected)
        if (binary) {
            serverSocket.await().send(
                ByteString.of(*ByteArray(WebSocketFrameBudget.MAX_FRAME_BYTES + 1)),
            )
        } else {
            serverSocket.await().send("x".repeat(WebSocketFrameBudget.MAX_FRAME_BYTES + 1))
        }
        val failure = withTimeout(5_000) {
            var receivedFailure: PtyEvent.Failure? = null
            while (receivedFailure == null) {
                when (val event = events.receive()) {
                    is PtyEvent.Failure -> receivedFailure = event
                    else -> Unit
                }
            }
            checkNotNull(receivedFailure)
        }

        assertEquals(WebSocketFrameBudget.MESSAGE_TOO_BIG_CLOSE_CODE, failure.code)
        assertEquals(
            WebSocketFrameBudget.MESSAGE_TOO_BIG_CLOSE_CODE,
            withTimeout(5_000) { closeCode.await() },
        )
        // The callback budget check must run before ANSI/UTF-8 conversion or
        // any bounded output event is emitted. The server's complete message
        // is intentionally larger than the client budget; OkHttp 4.12 may
        // still have materialized it before this callback (see the explicit
        // residual-risk note on WebSocketFrameBudget).
        assertNull(events.tryReceive().getOrNull())
        collector.cancelAndJoin()
    }
}
