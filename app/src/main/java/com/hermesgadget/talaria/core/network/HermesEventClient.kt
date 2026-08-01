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

import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.domain.model.effectiveManagementProfile
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

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
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val reconnectBackoff = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L)

    private val _events = MutableSharedFlow<HermesSideEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<HermesSideEvent> = _events.asSharedFlow()

    private data class PendingRpc(
        val callback: (JsonElement?) -> Unit,
        val timeout: Job,
    )

    private val pendingRpc = ConcurrentHashMap<Long, PendingRpc>()

    fun currentChannel(): String? = channelId

    fun start(channel: String = UUID.randomUUID().toString(), includeRpc: Boolean = true) {
        stop()
        channelId = channel
        stopped = false
        job = scope.launch {
            if (connectionStore.activeProfile() == null) {
                _events.emit(HermesSideEvent.TransportError("auth", "No active connection profile"))
                return@launch
            }
            val auth = runCatching { wsAuth.authQueryParam() }.getOrElse {
                _events.emit(HermesSideEvent.TransportError("auth", it.message ?: "authentication failed"))
                return@launch
            }
            openEvents(channel, auth)
            if (includeRpc) openRpc(auth)
        }
    }

    fun stop() {
        // Guard first: close callbacks must not schedule reconnects.
        stopped = true
        reconnectAttempts.clear()
        reconnectJobs.values.forEach(Job::cancel)
        reconnectJobs.clear()
        job?.cancel()
        job = null
        eventsSocket?.close(1000, "stop")
        rpcSocket?.close(1000, "stop")
        eventsSocket = null
        rpcSocket = null
        channelId = null
        pendingRpc.values.forEach {
            it.timeout.cancel()
            it.callback(null)
        }
        pendingRpc.clear()
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    fun sendRpc(method: String, params: JsonObject = JsonObject(emptyMap()), onResult: ((JsonElement?) -> Unit)? = null) {
        val id = rpcId.getAndIncrement()
        if (onResult != null) {
            val timeout = scope.launch {
                delay(RPC_TIMEOUT_MS)
                pendingRpc.remove(id)?.callback?.invoke(null)
            }
            pendingRpc[id] = PendingRpc(onResult, timeout)
        }
        // Quote the method name — unquoted `method:model.info` is rejected by Hermes
        // (`Expecting value` parse errors on every connect; see gui.log).
        val safeMethod = method.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"jsonrpc":"2.0","id":$id,"method":"$safeMethod","params":$params}"""
        if (rpcSocket?.send(body) != true) {
            pendingRpc.remove(id)?.let {
                it.timeout.cancel()
                it.callback(null)
            }
        }
    }

    /** Awaitable form for ordered workflows such as attaching several images before a prompt. */
    suspend fun requestRpc(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement? =
        suspendCancellableCoroutine { continuation ->
            sendRpc(method, params) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }

    fun respondPrompt(
        kind: PromptKind,
        sessionId: String?,
        requestId: String?,
        approved: Boolean,
        text: String?,
        approvalChoice: String = "once",
        onResult: (Boolean) -> Unit,
    ) {
        val method = when (kind) {
            PromptKind.APPROVAL -> "approval.respond"
            PromptKind.CLARIFY -> "clarify.respond"
            PromptKind.SUDO -> "sudo.respond"
            PromptKind.SECRET -> "secret.respond"
        }
        val params = buildJsonObject {
            when (kind) {
                PromptKind.APPROVAL -> {
                    sessionId?.let { put("session_id", it) }
                    put("choice", if (approved) approvalChoice else "deny")
                }
                PromptKind.CLARIFY -> {
                    put("request_id", requestId.orEmpty())
                    put("answer", if (approved) text.orEmpty() else "")
                }
                PromptKind.SUDO -> {
                    put("request_id", requestId.orEmpty())
                    put("password", if (approved) text.orEmpty() else "")
                }
                PromptKind.SECRET -> {
                    put("request_id", requestId.orEmpty())
                    put("value", if (approved) text.orEmpty() else "")
                }
            }
        }
        sendRpc(method, params) { result ->
            val root = result as? JsonObject
            val success = when (kind) {
                PromptKind.APPROVAL -> root?.get("resolved")?.jsonPrimitive?.booleanOrNull != false && root != null
                else -> root?.get("status")?.jsonPrimitive?.contentOrNull in setOf("ok", "expired")
            }
            onResult(success)
        }
    }

    /** Request context-aware slash completions from the connected Hermes TUI. */
    fun requestSlashCompletions(
        text: String,
        onResult: (List<SidecarSlashCompletion>) -> Unit,
    ) {
        sendRpc("complete.slash", buildJsonObject { put("text", text) }) { result ->
            val root = result as? JsonObject
            if (root == null) {
                onResult(emptyList())
                return@sendRpc
            }
            val replaceFrom = root["replace_from"]?.jsonPrimitive?.intOrNull ?: 1
            val prefix = if (replaceFrom > 1) text.take(replaceFrom.coerceAtMost(text.length)) else ""
            val completions = (root["items"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val raw = item["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val replacement = when {
                    replaceFrom > 1 -> prefix + raw
                    raw.startsWith('/') -> raw
                    else -> "/$raw"
                }
                SidecarSlashCompletion(
                    replacement = replacement,
                    description = item["meta"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    kind = item["kind"]?.jsonPrimitive?.contentOrNull,
                )
            }
            onResult(completions)
        }
    }

    /**
     * Reopens one socket after a transport failure. Backs off exponentially,
     * re-mints auth (single-use WS tickets may have expired), and gives up
     * after [MAX_RECONNECT_ATTEMPTS] consecutive failures.
     */
    private fun scheduleReconnect(name: String, failedSocket: WebSocket) {
        if (stopped) return
        val isCurrent = when (name) {
            "events" -> eventsSocket === failedSocket
            "rpc" -> rpcSocket === failedSocket
            else -> false
        }
        if (!isCurrent || reconnectJobs.containsKey(name)) return
        val attempt = reconnectAttempts.merge(name, 1, Int::plus) ?: 1
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts.remove(name)
            _events.tryEmit(
                HermesSideEvent.TransportError(name, "reconnect failed after $MAX_RECONNECT_ATTEMPTS attempts"),
            )
            return
        }
        val delayMs = reconnectBackoff.getOrElse(attempt - 1) { reconnectBackoff.last() }
        val reconnectJob = scope.launch {
            delay(delayMs)
            reconnectJobs.remove(name)
            if (stopped) return@launch
            val auth = runCatching { wsAuth.authQueryParam() }.getOrElse {
                _events.tryEmit(HermesSideEvent.TransportError(name, it.message ?: "authentication failed"))
                scheduleReconnect(name, failedSocket)
                return@launch
            }
            when (name) {
                "events" -> {
                    val ch = channelId ?: return@launch
                    openEvents(ch, auth)
                }
                "rpc" -> openRpc(auth)
            }
        }
        reconnectJobs[name] = reconnectJob
    }

    private fun openEvents(channel: String, auth: String) {
        val profile = connectionStore.activeProfile() ?: return
        val url = HermesWebSocketUrlBuilder.build(
            baseUrl = profile.baseUrl,
            endpoint = "api/events",
            authQuery = auth,
            query = listOf("channel" to channel, "profile" to profile.effectiveManagementProfile()),
        ) ?: run {
            _events.tryEmit(HermesSideEvent.TransportError("events", "Invalid dashboard URL"))
            return
        }
        val req = Request.Builder().url(url).build()
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
                    scheduleReconnect("events", webSocket)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!stopped) scheduleReconnect("events", webSocket)
                }
            },
        )
    }

    private fun openRpc(auth: String) {
        val profile = connectionStore.activeProfile() ?: return
        val url = HermesWebSocketUrlBuilder.build(
            baseUrl = profile.baseUrl,
            endpoint = "api/ws",
            authQuery = auth,
            query = listOf("profile" to profile.effectiveManagementProfile()),
        ) ?: return
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
                    // This is the authoritative command surface for the active
                    // Hermes instance: built-ins, quick commands, and skills.
                    sendRpc("commands.catalog") { result ->
                        parseCommandCatalog(result).takeIf { it.isNotEmpty() }?.let {
                            _events.tryEmit(HermesSideEvent.CommandCatalog(it))
                        }
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) = parseRpc(text)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _events.tryEmit(HermesSideEvent.TransportError("ws", t.message ?: "rpc WS failed"))
                    scheduleReconnect("rpc", webSocket)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!stopped) scheduleReconnect("rpc", webSocket)
                }
            },
        )
    }

    private fun parseRpc(text: String) {
        val el = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = el["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (id != null && el.containsKey("result")) {
            pendingRpc.remove(id)?.let {
                it.timeout.cancel()
                it.callback(el["result"])
            }
            return
        }
        if (id != null && el.containsKey("error")) {
            pendingRpc.remove(id)?.let {
                it.timeout.cancel()
                it.callback(null)
            }
            return
        }
        // Non-result frames: classify into a typed event (pure logic below).
        SidecarFrameParser.parse(el)?.let { _events.tryEmit(it) }
    }

    companion object {
        const val MAX_RECONNECT_ATTEMPTS = 6
        const val RPC_TIMEOUT_MS = 15_000L
    }

    private fun parseCommandCatalog(result: JsonElement?): List<SidecarSlashCommand> {
        val root = result as? JsonObject ?: return emptyList()
        val commands = linkedMapOf<String, SidecarSlashCommand>()

        fun addPairs(pairs: JsonElement?, category: String) {
            (pairs as? JsonArray).orEmpty().forEach { pairElement ->
                val pair = pairElement as? JsonArray ?: return@forEach
                val command = pair.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return@forEach
                val description = pair.getOrNull(1)?.jsonPrimitive?.contentOrNull.orEmpty()
                if (!command.startsWith('/')) return@forEach
                commands.putIfAbsent(
                    command.lowercase(),
                    SidecarSlashCommand(command, description, category),
                )
            }
        }

        (root["categories"] as? JsonArray).orEmpty().forEach { sectionElement ->
            val section = sectionElement as? JsonObject ?: return@forEach
            val category = section["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "Commands" }
            addPairs(section["pairs"], category)
        }
        // Quick commands and skills can be present only in the flat list.
        addPairs(root["pairs"], "Skills & custom")
        return commands.values.toList()
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
        // Gateway event envelopes keep event-specific fields under `payload`.
        // Flat frames used by older Hermes versions keep them at the top level.
        val payload = (frame["payload"] as? JsonObject) ?: frame
        val sessionId = frame["session_id"]?.jsonPrimitive?.contentOrNull
        return when {
            type == "message.start" -> HermesSideEvent.MessageStart(sessionId)
            type == "message.delta" -> HermesSideEvent.MessageDelta(
                sessionId = sessionId,
                text = payload["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            type == "message.interim" -> HermesSideEvent.MessageInterim(
                sessionId = sessionId,
                text = payload["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                alreadyStreamed = payload["already_streamed"]?.jsonPrimitive?.booleanOrNull == true,
            )
            type == "message.complete" -> {
                val usage = payload["usage"] as? JsonObject
                val failureReason = payload["failure_reason"]?.jsonPrimitive?.contentOrNull
                val prompt = usage?.longField("prompt_tokens", "input_tokens", "prompt")
                val completion = usage?.longField("completion_tokens", "output_tokens", "completion")
                val total = usage?.longField("total_tokens", "tokens")
                    ?: if (prompt != null || completion != null) (prompt ?: 0) + (completion ?: 0) else null
                HermesSideEvent.MessageComplete(
                    sessionId = sessionId,
                    text = payload["text"]?.jsonPrimitive?.contentOrNull
                        ?: payload["rendered"]?.jsonPrimitive?.contentOrNull
                        ?: failureReason.orEmpty(),
                    status = payload["status"]?.jsonPrimitive?.contentOrNull
                        ?: failureReason?.let { "error" },
                    totalTokens = total,
                    costUsd = usage?.doubleField("cost", "cost_usd", "total_cost"),
                )
            }
            type == "status.update" -> HermesSideEvent.Status(
                sessionId = sessionId,
                kind = payload["kind"]?.jsonPrimitive?.contentOrNull,
                text = payload["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            type.contains("tool") -> {
                val name = payload["name"]?.jsonPrimitive?.contentOrNull
                    ?: payload["tool"]?.jsonPrimitive?.contentOrNull
                    ?: "tool"
                val id = payload["tool_id"]?.jsonPrimitive?.contentOrNull
                    ?: payload["id"]?.jsonPrimitive?.contentOrNull
                    ?: payload["call_id"]?.jsonPrimitive?.contentOrNull
                    ?: name
                val status = when {
                    type.endsWith("complete") || type.endsWith("end") || type.endsWith("done") -> ToolCallStatus.DONE
                    type.endsWith("error") || type.endsWith("fail") -> ToolCallStatus.ERROR
                    else -> ToolCallStatus.RUNNING
                }
                val args = payload["args_text"]?.jsonPrimitive?.contentOrNull
                    ?: payload["args"]?.toString()
                    ?: payload["arguments"]?.toString()
                val message = payload["summary"]?.jsonPrimitive?.contentOrNull
                    ?: payload["error"]?.jsonPrimitive?.contentOrNull
                    ?: payload["message"]?.jsonPrimitive?.contentOrNull
                HermesSideEvent.Tool(id, name, status, args, message)
            }
            type.endsWith(".expire") -> HermesSideEvent.PromptExpired(
                sessionId = sessionId,
                requestId = payload["request_id"]?.jsonPrimitive?.contentOrNull,
            )
            type == "background.complete" -> {
                val text = payload["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                HermesSideEvent.BackgroundComplete(
                    sessionId = sessionId,
                    taskId = payload["task_id"]?.jsonPrimitive?.contentOrNull,
                    text = text,
                    failed = text.trimStart().startsWith("error:", ignoreCase = true),
                )
            }
            type.endsWith(".request") && (
                type.startsWith("approval.") || type.startsWith("clarify.") ||
                    type.startsWith("sudo.") || type.startsWith("secret.")
                ) -> {
                val message = payload["message"]?.jsonPrimitive?.contentOrNull
                    ?: payload["prompt"]?.jsonPrimitive?.contentOrNull
                    ?: payload["question"]?.jsonPrimitive?.contentOrNull
                    ?: payload["description"]?.jsonPrimitive?.contentOrNull
                    ?: payload["command"]?.jsonPrimitive?.contentOrNull
                    ?: "Approval required"
                val kind = when {
                    type.contains("sudo") -> PromptKind.SUDO
                    type.contains("clarify") -> PromptKind.CLARIFY
                    type.contains("secret") -> PromptKind.SECRET
                    else -> PromptKind.APPROVAL
                }
                val choices = (payload["choices"] as? JsonArray).orEmpty().mapNotNull {
                    it.jsonPrimitive.contentOrNull
                }
                HermesSideEvent.Prompt(
                    kind = kind,
                    message = message,
                    sessionId = sessionId,
                    requestId = payload["request_id"]?.jsonPrimitive?.contentOrNull,
                    choices = choices,
                    raw = payload,
                )
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
            type.contains("usage") || type.contains("cost") || payload.containsKey("usage") -> {
                val usage = (payload["usage"] as? JsonObject)
                    ?: (frame["usage"] as? JsonObject)
                    ?: payload
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
                val model = payload["model"]?.jsonPrimitive?.contentOrNull
                    ?: payload["name"]?.jsonPrimitive?.contentOrNull
                if (model != null) {
                    HermesSideEvent.Model(model, payload["connected"]?.jsonPrimitive?.booleanOrNull)
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
enum class PromptKind { APPROVAL, CLARIFY, SUDO, SECRET }

data class SidecarSlashCommand(
    val command: String,
    val description: String,
    val category: String,
)

data class SidecarSlashCompletion(
    val replacement: String,
    val description: String,
    val kind: String?,
)

sealed class HermesSideEvent {
    data class Tool(
        val id: String,
        val name: String,
        val status: ToolCallStatus,
        val argsPreview: String?,
        val message: String?,
    ) : HermesSideEvent()

    data class Prompt(
        val kind: PromptKind,
        val message: String,
        val sessionId: String?,
        val requestId: String?,
        val choices: List<String>,
        val raw: JsonObject,
    ) : HermesSideEvent()
    data class PromptExpired(val sessionId: String?, val requestId: String?) : HermesSideEvent()
    data class Model(val name: String, val connected: Boolean?) : HermesSideEvent()
    data class CommandCatalog(val commands: List<SidecarSlashCommand>) : HermesSideEvent()

    data class MessageStart(val sessionId: String?) : HermesSideEvent()
    data class MessageDelta(val sessionId: String?, val text: String) : HermesSideEvent()
    data class MessageInterim(
        val sessionId: String?,
        val text: String,
        val alreadyStreamed: Boolean,
    ) : HermesSideEvent()
    data class MessageComplete(
        val sessionId: String?,
        val text: String,
        val status: String?,
        val totalTokens: Long?,
        val costUsd: Double?,
    ) : HermesSideEvent()
    data class BackgroundComplete(
        val sessionId: String?,
        val taskId: String?,
        val text: String,
        val failed: Boolean,
    ) : HermesSideEvent()
    data class Status(val sessionId: String?, val kind: String?, val text: String) : HermesSideEvent()

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
