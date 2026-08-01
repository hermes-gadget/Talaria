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
import kotlinx.coroutines.delay
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

    /** Set false by [start] and true by [stop] so late close callbacks never reconnect. */
    @Volatile
    private var stopped = true

    /** Consecutive failures per socket name; reset on a successful open. */
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val reconnectBackoff = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L)

    private val _events = MutableSharedFlow<HermesSideEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<HermesSideEvent> = _events.asSharedFlow()

    private val pendingRpc = ConcurrentHashMap<Long, (JsonElement?) -> Unit>()

    fun currentChannel(): String? = channelId

    fun start(channel: String = UUID.randomUUID().toString()) {
        stop()
        channelId = channel
        stopped = false
        job = scope.launch {
            val auth = wsAuth.authQueryParam()
            openEvents(channel, auth)
            openRpc(auth)
        }
    }

    fun stop() {
        // Guard first: close callbacks must not schedule reconnects.
        stopped = true
        reconnectAttempts.clear()
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
        // Quote the method name — unquoted `method:model.info` is rejected by Hermes
        // (`Expecting value` parse errors on every connect; see gui.log).
        val safeMethod = method.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"jsonrpc":"2.0","id":$id,"method":"$safeMethod","params":$params}"""
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

    /**
     * Reopens one socket after a transport failure. Backs off exponentially,
     * re-mints auth (single-use WS tickets may have expired), and gives up
     * after [MAX_RECONNECT_ATTEMPTS] consecutive failures.
     */
    private fun scheduleReconnect(name: String) {
        if (stopped) return
        val attempt = reconnectAttempts.merge(name, 1, Int::plus) ?: 1
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts.remove(name)
            _events.tryEmit(
                HermesSideEvent.TransportError(name, "reconnect failed after $MAX_RECONNECT_ATTEMPTS attempts"),
            )
            return
        }
        val delayMs = reconnectBackoff.getOrElse(attempt - 1) { reconnectBackoff.last() }
        scope.launch {
            delay(delayMs)
            if (stopped) return@launch
            val auth = runCatching { wsAuth.authQueryParam() }.getOrNull() ?: return@launch
            when (name) {
                "events" -> {
                    val ch = channelId ?: return@launch
                    openEvents(ch, auth)
                }
                "rpc" -> openRpc(auth)
            }
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
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    reconnectAttempts.remove("events")
                }
                // The events socket speaks the same JSON-RPC `event` envelope as
                // /api/ws, so unwrap via parseRpc (which also handles flat frames).
                override fun onMessage(webSocket: WebSocket, text: String) = parseRpc(text)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _events.tryEmit(HermesSideEvent.TransportError("events", t.message ?: "events WS failed"))
                    scheduleReconnect("events")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!stopped) scheduleReconnect("events")
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
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    reconnectAttempts.remove("rpc")
                    // Proactive model-state probe: some dashboards only push
                    // model notifies on change, so ask once on connect.
                    sendRpc("model.info") { result ->
                        val obj = (result as? JsonObject) ?: return@sendRpc
                        val name = obj["model"]?.jsonPrimitive?.contentOrNull
                            ?: obj["name"]?.jsonPrimitive?.contentOrNull
                            ?: return@sendRpc
                        _events.tryEmit(
                            HermesSideEvent.Model(name, obj["connected"]?.jsonPrimitive?.booleanOrNull),
                        )
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) = parseRpc(text)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _events.tryEmit(HermesSideEvent.TransportError("ws", t.message ?: "rpc WS failed"))
                    scheduleReconnect("rpc")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!stopped) scheduleReconnect("rpc")
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
        // Non-result frames: classify into a typed event (pure logic below).
        SidecarFrameParser.parse(el)?.let { _events.tryEmit(it) }
    }

    companion object {
        const val MAX_RECONNECT_ATTEMPTS = 6
    }
}

/**
 * Pure classifier for sidecar frames (both flat and JSON-RPC `event` envelopes).
 * Kept side-effect-free so it can be unit-tested without sockets.
 */
object SidecarFrameParser {
    fun parse(raw: String): HermesSideEvent? =
        runCatching { JsonConfig.json.parseToJsonElement(raw).jsonObject }.getOrNull()?.let { parse(it) }

    fun parse(el: JsonObject): HermesSideEvent? {
        // Unwrap a JSON-RPC envelope. `event`-method frames carry the real event
        // name in params.type (session.info, sessions.changed) — keep it rather
        // than clobbering with the outer "event" method name.
        val method = el["method"]?.jsonPrimitive?.contentOrNull
        val frame: JsonObject = when {
            method == null -> el
            method == "event" -> (el["params"] as? JsonObject) ?: return null
            else -> {
                val params = (el["params"] as? JsonObject) ?: JsonObject(emptyMap())
                JsonObject(params + ("type" to JsonPrimitive(method)))
            }
        }
        val type = frame["type"]?.jsonPrimitive?.contentOrNull
            ?: frame["event"]?.jsonPrimitive?.contentOrNull
            ?: return null
        return when {
            type.contains("tool") -> {
                val name = frame["name"]?.jsonPrimitive?.contentOrNull
                    ?: frame["tool"]?.jsonPrimitive?.contentOrNull
                    ?: "tool"
                val id = frame["id"]?.jsonPrimitive?.contentOrNull
                    ?: frame["call_id"]?.jsonPrimitive?.contentOrNull
                    ?: name
                val status = when {
                    type.endsWith("complete") || type.endsWith("end") || type.endsWith("done") -> ToolCallStatus.DONE
                    type.endsWith("error") || type.endsWith("fail") -> ToolCallStatus.ERROR
                    else -> ToolCallStatus.RUNNING
                }
                val args = frame["args"]?.toString() ?: frame["arguments"]?.toString()
                HermesSideEvent.Tool(id, name, status, args, frame["message"]?.jsonPrimitive?.contentOrNull)
            }
            type.contains("prompt") || type.contains("approval") || type.contains("clarify") || type.contains("sudo") -> {
                val message = frame["message"]?.jsonPrimitive?.contentOrNull
                    ?: frame["prompt"]?.jsonPrimitive?.contentOrNull
                    ?: "Approval required"
                val kind = when {
                    type.contains("sudo") -> PromptKind.SUDO
                    type.contains("clarify") -> PromptKind.CLARIFY
                    else -> PromptKind.APPROVAL
                }
                HermesSideEvent.Prompt(kind, message, frame)
            }
            // Rich per-session status pushed on connect / model change.
            type == "session.info" -> {
                val payload = (frame["payload"] as? JsonObject) ?: frame
                HermesSideEvent.SessionInfo(
                    sessionId = frame["session_id"]?.jsonPrimitive?.contentOrNull,
                    model = payload["model"]?.jsonPrimitive?.contentOrNull,
                    provider = payload["provider"]?.jsonPrimitive?.contentOrNull,
                    reasoningEffort = payload["reasoning_effort"]?.jsonPrimitive?.contentOrNull,
                    approvalMode = payload["approval_mode"]?.jsonPrimitive?.contentOrNull,
                    yolo = payload["yolo"]?.jsonPrimitive?.booleanOrNull,
                    fast = payload["fast"]?.jsonPrimitive?.booleanOrNull,
                )
            }
            // Token/cost accounting when a provider emits it (not all do).
            type.contains("usage") || type.contains("cost") || frame.containsKey("usage") -> {
                val usage = (frame["usage"] as? JsonObject) ?: frame
                val prompt = usage.longField("prompt_tokens", "input_tokens", "prompt")
                val completion = usage.longField("completion_tokens", "output_tokens", "completion")
                val total = usage.longField("total_tokens", "tokens")
                    ?: if (prompt != null || completion != null) (prompt ?: 0) + (completion ?: 0) else null
                val cost = usage.doubleField("cost", "cost_usd", "total_cost")
                if (total != null || cost != null) {
                    HermesSideEvent.Usage(prompt, completion, total, cost)
                } else {
                    HermesSideEvent.Raw(type, frame)
                }
            }
            type.contains("model") -> {
                val model = frame["model"]?.jsonPrimitive?.contentOrNull
                    ?: frame["name"]?.jsonPrimitive?.contentOrNull
                if (model != null) {
                    HermesSideEvent.Model(model, frame["connected"]?.jsonPrimitive?.booleanOrNull)
                } else {
                    HermesSideEvent.Raw(type, frame)
                }
            }
            else -> HermesSideEvent.Raw(type, frame)
        }
    }
}

private fun JsonObject.longField(vararg names: String): Long? {
    for (n in names) {
        val v = this[n]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (v != null) return v
    }
    return null
}

private fun JsonObject.doubleField(vararg names: String): Double? {
    for (n in names) {
        val v = this[n]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        if (v != null) return v
    }
    return null
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

    /** Live agent config for a session (model, provider, reasoning, approval mode). */
    data class SessionInfo(
        val sessionId: String?,
        val model: String?,
        val provider: String?,
        val reasoningEffort: String?,
        val approvalMode: String?,
        val yolo: Boolean?,
        val fast: Boolean?,
    ) : HermesSideEvent()

    /** Token/cost accounting when the provider emits it. */
    data class Usage(
        val promptTokens: Long?,
        val completionTokens: Long?,
        val totalTokens: Long?,
        val costUsd: Double?,
    ) : HermesSideEvent()

    data class TransportError(val socket: String, val message: String) : HermesSideEvent()
    data class Raw(val type: String, val payload: JsonObject) : HermesSideEvent()
}
