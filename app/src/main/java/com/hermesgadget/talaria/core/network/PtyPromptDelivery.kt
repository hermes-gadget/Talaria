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

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** Result of a prompt that reached the TUI and produced an acceptance signal. */
data class PtyPromptDeliveryReceipt(
    val sessionKey: String,
    val send: PtySendReceipt,
)

/**
 * A prompt could not be proven to have reached the TUI. [frameAccepted] is
 * true when at least one raw frame entered the WebSocket writer queue, so a
 * caller must not blindly retry it as though it had never been sent.
 */
class PtyPromptDeliveryException(
    message: String,
    val frameAccepted: Boolean,
    cause: Throwable? = null,
) : IOException(message, cause)

enum class PtyPromptDeliveryStart {
    SEND,
    ALREADY_ACCEPTED,
    IN_FLIGHT,
}

/**
 * Acceptance gate for user-initiated prompts.
 *
 * Transport recovery never calls [begin] again for an accepted delivery id.
 * The ledger is intentionally independent from [PtyTransportSupervisor]: a
 * new socket generation is allowed to recover the transport, but it is never
 * allowed to replay a prompt whose frame already entered the old writer queue.
 */
class PtyPromptDeliveryLedger {
    private enum class Entry {
        IN_FLIGHT,
        ACCEPTED,
    }

    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun begin(deliveryId: String): PtyPromptDeliveryStart {
        require(deliveryId.isNotBlank()) { "deliveryId must not be blank" }
        return when (entries[deliveryId]) {
            Entry.ACCEPTED -> PtyPromptDeliveryStart.ALREADY_ACCEPTED
            Entry.IN_FLIGHT -> PtyPromptDeliveryStart.IN_FLIGHT
            null -> {
                entries[deliveryId] = Entry.IN_FLIGHT
                PtyPromptDeliveryStart.SEND
            }
        }
    }

    /** Record the strongest transport acceptance signal available. */
    @Synchronized
    fun complete(deliveryId: String, result: Result<PtySendReceipt>): Boolean {
        val accepted = result.getOrNull()?.accepted == true ||
            (result.exceptionOrNull() as? PtySendException)?.receipt?.accepted == true
        if (accepted) entries[deliveryId] = Entry.ACCEPTED else entries.remove(deliveryId)
        return accepted
    }

    @Synchronized
    fun isAccepted(deliveryId: String): Boolean = entries[deliveryId] == Entry.ACCEPTED
}

/**
 * Shared handshake for notification replies and car quick actions.
 *
 * The first PTY output only proves that the terminal mounted. The prompt is
 * sent only after that output and both frames report accepted. The socket then
 * remains attached until the sidecar publishes `message.start`/a transcript
 * event or the PTY produces a new post-send render. This replaces elapsed-time
 * guesses with a transport-visible acknowledgement.
 */
object PtyPromptDelivery {
    const val DEFAULT_TIMEOUT_MS = 15_000L

    suspend fun deliver(
        session: PtyWebSocketSession,
        ptyEvents: Flow<PtyEvent>,
        text: String,
        eventClient: HermesEventClient? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): PtyPromptDeliveryReceipt {
        require(timeoutMs > 0) { "timeoutMs must be positive" }

        var sessionKey = ""
        var sendReceipt: PtySendReceipt? = null
        var frameAccepted = false
        var outputTranscript = ""

        val ptySignals = ptyEvents.map { PromptSignal.Pty(it) }
        val sidecarSignals = eventClient?.events?.map { PromptSignal.Sidecar(it) }
        val signals: Flow<PromptSignal> = if (sidecarSignals == null) {
            ptySignals
        } else {
            merge(ptySignals, sidecarSignals)
        }

        try {
            try {
                withTimeout(timeoutMs) {
                    signals.collect { signal ->
                        when (signal) {
                            is PromptSignal.Sidecar -> {
                                if (sendReceipt != null && signal.event.isPromptAccepted()) {
                                    throw PromptAccepted
                                }
                            }

                            is PromptSignal.Pty -> when (val event = signal.event) {
                                is PtyEvent.Connected -> sessionKey = event.sessionKey
                                is PtyEvent.Output -> {
                                    if (sendReceipt == null) {
                                        if (sessionKey.isBlank()) return@collect
                                        val result = session.sendTextChecked(text)
                                        sendReceipt = result.getOrElse { failure ->
                                            val sendFailure = failure as? PtySendException
                                            frameAccepted = sendFailure?.receipt?.accepted == true
                                            throw PtyPromptDeliveryException(
                                                failure.message ?: "PTY rejected the prompt",
                                                frameAccepted = frameAccepted,
                                                cause = failure,
                                            )
                                        }
                                        frameAccepted = true
                                    } else {
                                        val acknowledgement = PtyPromptAcknowledgement.isAccepted(
                                            event,
                                            text,
                                            outputTranscript,
                                        )
                                        outputTranscript = acknowledgement.second
                                        if (acknowledgement.first) throw PromptAccepted
                                    }
                                }

                                is PtyEvent.Failure -> throw PtyPromptDeliveryException(
                                    event.message,
                                    frameAccepted = frameAccepted,
                                )

                                is PtyEvent.Closed -> throw PtyPromptDeliveryException(
                                    "Chat closed before the prompt was acknowledged (${event.code})",
                                    frameAccepted = frameAccepted,
                                )
                            }
                        }
                    }
                }
            } catch (_: PromptAcceptedSignal) {
                // The acknowledgement is the successful completion condition.
            } catch (timeout: TimeoutCancellationException) {
                throw PtyPromptDeliveryException(
                    "Timed out waiting for the TUI to acknowledge the prompt",
                    frameAccepted = frameAccepted,
                    cause = timeout,
                )
            }
        } finally {
            eventClient?.stop()
            session.close()
        }

        val receipt = sendReceipt
            ?: throw PtyPromptDeliveryException(
                "PTY closed before the prompt was sent",
                frameAccepted = false,
            )
        if (sessionKey.isBlank()) {
            throw PtyPromptDeliveryException(
                "PTY acknowledged the prompt without a session key",
                frameAccepted = true,
            )
        }
        return PtyPromptDeliveryReceipt(sessionKey, receipt)
    }

    private sealed interface PromptSignal {
        data class Pty(val event: PtyEvent) : PromptSignal
        data class Sidecar(val event: HermesSideEvent) : PromptSignal
    }

    private object PromptAccepted : PromptAcceptedSignal()

    private open class PromptAcceptedSignal : RuntimeException()
}

private object PtyPromptAcknowledgement {
    private val agentMarkers = listOf(
        "agent",
        "assistant",
        "thinking",
        "working",
        "running",
        "processing",
        "queued",
    )

    fun isAccepted(
        event: PtyEvent.Output,
        prompt: String,
        previousTranscript: String,
    ): Pair<Boolean, String> {
        val visible = event.text.ifBlank { event.raw }
        val transcript = (previousTranscript + visible).takeLast(MAX_TRANSCRIPT_LENGTH)
        if (visible.isBlank()) return false to transcript
        val normalized = transcript.lowercase()
        val promptText = prompt.trim()
        val normalizedPrompt = promptText.lowercase()
        return (
            (promptText.isNotEmpty() && normalized.contains(normalizedPrompt)) ||
                agentMarkers.any(normalized::contains)
            ) to transcript
    }

    private const val MAX_TRANSCRIPT_LENGTH = 8_192
}

private fun HermesSideEvent.isPromptAccepted(): Boolean = when (this) {
    is HermesSideEvent.MessageStart,
    is HermesSideEvent.MessageDelta,
    is HermesSideEvent.MessageInterim,
    is HermesSideEvent.MessageComplete,
    -> true
    else -> false
}
