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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Sidecar sockets used by the web Chat tab:
 * - `/api/ws` — JSON-RPC (model picker, prompts, state)
 * - `/api/events?channel=` — tool progress fan-out from the PTY child
 */
class HermesEventClient(
    private val clientFactory: HermesClientFactory,
    private val connectionStore: SecureConnectionStore,
    private val wsAuth: WsAuthHelper,
) {
    private val json = JsonConfig.json
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rpcId = AtomicLong(1)

    private var channelId: String? = null
    private var eventsSocket: WebSocket? = null
    private var rpcSocket: WebSocket? = null
    private var job: Job? = null

    private val _events = MutableSharedFlow<HermesSideEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<HermesSideEvent> = _events.asSharedFlow()

    private val pendingRpc = ConcurrentHashMap<Long, (JsonElement?) -> Unit>()

    fun currentChannel(): String? = channelId

    fun start(channel: String = UUID.randomUUID().toString()) {
        stop()
        channelId = channel
        job = scope.launch {
            val auth = wsAuth.authQueryParam()
            openEvents(channel, auth)
            openRpc(auth)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        eventsSocket?.close(1000, "stop")
        rpcSocket?.close(1000, "stop")
        eventsSocket = null
        rpcSocket = null
        channelId = null
        pendingRpc.clear()
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    fun sendRpc(method: String, params: JsonObject = JsonObject(emptyMap()), onResult: ((JsonElement?) -> Unit)? = null) {
        val id = rpcId.getAndIncrement()
        if (onResult != null) pendingRpc[id] = onResult
        val body = buildString {
            append("""{"jsonrpc":"2.0","id":""")
            append(id)
            append(""","method":""")
            append(method.replace("\"", "\\\""))
            append(""","params":""")
            append(params.toString())
            append("}")
        }
        rpcSocket?.send(body) ?: onResult?.invoke(null)
    }

    fun respondPrompt(approved: Boolean, text: String? = null) {
        val params = JsonObject(
            buildMap {
                put("approved", JsonPrimitive(approved))
                if (text != null) put("text", JsonPrimitive(text))
            },
        )
        rpcSocket?.send(
            """{"jsonrpc":"2.0","method":"prompt.respond","params":$params}""",
        )
    }

    private fun wsBase(): String? {
        val base = connectionStore.activeProfile()?.baseUrl?.trimEnd('/') ?: return null
        return when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "ws://$base"
        }
    }

    private fun openEvents(channel: String, auth: String) {
        val base = wsBase() ?: return
        val qs = buildList {
            add("channel=$channel")
            if (auth.isNotBlank()) add(auth)
            connectionStore.activeProfile()?.managementProfile?.takeIf { it.isNotBlank() }?.let {
                add("profile=$it")
            }
        }.joinToString("&")
        val req = Request.Builder().url("$base/api/events?$qs").build()
        eventsSocket = clientFactory.webSocketClient().newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = parseSidePayload(text)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _events.tryEmit(HermesSideEvent.TransportError("events", t.message ?: "events WS failed"))
                }
            },
        )
    }

    private fun openRpc(auth: String) {
        val base = wsBase() ?: return
        val qs = buildList {
            if (auth.isNotBlank()) add(auth)
            connectionStore.activeProfile()?.managementProfile?.takeIf { it.isNotBlank() }?.let {
                add("profile=$it")
            }
        }.joinToString("&")
        val url = if (qs.isEmpty()) "$base/api/ws" else "$base/api/ws?$qs"
        val req = Request.Builder().url(url).build()
        rpcSocket = clientFactory.webSocketClient().newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = parseRpc(text)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _events.tryEmit(HermesSideEvent.TransportError("ws", t.message ?: "rpc WS failed"))
                }
            },
        )
    }

    private fun parseRpc(text: String) {
        val el = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = el["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (id != null && el.containsKey("result")) {
            pendingRpc.remove(id)?.invoke(el["result"])
            return
        }
        val method = el["method"]?.jsonPrimitive?.contentOrNull ?: return
        val params = (el["params"] as? JsonObject) ?: JsonObject(emptyMap())
        val merged = JsonObject(params + ("type" to JsonPrimitive(method)))
        parseSidePayload(merged.toString())
    }

    private fun parseSidePayload(text: String) {
        val el = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val type = el["type"]?.jsonPrimitive?.contentOrNull
            ?: el["event"]?.jsonPrimitive?.contentOrNull
            ?: return
        when {
            type.contains("tool") -> {
                val name = el["name"]?.jsonPrimitive?.contentOrNull
                    ?: el["tool"]?.jsonPrimitive?.contentOrNull
                    ?: "tool"
                val id = el["id"]?.jsonPrimitive?.contentOrNull
                    ?: el["call_id"]?.jsonPrimitive?.contentOrNull
                    ?: name
                val status = when {
                    type.endsWith("complete") || type.endsWith("end") || type.endsWith("done") -> ToolCallStatus.DONE
                    type.endsWith("error") || type.endsWith("fail") -> ToolCallStatus.ERROR
                    else -> ToolCallStatus.RUNNING
                }
                val args = el["args"]?.toString() ?: el["arguments"]?.toString()
                _events.tryEmit(
                    HermesSideEvent.Tool(id, name, status, args, el["message"]?.jsonPrimitive?.contentOrNull),
                )
            }
            type.contains("prompt") || type.contains("approval") || type.contains("clarify") || type.contains("sudo") -> {
                val message = el["message"]?.jsonPrimitive?.contentOrNull
                    ?: el["prompt"]?.jsonPrimitive?.contentOrNull
                    ?: "Approval required"
                val kind = when {
                    type.contains("sudo") -> PromptKind.SUDO
                    type.contains("clarify") -> PromptKind.CLARIFY
                    else -> PromptKind.APPROVAL
                }
                _events.tryEmit(HermesSideEvent.Prompt(kind, message, el))
            }
            type.contains("model") -> {
                val model = el["model"]?.jsonPrimitive?.contentOrNull
                    ?: el["name"]?.jsonPrimitive?.contentOrNull
                if (model != null) {
                    _events.tryEmit(
                        HermesSideEvent.Model(model, el["connected"]?.jsonPrimitive?.booleanOrNull),
                    )
                }
            }
            else -> _events.tryEmit(HermesSideEvent.Raw(type, el))
        }
    }
}

enum class ToolCallStatus { RUNNING, DONE, ERROR }
enum class PromptKind { APPROVAL, CLARIFY, SUDO }

sealed class HermesSideEvent {
    data class Tool(
        val id: String,
        val name: String,
        val status: ToolCallStatus,
        val argsPreview: String?,
        val message: String?,
    ) : HermesSideEvent()

    data class Prompt(val kind: PromptKind, val message: String, val raw: JsonObject) : HermesSideEvent()
    data class Model(val name: String, val connected: Boolean?) : HermesSideEvent()
    data class TransportError(val socket: String, val message: String) : HermesSideEvent()
    data class Raw(val type: String, val payload: JsonObject) : HermesSideEvent()
}
