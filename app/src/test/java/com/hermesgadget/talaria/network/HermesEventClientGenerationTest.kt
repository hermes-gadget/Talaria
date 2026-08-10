/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.network

import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.HermesEventClient
import com.hermesgadget.talaria.core.network.HermesEventEnvelope
import com.hermesgadget.talaria.core.network.HermesEventScope
import com.hermesgadget.talaria.core.network.HermesSideEvent
import com.hermesgadget.talaria.core.network.HermesClientFactory
import com.hermesgadget.talaria.core.network.WsAuthHelper
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Fake-gated WebSocket coverage for N0.5 auth and ingress ownership. */
class HermesEventClientGenerationTest {
    private lateinit var server: MockWebServer
    private lateinit var snapshot: ConnectionSnapshot
    private lateinit var wsAuth: WsAuthHelper
    private lateinit var clientFactory: HermesClientFactory
    private var client: HermesEventClient? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        snapshot = ConnectionSnapshot(
            profile = ConnectionProfile(
                id = "connection-n05",
                name = "N0.5 fake dashboard",
                baseUrl = server.url("/").toString(),
                authMode = AuthMode.BASIC,
                managementProfile = "research",
            ),
            secrets = ConnectionSecrets(),
        )
        wsAuth = mockk()
        clientFactory = mockk()
    }

    @After
    fun tearDown() {
        client?.dispose()
        server.shutdown()
    }

    @Test
    fun gatedInitialSocketsMintIndependentTicketsImmediatelyBeforeOpening() = runBlocking {
        val issued = mutableListOf<String>()
        coEvery { wsAuth.authQueryParam(snapshot) } coAnswers {
            "ticket=ticket-${issued.size + 1}".also(issued::add)
        }
        val eventsSocket = enqueueSocket()
        val rpcSocket = enqueueSocket()
        val eventClient = newClient()

        eventClient.start(channel = "channel-a", includeRpc = true)
        awaitOpened(eventsSocket)
        awaitOpened(rpcSocket)

        val requests = listOf(awaitRequest(), awaitRequest())
        assertEquals(2, issued.size)
        assertEquals(
            setOf("api/events", "api/ws"),
            requests.mapNotNull { it.requestUrl?.encodedPath?.trimStart('/') }.toSet(),
        )
        assertEquals(
            setOf("ticket-1", "ticket-2"),
            requests.mapNotNull { it.requestUrl?.queryParameter("ticket") }.toSet(),
        )
        assertEquals("channel-a", requests.firstNotNullOf { it.requestUrl?.queryParameter("channel") })
        assertEquals("research", requests.firstNotNullOf { it.requestUrl?.queryParameter("profile") })
    }

    @Test
    fun initialTicketFailuresRetryForBothEventsAndRpcWithoutOpeningUnauthenticatedSockets() = runBlocking {
        val attempts = AtomicInteger(0)
        coEvery { wsAuth.authQueryParam(snapshot) } coAnswers {
            val attempt = attempts.incrementAndGet()
            if (attempt <= 2) throw IOException("temporary ticket outage")
            "ticket=ticket-retry-$attempt"
        }
        val eventsSocket = enqueueSocket()
        val rpcSocket = enqueueSocket()
        val eventClient = newClient(reconnectBackoff = longArrayOf(25L))

        eventClient.start(channel = "channel-auth-retry", includeRpc = true)
        awaitOpened(eventsSocket)
        awaitOpened(rpcSocket)

        val requests = listOf(awaitRequest(), awaitRequest())
        assertEquals(4, attempts.get())
        assertEquals(
            setOf("api/events", "api/ws"),
            requests.mapNotNull { it.requestUrl?.encodedPath?.trimStart('/') }.toSet(),
        )
        assertTrue(requests.all { it.requestUrl?.queryParameter("ticket")?.contains("retry-") == true })
    }

    @Test
    fun reconnectReplacesSocketAndMintsAFreshTicket() = runBlocking {
        val issued = AtomicInteger(0)
        coEvery { wsAuth.authQueryParam(snapshot) } coAnswers {
            "ticket=ticket-${issued.incrementAndGet()}"
        }
        val first = enqueueSocket(closeOnOpen = true)
        val second = enqueueSocket()
        val eventClient = newClient(reconnectBackoff = longArrayOf(25L))

        eventClient.start(channel = "channel-reconnect", includeRpc = false)
        awaitOpened(first)
        val initialRequest = awaitRequest()
        assertEquals("ticket-1", initialRequest.requestUrl?.queryParameter("ticket"))

        awaitOpened(second)
        val reconnectRequest = awaitRequest()

        assertEquals("api/events", reconnectRequest.requestUrl?.encodedPath?.trimStart('/'))
        assertEquals("ticket-2", reconnectRequest.requestUrl?.queryParameter("ticket"))
        assertEquals(2, issued.get())
    }

    @Test
    fun loopbackTokenReuseCannotCrossGenerations() = runBlocking {
        // Loopback auth deliberately returns the same reusable process token for
        // A and B. Generation ownership, rather than token uniqueness, must stop
        // an old socket/replay item from entering the new scope.
        coEvery { wsAuth.authQueryParam(snapshot) } returns "token=local"
        val oldSocket = enqueueSocket()
        val newSocket = enqueueSocket()
        val eventClient = newClient(reconnectBackoff = longArrayOf(25L))
        val received = Channel<HermesEventEnvelope>(Channel.UNLIMITED)
        val collector: Job = launchCollector(eventClient, received)

        eventClient.start(channel = "channel-a", includeRpc = false)
        awaitOpened(oldSocket)
        val firstRequest = awaitRequest()
        assertEquals("local", firstRequest.requestUrl?.queryParameter("token"))
        oldSocket.socket.await().send(
            """{"type":"message.delta","session_id":"session-a","text":"A"}""",
        )
        val first = withTimeout(5_000) { received.receive() }
        assertEquals("channel-a", first.scope.channelId)
        assertEquals("tab-n05", first.scope.tabId)
        assertEquals("session-n05", first.scope.sessionId)
        assertEquals("A", (first.event as HermesSideEvent.MessageDelta).text)
        assertTrue(first.generation > 0)
        assertNotNull(first.socketIdentity)

        eventClient.stop()
        oldSocket.socket.await().send(
            """{"type":"message.delta","session_id":"session-a","text":"after-stop"}""",
        )
        assertNull(received.tryReceive().getOrNull())

        eventClient.start(channel = "channel-b", includeRpc = false)
        awaitOpened(newSocket)
        val secondRequest = awaitRequest()
        assertEquals("local", secondRequest.requestUrl?.queryParameter("token"))

        // This is a late frame from A. It may be rejected by the close handshake
        // itself; either way it must never appear in B's scoped stream.
        oldSocket.socket.await().send(
            """{"type":"message.delta","session_id":"session-a","text":"late-A"}""",
        )
        newSocket.socket.await().send(
            """{"type":"message.delta","session_id":"session-b","text":"B"}""",
        )
        val second = withTimeout(5_000) { received.receive() }
        assertEquals("channel-b", second.scope.channelId)
        assertEquals("B", (second.event as HermesSideEvent.MessageDelta).text)
        assertTrue(second.generation != first.generation)
        assertTrue(second.socketIdentity != first.socketIdentity)
        assertNull(received.tryReceive().getOrNull())

        collector.cancelAndJoin()
    }

    private fun newClient(reconnectBackoff: LongArray = longArrayOf(1_000L)) =
        HermesEventClient(
            clientFactory = clientFactory,
            wsAuth = wsAuth,
            fixedSnapshot = snapshot,
            fixedEventScope = HermesEventScope(
                connectionId = snapshot.connectionId,
                managementProfile = snapshot.managementProfile,
                channelId = "not-used-before-start",
                tabId = "tab-n05",
                sessionId = "session-n05",
            ),
            fixedWebSocketClient = OkHttpClient(),
            reconnectBackoff = reconnectBackoff,
        ).also { client = it }

    private fun launchCollector(
        eventClient: HermesEventClient,
        received: Channel<HermesEventEnvelope>,
    ): Job = CoroutineScope(Dispatchers.Default).launch {
        eventClient.scopedEvents.collect { received.send(it) }
    }

    private fun enqueueSocket(
        replyToClose: Boolean = true,
        closeOnOpen: Boolean = false,
    ): FakeSocket {
        val fake = FakeSocket()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    fake.opened.complete(Unit)
                    fake.socket.complete(webSocket)
                    if (closeOnOpen) webSocket.close(1001, "fake transport drop")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (replyToClose) webSocket.close(code, reason)
                }
            }),
        )
        return fake
    }

    private suspend fun awaitRequest(): RecordedRequest =
        withTimeout(5_000) {
            server.takeRequest(5, TimeUnit.SECONDS) ?: error("WebSocket request did not arrive")
        }

    private suspend fun awaitOpened(fakeSocket: FakeSocket) {
        withTimeout(5_000) { fakeSocket.opened.await() }
    }

    private class FakeSocket {
        val opened = kotlinx.coroutines.CompletableDeferred<Unit>()
        val socket = kotlinx.coroutines.CompletableDeferred<WebSocket>()
    }
}
