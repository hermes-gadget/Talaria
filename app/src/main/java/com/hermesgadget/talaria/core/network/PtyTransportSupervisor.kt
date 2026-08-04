/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hermesgadget.talaria.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong
import kotlin.random.Random

/** The result of classifying a PTY close or transport failure. */
enum class PtyCloseDisposition {
    RETRY,
    STOP,
    GRACEFUL_STOP,
}

data class PtyCloseClassification(
    val disposition: PtyCloseDisposition,
    val message: String,
)

/**
 * Close-code policy shared by the PTY supervisor and its tests.
 *
 * Hermes uses the 44xx range for application-level WebSocket rejects. A
 * normal client close is deliberately separate from a terminal reject: the
 * former is the local stop path, while the latter is surfaced with a clear
 * message and waits for one explicit user retry.
 */
object PtyCloseCodeClassifier {
    private val terminalCodes = setOf(401, 403, 404, 4401, 4403, 4404, 4408, 1008)

    fun classify(code: Int, reason: String = ""): PtyCloseClassification = when {
        code == 1000 -> PtyCloseClassification(
            PtyCloseDisposition.GRACEFUL_STOP,
            "PTY closed gracefully${reasonSuffix(reason)}",
        )
        code in terminalCodes -> PtyCloseClassification(
            PtyCloseDisposition.STOP,
            WsAuthHelper.explainCloseCode(code)
                ?: "PTY connection was rejected ($code)${reasonSuffix(reason)}",
        )
        else -> PtyCloseClassification(
            PtyCloseDisposition.RETRY,
            "PTY connection lost ($code)${reasonSuffix(reason)}",
        )
    }

    fun classifyFailure(code: Int?, message: String): PtyCloseClassification =
        code?.let { classify(it, message) }
            ?: PtyCloseClassification(PtyCloseDisposition.RETRY, message.ifBlank { "PTY connection failed" })

    fun isTerminal(code: Int): Boolean = code in terminalCodes

    private fun reasonSuffix(reason: String): String =
        reason.takeIf { it.isNotBlank() }?.let { ": ${it.trim()}" }.orEmpty()
}

/**
 * Bounded exponential backoff with symmetric jitter.
 *
 * [attempt] is one-based. Keeping the policy pure makes the cap and jitter
 * independently testable without opening a socket or waiting on a clock.
 */
class PtyBackoffPolicy(
    val maxAttempts: Int = 6,
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 30_000L,
    private val jitterRatio: Double = 0.2,
    private val random: Random = Random.Default,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(baseDelayMs > 0) { "baseDelayMs must be positive" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must not be below baseDelayMs" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be between 0 and 1" }
    }

    fun delayMs(attempt: Int): Long {
        require(attempt > 0) { "attempt must be one-based" }
        var bounded = baseDelayMs
        repeat((attempt - 1).coerceAtMost(30)) {
            bounded = if (bounded >= maxDelayMs) maxDelayMs else (bounded * 2).coerceAtMost(maxDelayMs)
        }
        val jitter = (bounded * jitterRatio * (random.nextDouble() * 2.0 - 1.0)).roundToLong()
        return (bounded + jitter).coerceIn(0L, maxDelayMs)
    }
}

/** Atomic current-generation gate used by every PTY socket. */
class PtyGenerationGate {
    private val current = AtomicLong(INACTIVE_GENERATION)

    fun activate(generation: Long) {
        require(generation > INACTIVE_GENERATION) { "generation must be positive" }
        current.set(generation)
    }

    fun invalidate(generation: Long) {
        current.compareAndSet(generation, INACTIVE_GENERATION)
    }

    fun isCurrent(generation: Long): Boolean = current.get() == generation

    fun invalidateAll() {
        current.set(INACTIVE_GENERATION)
    }

    private companion object {
        const val INACTIVE_GENERATION = 0L
    }
}

/** Minimal socket contract so the supervisor's state machine stays unit-testable. */
interface PtyTransportConnection {
    val events: Flow<PtyEvent>

    fun sendTextChecked(text: String): Result<PtySendReceipt>

    fun sendRawChecked(text: String): Result<PtySendReceipt>

    fun resizeChecked(cols: Int, rows: Int): Result<PtySendReceipt>

    fun close()
}

/** Adapter used by production chat code to put [PtyWebSocketSession] under supervision. */
class PtyWebSocketTransportConnection(
    private val session: PtyWebSocketSession,
    override val events: Flow<PtyEvent>,
) : PtyTransportConnection {
    override fun sendTextChecked(text: String): Result<PtySendReceipt> = session.sendTextChecked(text)

    override fun sendRawChecked(text: String): Result<PtySendReceipt> = session.sendRawChecked(text)

    override fun resizeChecked(cols: Int, rows: Int): Result<PtySendReceipt> =
        session.resizeChecked(cols, rows)

    override fun close() = session.close()
}

fun interface PtyTransportFactory {
    /** Build a brand-new socket. The factory must not cache auth or the old socket. */
    suspend fun open(generation: Long, gate: PtyGenerationGate): PtyTransportConnection
}

sealed interface PtyTransportState {
    data object Idle : PtyTransportState
    data class Connecting(val generation: Long, val attempt: Int) : PtyTransportState
    data class Connected(val generation: Long) : PtyTransportState
    data class Recovering(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val reason: String,
    ) : PtyTransportState
    data class Exhausted(
        val attempts: Int,
        val message: String,
        val terminal: Boolean,
        val manualRetryAvailable: Boolean = true,
    ) : PtyTransportState
    data object Stopped : PtyTransportState
}

data class PtyTransportEvent(
    val generation: Long,
    val event: PtyEvent,
)

/**
 * Supervises only the authoritative PTY socket. Sidecar sockets deliberately
 * remain outside this class so a PTY outage cannot tear down event delivery.
 */
class PtyTransportSupervisor(
    private val scope: CoroutineScope,
    private val factory: PtyTransportFactory,
    private val backoff: PtyBackoffPolicy = PtyBackoffPolicy(),
    private val stabilityWindowMs: Long = 5_000L,
    private val backoffDelay: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(stabilityWindowMs > 0) { "stabilityWindowMs must be positive" }
    }

    private data class ActiveConnection(
        val generation: Long,
        val connection: PtyTransportConnection,
    )

    private val lock = Any()
    private val generationGate = PtyGenerationGate()
    private val _state = MutableStateFlow<PtyTransportState>(PtyTransportState.Idle)
    private val _events = MutableSharedFlow<PtyTransportEvent>(
        replay = 32,
        extraBufferCapacity = 128,
    )

    @Volatile
    private var stopped = true
    @Volatile
    private var currentGeneration: Long? = null
    private var nextGeneration = 0L
    private var consecutiveFailures = 0
    private var active: ActiveConnection? = null
    private var attemptJob: Job? = null
    private var reconnectJob: Job? = null
    private var stabilityJob: Job? = null
    private var handledGeneration: Long? = null

    val state: StateFlow<PtyTransportState> = _state.asStateFlow()
    val events: SharedFlow<PtyTransportEvent> = _events.asSharedFlow()

    fun start() {
        synchronized(lock) {
            if (!stopped && currentGeneration != null) return
            stopped = false
            consecutiveFailures = 0
            reconnectJob?.cancel()
            reconnectJob = null
        }
        beginAttempt(attempt = 0)
    }

    /** Stop the loop and invalidate the current socket before closing it. */
    fun stop() {
        val old: ActiveConnection?
        synchronized(lock) {
            stopped = true
            currentGeneration = null
            reconnectJob?.cancel()
            reconnectJob = null
            stabilityJob?.cancel()
            stabilityJob = null
            attemptJob?.cancel()
            attemptJob = null
            old = active
            active = null
            generationGate.invalidateAll()
            _state.value = PtyTransportState.Stopped
        }
        old?.connection?.close()
    }

    /**
     * Start one fresh bounded cycle after automatic exhaustion. It does not
     * recreate the sidecar or alter the durable session/channel identity.
     */
    fun retry(): Boolean {
        synchronized(lock) {
            val retryable = _state.value is PtyTransportState.Exhausted ||
                _state.value is PtyTransportState.Connected
            if (!retryable) return false
            stopped = false
            consecutiveFailures = 0
            reconnectJob?.cancel()
            reconnectJob = null
        }
        beginAttempt(attempt = 0)
        return true
    }

    fun isCurrentGeneration(generation: Long): Boolean =
        !stopped && currentGeneration == generation && generationGate.isCurrent(generation)

    fun currentGeneration(): Long? = currentGeneration

    fun sendTextChecked(text: String): Result<PtySendReceipt> = currentConnectionOrFailure()?.let {
        it.connection.sendTextChecked(text)
    } ?: Result.failure(
        PtySendException("PTY is not connected or its generation is stale", PtySendReceipt()),
    )

    fun sendRawChecked(text: String): Result<PtySendReceipt> = currentConnectionOrFailure()?.let {
        it.connection.sendRawChecked(text)
    } ?: Result.failure(
        PtySendException("PTY is not connected or its generation is stale", PtySendReceipt()),
    )

    fun resizeChecked(cols: Int, rows: Int): Result<PtySendReceipt> = currentConnectionOrFailure()?.let {
        it.connection.resizeChecked(cols, rows)
    } ?: Result.failure(
        PtySendException("PTY is not connected or its generation is stale", PtySendReceipt()),
    )

    fun dispose() = stop()

    private fun currentConnectionOrFailure(): ActiveConnection? {
        val candidate = synchronized(lock) {
            active?.takeIf { _state.value is PtyTransportState.Connected }
        }
        return candidate?.takeIf { isCurrentGeneration(it.generation) }
    }

    private fun beginAttempt(attempt: Int) {
        val generation: Long
        val old: ActiveConnection?
        synchronized(lock) {
            if (stopped) return
            generation = ++nextGeneration
            currentGeneration = generation
            generationGate.activate(generation)
            old = active
            active = null
            old?.let { generationGate.invalidate(it.generation) }
            stabilityJob?.cancel()
            stabilityJob = null
            attemptJob?.cancel()
            _state.value = PtyTransportState.Connecting(generation, attempt)
        }
        old?.connection?.close()

        val job = scope.launch {
            val connection = try {
                // A new call for every generation is what makes single-use WS
                // tickets safe: the factory constructs a new PtyWebSocketSession.
                factory.open(generation, generationGate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                handleFailure(
                    generation,
                    PtyCloseClassification(
                        PtyCloseDisposition.RETRY,
                        failure.message ?: "PTY connection failed",
                    ),
                )
                return@launch
            }

            val accepted = synchronized(lock) {
                if (stopped || currentGeneration != generation || !generationGate.isCurrent(generation)) {
                    false
                } else {
                    active = ActiveConnection(generation, connection)
                    true
                }
            }
            if (!accepted) {
                connection.close()
                return@launch
            }

            try {
                connection.events.collect { event ->
                    if (!isCurrentGeneration(generation)) return@collect
                    _events.emit(PtyTransportEvent(generation, event))
                    when (event) {
                        is PtyEvent.Connected -> markConnected(generation)
                        is PtyEvent.Output -> Unit
                        is PtyEvent.Closed -> handleFailure(
                            generation,
                            PtyCloseCodeClassifier.classify(event.code, event.reason),
                        )
                        is PtyEvent.Failure -> handleFailure(
                            generation,
                            PtyCloseCodeClassifier.classifyFailure(event.code, event.message),
                        )
                    }
                }
                if (isCurrentGeneration(generation)) {
                    handleFailure(
                        generation,
                        PtyCloseClassification(PtyCloseDisposition.RETRY, "PTY stream ended unexpectedly"),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                handleFailure(
                    generation,
                    PtyCloseClassification(
                        PtyCloseDisposition.RETRY,
                        failure.message ?: "PTY connection failed",
                    ),
                )
            }
        }
        synchronized(lock) {
            if (currentGeneration == generation && !stopped) attemptJob = job
        }
    }

    private fun markConnected(generation: Long) {
        synchronized(lock) {
            if (!isCurrentGeneration(generation)) return
            _state.value = PtyTransportState.Connected(generation)
            stabilityJob?.cancel()
            stabilityJob = scope.launch {
                backoffDelay(stabilityWindowMs)
                synchronized(lock) {
                    if (isCurrentGeneration(generation) && _state.value is PtyTransportState.Connected) {
                        consecutiveFailures = 0
                        stabilityJob = null
                    }
                }
            }
        }
    }

    private fun handleFailure(generation: Long, classification: PtyCloseClassification) {
        val old: ActiveConnection?
        val nextAttempt: Int
        synchronized(lock) {
            if (stopped || currentGeneration != generation || handledGeneration == generation) return
            handledGeneration = generation
            currentGeneration = null
            generationGate.invalidate(generation)
            stabilityJob?.cancel()
            stabilityJob = null
            old = active
            active = null
            nextAttempt = consecutiveFailures + 1
            consecutiveFailures = nextAttempt
            when (classification.disposition) {
                PtyCloseDisposition.GRACEFUL_STOP -> {
                    stopped = true
                    _state.value = PtyTransportState.Stopped
                }
                PtyCloseDisposition.STOP -> {
                    stopped = true
                    _state.value = PtyTransportState.Exhausted(
                        attempts = nextAttempt,
                        message = classification.message,
                        terminal = true,
                    )
                }
                PtyCloseDisposition.RETRY -> {
                    if (nextAttempt > backoff.maxAttempts) {
                        stopped = true
                        _state.value = PtyTransportState.Exhausted(
                            attempts = backoff.maxAttempts,
                            message = "PTY recovery exhausted after ${backoff.maxAttempts} attempts: " +
                                classification.message,
                            terminal = false,
                        )
                    } else {
                        val wait = backoff.delayMs(nextAttempt)
                        _state.value = PtyTransportState.Recovering(
                            attempt = nextAttempt,
                            maxAttempts = backoff.maxAttempts,
                            delayMs = wait,
                            reason = classification.message,
                        )
                        reconnectJob?.cancel()
                        reconnectJob = scope.launch {
                            backoffDelay(wait)
                            synchronized(lock) {
                                if (stopped || currentGeneration != null) return@synchronized
                                reconnectJob = null
                            }
                            beginAttempt(nextAttempt)
                        }
                    }
                }
            }
        }
        old?.connection?.close()
    }
}
