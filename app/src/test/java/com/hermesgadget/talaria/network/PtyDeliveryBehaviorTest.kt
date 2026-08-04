/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.network

import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.PtyEvent
import com.hermesgadget.talaria.core.network.PtyWebSocketSession
import com.hermesgadget.talaria.core.network.WsAuthHelper
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
        val collector = launch(Dispatchers.Default) {
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
}
