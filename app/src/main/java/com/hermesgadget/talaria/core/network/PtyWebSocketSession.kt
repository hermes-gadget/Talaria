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

import com.hermesgadget.talaria.core.util.AnsiStripper
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

sealed class PtyEvent {
    data class Output(val text: String, val raw: String) : PtyEvent()
    data class Connected(val sessionKey: String, val channel: String) : PtyEvent()
    data class Closed(val code: Int, val reason: String) : PtyEvent()
    data class Failure(val message: String, val code: Int? = null) : PtyEvent()
}

/** The individual WebSocket frames accepted by a prompt send. */
data class PtySendReceipt(
    val bodyAccepted: Boolean = false,
    val enterAccepted: Boolean = false,
    val rawAccepted: Boolean = false,
) {
    val accepted: Boolean
        get() = bodyAccepted || enterAccepted || rawAccepted
}

/** A checked send failed after zero or more frames were accepted by OkHttp. */
class PtySendException(
    message: String,
    val receipt: PtySendReceipt,
) : IllegalStateException(message)

/**
 * Mobile chat transport over Hermes `/api/pty` WebSocket.
 *
 * Auth: gated dashboards use `ticket=` (via [WsAuthHelper]); loopback uses `token=`.
 * Sidecar correlation: `channel=` matches `/api/events`.
 */
class PtyWebSocketSession(
    private val client: OkHttpClient,
    private val wsAuth: WsAuthHelper,
    /** The URL, management profile, and credentials for this socket operation. */
    private val snapshot: ConnectionSnapshot,
    /** Optional auth value captured for the same immutable transport snapshot. */
    private val fixedAuthQuery: String? = null,
    /** Optional supervisor generation; stale sockets cannot emit or send. */
    private val generationId: Long? = null,
    private val generationGate: PtyGenerationGate? = null,
) {
    private enum class SocketState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        CLOSING,
    }

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var state = SocketState.DISCONNECTED

    var channel: String = UUID.randomUUID().toString()
        private set

    fun connect(
        resumeSessionId: String? = null,
        channelId: String = UUID.randomUUID().toString(),
        cols: Int = 80,
        rows: Int = 24,
        attachToken: String? = null,
    ): Flow<PtyEvent> = callbackFlow {
        if (!generationIsCurrent()) {
            trySendBlocking(PtyEvent.Failure(staleGenerationMessage()))
            close()
            return@callbackFlow
        }
        this@PtyWebSocketSession.channel = channelId
        state = SocketState.CONNECTING
        // callbackFlow's builder is already suspendable. Waiting directly keeps
        // token discovery and ticket minting off the main thread instead of
        // freezing Compose during a remote HTTP round trip.
        val auth = fixedAuthQuery ?: wsAuth.authQueryParam(snapshot)
        if (!generationIsCurrent()) {
            state = SocketState.DISCONNECTED
            trySendBlocking(PtyEvent.Failure(staleGenerationMessage()))
            close()
            return@callbackFlow
        }
        val url = HermesWebSocketUrlBuilder.build(
            baseUrl = snapshot.baseUrl,
            endpoint = "api/pty",
            authQuery = auth,
            query = listOf(
                "channel" to channelId,
                "profile" to snapshot.managementProfile,
                "resume" to resumeSessionId,
                "attach" to attachToken,
                "cols" to cols.toString(),
                "rows" to rows.toString(),
            ),
        ) ?: run {
            state = SocketState.DISCONNECTED
            trySendBlocking(PtyEvent.Failure("Invalid dashboard URL"))
            close()
            return@callbackFlow
        }
        val key = UUID.randomUUID().toString()
        val ansi = AnsiStripper.Stream()
        val oversizedFailurePending = AtomicBoolean(false)
        val request = Request.Builder().url(url).build()

        fun emit(event: PtyEvent) {
            // Connection, close, failure, and output events are delivered with
            // bounded-channel backpressure instead of silently dropping a
            // prompt boundary or a diagnostic frame when the UI is behind.
            trySendBlocking(event)
        }

        fun rejectOversizedFrame(webSocket: WebSocket, binary: Boolean) {
            if (!generationIsCurrent()) return
            state = SocketState.CLOSING
            oversizedFailurePending.set(true)
            webSocket.close(
                WebSocketFrameBudget.MESSAGE_TOO_BIG_CLOSE_CODE,
                "message too large",
            )
            emit(
                PtyEvent.Failure(
                    "PTY ${if (binary) "binary" else "text"} frame exceeds ${WebSocketFrameBudget.MAX_FRAME_BYTES} bytes",
                    WebSocketFrameBudget.MESSAGE_TOO_BIG_CLOSE_CODE,
                ),
            )
            oversizedFailurePending.set(false)
            close()
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!generationIsCurrent()) {
                    webSocket.close(1000, "stale generation")
                    return
                }
                socket = webSocket
                state = SocketState.CONNECTED
                emit(PtyEvent.Connected(key, channelId))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!generationIsCurrent()) return
                if (!WebSocketFrameBudget.textWithinLimit(text)) {
                    rejectOversizedFrame(webSocket, binary = false)
                    return
                }
                emit(PtyEvent.Output(ansi.append(text), text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!generationIsCurrent()) return
                if (!WebSocketFrameBudget.binaryWithinLimit(bytes)) {
                    rejectOversizedFrame(webSocket, binary = true)
                    return
                }
                val text = bytes.utf8()
                emit(PtyEvent.Output(ansi.append(text), text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!generationIsCurrent()) return
                state = SocketState.CLOSING
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!generationIsCurrent()) return
                state = SocketState.DISCONNECTED
                if (socket === webSocket) socket = null
                if (oversizedFailurePending.get() && code == WebSocketFrameBudget.MESSAGE_TOO_BIG_CLOSE_CODE) {
                    // rejectOversizedFrame owns the failure event and closes
                    // the flow after enqueueing it; keep the close callback
                    // from racing a Closed event ahead of that failure.
                    return
                }
                val hint = WsAuthHelper.explainCloseCode(code)
                if (hint != null) {
                    emit(PtyEvent.Failure(hint, code))
                } else {
                    emit(PtyEvent.Closed(code, reason))
                }
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!generationIsCurrent()) return
                state = SocketState.DISCONNECTED
                if (socket === webSocket) socket = null
                emit(PtyEvent.Failure(t.message ?: "WebSocket failure", response?.code))
                close()
            }
        }
        val ws = client.newWebSocket(request, listener)
        socket = ws
        awaitClose {
            state = SocketState.DISCONNECTED
            if (socket === ws) socket = null
            ws.close(1000, "client close")
        }
    }.buffer(
        capacity = PTY_EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /**
     * Send the body and Enter frames, reporting whether each was accepted by
     * OkHttp. A true return from [WebSocket.send] means the frame entered the
     * WebSocket writer queue; it is the strongest delivery signal available on
     * this raw PTY protocol and is deliberately followed by a TUI ack in the
     * background prompt paths.
     */
    fun sendTextChecked(text: String): Result<PtySendReceipt> {
        if (!generationIsCurrent()) return staleGenerationFailure()
        val ws = connectedSocket() ?: return Result.failure(
            PtySendException(
                "PTY is not connected",
                PtySendReceipt(),
            ),
        )
        if (!WebSocketFrameBudget.textWithinLimit(text)) {
            return Result.failure(
                PtySendException(
                    "PTY prompt exceeds ${WebSocketFrameBudget.MAX_FRAME_BYTES} bytes",
                    PtySendReceipt(),
                ),
            )
        }
        val body = text.trimEnd('\n', '\r')
        var bodyAccepted = false
        if (body.isNotEmpty()) {
            val accepted = runCatching { ws.send(body) }.getOrDefault(false)
            if (!accepted) {
                return Result.failure(
                    PtySendException(
                        "PTY rejected the prompt body frame",
                        PtySendReceipt(),
                    ),
                )
            }
            bodyAccepted = true
        }

        val enterAccepted = runCatching { ws.send("\r") }.getOrDefault(false)
        if (!enterAccepted) {
            return Result.failure(
                PtySendException(
                    "PTY rejected the Enter frame",
                    PtySendReceipt(bodyAccepted = bodyAccepted),
                ),
            )
        }
        return Result.success(
            PtySendReceipt(
                bodyAccepted = bodyAccepted,
                enterAccepted = true,
            ),
        )
    }

    /** Send one raw PTY frame and report whether OkHttp accepted it. */
    fun sendRawChecked(text: String): Result<PtySendReceipt> {
        if (!generationIsCurrent()) return staleGenerationFailure()
        if (text.isEmpty()) {
            return Result.failure(
                PtySendException("PTY raw frame is empty", PtySendReceipt()),
            )
        }
        if (!WebSocketFrameBudget.textWithinLimit(text)) {
            return Result.failure(
                PtySendException(
                    "PTY raw frame exceeds ${WebSocketFrameBudget.MAX_FRAME_BYTES} bytes",
                    PtySendReceipt(),
                ),
            )
        }
        val ws = connectedSocket() ?: return Result.failure(
            PtySendException("PTY is not connected", PtySendReceipt()),
        )
        val accepted = runCatching { ws.send(text) }.getOrDefault(false)
        if (!accepted) {
            return Result.failure(
                PtySendException("PTY rejected the raw frame", PtySendReceipt()),
            )
        }
        return Result.success(PtySendReceipt(rawAccepted = true))
    }

    fun sendText(text: String) {
        // The Hermes TUI reads the PTY in raw mode. Send the message body and
        // the Enter key as SEPARATE frames: bundling "body\r" into one frame is
        // seen as a bracketed paste (the \r lands as a literal newline in the
        // input and nothing is submitted). A standalone \r frame reads as an
        // Enter keypress, which is what actually submits the line — matching how
        // xterm.js delivers a paste followed by the Enter key on the web dashboard.
        sendTextChecked(text)
    }

    fun sendRaw(text: String) {
        sendRawChecked(text)
    }

    private var lastResize: Pair<Int, Int>? = null

    fun resize(cols: Int, rows: Int) {
        resizeChecked(cols, rows)
    }

    fun resizeChecked(cols: Int, rows: Int): Result<PtySendReceipt> {
        // Hermes' PTY writer consumes ONLY the `\x1b[RESIZE:cols;rows]` escape
        // (see web_server.py `_RESIZE_RE`); any other frame — e.g. a JSON
        // `{"type":"resize"}` — is written straight to the PTY and echoed back
        // as garbage, flooding the transcript and corrupting typed input. Send
        // just the escape, and only when the dimensions actually change so we
        // don't spam the socket on every IME/layout tick.
        if (!generationIsCurrent() || state != SocketState.CONNECTED) {
            return Result.failure(PtySendException("PTY is not connected", PtySendReceipt()))
        }
        if (lastResize == cols to rows) {
            return Result.success(PtySendReceipt(rawAccepted = true))
        }
        val result = sendRawChecked("\u001b[RESIZE:$cols;$rows]")
        if (result.isSuccess) lastResize = cols to rows
        return result
    }

    fun close() {
        state = SocketState.CLOSING
        socket?.close(1000, "done")
        socket = null
        state = SocketState.DISCONNECTED
    }

    private fun connectedSocket(): WebSocket? =
        socket?.takeIf { state == SocketState.CONNECTED && generationIsCurrent() }

    private fun generationIsCurrent(): Boolean =
        generationId == null || generationGate?.isCurrent(generationId) == true

    private fun staleGenerationMessage(): String =
        "PTY generation ${generationId ?: "unknown"} is stale and cannot accept input"

    private fun staleGenerationFailure(): Result<PtySendReceipt> = Result.failure(
        PtySendException(staleGenerationMessage(), PtySendReceipt()),
    )
}

private const val PTY_EVENT_BUFFER_CAPACITY = 256
