/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.network

import com.hermesgadget.talaria.core.network.PtyBackoffPolicy
import com.hermesgadget.talaria.core.network.PtyCloseCodeClassifier
import com.hermesgadget.talaria.core.network.PtyCloseDisposition
import com.hermesgadget.talaria.core.network.PtyEvent
import com.hermesgadget.talaria.core.network.PtyGenerationGate
import com.hermesgadget.talaria.core.network.PtyPromptDeliveryLedger
import com.hermesgadget.talaria.core.network.PtyPromptDeliveryStart
import com.hermesgadget.talaria.core.network.PtySendException
import com.hermesgadget.talaria.core.network.PtySendReceipt
import com.hermesgadget.talaria.core.network.PtyTransportConnection
import com.hermesgadget.talaria.core.network.PtyTransportFactory
import com.hermesgadget.talaria.core.network.PtyTransportState
import com.hermesgadget.talaria.core.network.PtyTransportSupervisor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PtyTransportSupervisorTest {
    @Test
    fun backoffDoublesAndCapsWithoutJitter() {
        val policy = PtyBackoffPolicy(
            maxAttempts = 6,
            baseDelayMs = 1_000L,
            maxDelayMs = 5_000L,
            jitterRatio = 0.0,
        )

        assertEquals(1_000L, policy.delayMs(1))
        assertEquals(2_000L, policy.delayMs(2))
        assertEquals(4_000L, policy.delayMs(3))
        assertEquals(5_000L, policy.delayMs(4))
        assertEquals(5_000L, policy.delayMs(6))
    }

    @Test
    fun closeCodesStopImmediatelyButNetworkLossRetries() = runTest {
        assertEquals(PtyCloseDisposition.STOP, PtyCloseCodeClassifier.classify(4401).disposition)
        assertEquals(PtyCloseDisposition.STOP, PtyCloseCodeClassifier.classify(4403).disposition)
        assertEquals(PtyCloseDisposition.STOP, PtyCloseCodeClassifier.classify(4404).disposition)
        assertEquals(PtyCloseDisposition.STOP, PtyCloseCodeClassifier.classify(4408).disposition)
        assertEquals(PtyCloseDisposition.RETRY, PtyCloseCodeClassifier.classify(1006).disposition)
        assertEquals(PtyCloseDisposition.RETRY, PtyCloseCodeClassifier.classify(408).disposition)

        val factory = FakeFactory()
        val supervisor = supervisor(this, factory)
        try {
            supervisor.start()
            runCurrent()
            factory.connections.single().emit(PtyEvent.Closed(4404, "missing"))
            runCurrent()

            val state = supervisor.state.value as PtyTransportState.Exhausted
            assertTrue(state.terminal)
            assertEquals(1, factory.connections.size)
        } finally {
            supervisor.stop()
        }
    }

    @Test
    fun stableConnectionResetsFailureCounterBeforeNextLoss() = runTest {
        val factory = FakeFactory()
        val supervisor = supervisor(this, factory, stabilityWindowMs = 50L)
        try {
            supervisor.start()
            runCurrent()
            factory.connections[0].emit(PtyEvent.Connected("key-1", "channel"))
            runCurrent()
            advanceTimeBy(51L)
            runCurrent()

            factory.connections[0].emit(PtyEvent.Failure("network"))
            runCurrent()
            assertEquals(1, (supervisor.state.value as PtyTransportState.Recovering).attempt)
        } finally {
            supervisor.stop()
        }
    }

    @Test
    fun staleGenerationCannotSendAfterReconnect() = runTest {
        val factory = FakeFactory()
        val supervisor = supervisor(this, factory)
        try {
            supervisor.start()
            runCurrent()
            val first = factory.connections[0]
            first.emit(PtyEvent.Connected("key-1", "channel"))
            runCurrent()
            assertTrue(supervisor.sendTextChecked("first").isSuccess)

            first.emit(PtyEvent.Failure("network"))
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()
            val second = factory.connections[1]
            second.emit(PtyEvent.Connected("key-2", "channel"))
            runCurrent()

            assertFalse(first.sendTextChecked("late").isSuccess)
            assertTrue(second.sendTextChecked("current").isSuccess)
            assertEquals(1, first.sendCount)
            assertEquals(1, second.sendCount)
        } finally {
            supervisor.stop()
        }
    }

    @Test
    fun acceptedPromptIsNotSentAgainWhenTheTransportGenerationChanges() {
        val ledger = PtyPromptDeliveryLedger()
        val deliveryId = "delivery-1"
        var sends = 0

        assertEquals(PtyPromptDeliveryStart.SEND, ledger.begin(deliveryId))
        sends += 1
        ledger.complete(
            deliveryId,
            Result.success(PtySendReceipt(bodyAccepted = true, enterAccepted = true)),
        )

        // A reconnect may create a new socket, but it must not claim this
        // accepted delivery id again or replay its prompt.
        assertEquals(PtyPromptDeliveryStart.ALREADY_ACCEPTED, ledger.begin(deliveryId))
        assertEquals(1, sends)
    }

    private fun supervisor(
        scope: kotlinx.coroutines.CoroutineScope,
        factory: FakeFactory,
        stabilityWindowMs: Long = 5_000L,
    ): PtyTransportSupervisor = PtyTransportSupervisor(
        scope = scope,
        factory = factory,
        backoff = PtyBackoffPolicy(
            maxAttempts = 3,
            baseDelayMs = 1_000L,
            maxDelayMs = 4_000L,
            jitterRatio = 0.0,
        ),
        stabilityWindowMs = stabilityWindowMs,
    )

    private class FakeFactory : PtyTransportFactory {
        val connections = mutableListOf<FakeConnection>()

        override suspend fun open(generation: Long, gate: PtyGenerationGate): PtyTransportConnection =
            FakeConnection(generation, gate).also(connections::add)
    }

    private class FakeConnection(
        private val generation: Long,
        private val gate: PtyGenerationGate,
    ) : PtyTransportConnection {
        private val source = MutableSharedFlow<PtyEvent>(replay = 16, extraBufferCapacity = 32)
        override val events: Flow<PtyEvent> = source
        var sendCount = 0
            private set
        private var connected = false

        fun emit(event: PtyEvent) {
            if (event is PtyEvent.Connected) connected = true
            source.tryEmit(event)
        }

        override fun sendTextChecked(text: String): Result<PtySendReceipt> {
            if (!gate.isCurrent(generation) || !connected) {
                return Result.failure(PtySendException("stale", PtySendReceipt()))
            }
            sendCount += 1
            return Result.success(PtySendReceipt(bodyAccepted = true))
        }

        override fun sendRawChecked(text: String): Result<PtySendReceipt> = sendTextChecked(text)

        override fun resizeChecked(cols: Int, rows: Int): Result<PtySendReceipt> = sendTextChecked("resize")

        override fun close() {
            connected = false
        }
    }
}
