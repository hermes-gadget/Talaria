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

package com.nousresearch.talaria.core.network

import com.nousresearch.talaria.core.data.prefs.SecureConnectionStore
import com.nousresearch.talaria.core.util.AnsiStripper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID

sealed class PtyEvent {
    data class Output(val text: String, val raw: String) : PtyEvent()
    data class Connected(val sessionKey: String, val channel: String) : PtyEvent()
    data class Closed(val code: Int, val reason: String) : PtyEvent()
    data class Failure(val message: String) : PtyEvent()
}

/**
 * Mobile chat transport over Hermes `/api/pty` WebSocket.
 *
 * Auth: gated dashboards use `ticket=` (via [WsAuthHelper]); loopback uses `token=`.
 * Sidecar correlation: `channel=` matches `/api/events`.
 */
class PtyWebSocketSession(
    private val client: OkHttpClient,
    private val connectionStore: SecureConnectionStore,
    private val wsAuth: WsAuthHelper,
) {
    private var socket: WebSocket? = null
    var channel: String = UUID.randomUUID().toString()
        private set

    fun connect(
        resumeSessionId: String? = null,
        channelId: String = UUID.randomUUID().toString(),
        cols: Int = 80,
        rows: Int = 24,
    ): Flow<PtyEvent> = callbackFlow {
        this@PtyWebSocketSession.channel = channelId
        val profile = connectionStore.activeProfile()
            ?: run {
                trySend(PtyEvent.Failure("No active connection profile"))
                close()
                return@callbackFlow
            }
        val base = profile.baseUrl.trimEnd('/')
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "ws://$base"
        }
        val auth = runBlocking { wsAuth.authQueryParam() }
        val params = buildList {
            if (auth.isNotBlank()) add(auth)
            add("channel=$channelId")
            if (profile.managementProfile.isNotBlank()) {
                add("profile=${profile.managementProfile}")
            }
            if (!resumeSessionId.isNullOrBlank()) add("resume=$resumeSessionId")
            add("cols=$cols")
            add("rows=$rows")
        }.joinToString("&")
        val url = "$wsBase/api/pty?$params"
        val key = UUID.randomUUID().toString()
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                trySend(PtyEvent.Connected(key, channelId))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(PtyEvent.Output(AnsiStripper.strip(text), text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val text = bytes.utf8()
                trySend(PtyEvent.Output(AnsiStripper.strip(text), text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val hint = WsAuthHelper.explainCloseCode(code)
                if (hint != null) {
                    trySend(PtyEvent.Failure(hint))
                } else {
                    trySend(PtyEvent.Closed(code, reason))
                }
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(PtyEvent.Failure(t.message ?: "WebSocket failure"))
                close()
            }
        }
        val ws = client.newWebSocket(request, listener)
        socket = ws
        awaitClose { ws.close(1000, "client close") }
    }

    fun sendText(text: String) {
        val payload = if (text.endsWith('\n')) text else text + '\n'
        socket?.send(payload)
    }

    fun sendRaw(text: String) {
        socket?.send(text)
    }

    fun resize(cols: Int, rows: Int) {
        // Match dashboard xterm resize control used by ChatPage.
        socket?.send("\u001b[RESIZE:$cols;$rows]")
        socket?.send("""{"type":"resize","cols":$cols,"rows":$rows}""")
    }

    fun close() {
        socket?.close(1000, "done")
        socket = null
    }
}
