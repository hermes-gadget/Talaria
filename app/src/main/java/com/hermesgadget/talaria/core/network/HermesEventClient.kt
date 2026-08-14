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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import com.hermesgadget.talaria.core.util.IngressOffer
import com.hermesgadget.talaria.core.util.IngressRetention
import com.hermesgadget.talaria.core.util.LossAwareIngress
import com.hermesgadget.talaria.core.util.LossAwareIngressMetrics
import com.hermesgadget.talaria.core.util.suspendResult

/** Immutable identity captured by one event-client start. */
data class HermesEventScope(
    val connectionId: String,
    val managementProfile: String,
    val channelId: String,
    val tabId: String? = null,
    val sessionId: String? = null,
)

/**
 * Sidecar ingress metadata retained through parsing, queueing, and replay.
 * Consumers can use [HermesEventClient.scopedEvents] when they need to audit
 * the transport owner; [HermesEventClient.events] exposes the compatible event
 * projection after the same generation checks have run.
 */
data class HermesEventEnvelope(
    val event: HermesSideEvent,
    val socketName: String,
    val socketIdentity: String,
    val generation: Long,
    val scope: HermesEventScope,
)

/** Payload-free transport diagnostics for bounded ingress and frame rejection. */
data class HermesEventClientDiagnostics(
    val ingress: LossAwareIngressMetrics,
    val oversizedTextFrames: Long,
    val oversizedBinaryFrames: Long,
)

private val DEFAULT_EVENT_RECONNECT_BACKOFF = longArrayOf(
    1_000L,
    2_000L,
    4_000L,
    8_000L,
    15_000L,
    30_000L,
)

/**
 * Sidecar sockets used by the web Chat tab:
 * - `/api/ws` — JSON-RPC (model picker, prompts, state)
 * - `/api/events?channel=` — tool progress fan-out from the PTY child
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HermesEventClient(
    private val clientFactory: HermesClientFactory,
    private val wsAuth: WsAuthHelper,
    /** Optional immutable connection snapshot for background runtimes. */
    val fixedSnapshot: ConnectionSnapshot? = null,
    /** Optional immutable tab/session scope captured with [fixedSnapshot]. */
    private val fixedEventScope: HermesEventScope? = null,
    /** Optional client built from [fixedSnapshot]. */
    private val fixedWebSocketClient: OkHttpClient? = null,
    /** Test seam for deterministic reconnect timing; auth is never captured here. */
    private val reconnectBackoff: LongArray = DEFAULT_EVENT_RECONNECT_BACKOFF,
) {
    private val json = JsonConfig.json
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rpcId = AtomicLong(1)
    /**
     * Critical events occupy measured FIFO capacity and are never evicted.
     * Replaceable status/progress/delta events are coalesced by [eventKey].
     */
    private val eventIngress = LossAwareIngress<HermesEventEnvelope>(
        capacity = EVENT_INGRESS_CAPACITY,
        retention = { eventRetention(it.event) },
        coalesceKey = { eventKey(it.event) },
    )
    /** A wake-up channel carries no event payload and is safe to conflate. */
    private val eventWake = Channel<Unit>(Channel.CONFLATED)
    private val oversizedTextFrames = AtomicLong(0)
    private val oversizedBinaryFrames = AtomicLong(0)
    private val lifecycleLock = Any()
    private var lastEventSequence: Long? = null

    private var channelId: String? = null
    private var job: Job? = null
    /** Snapshot the full transport at start so reconnects never follow a foreground switch. */
    @Volatile
    private var transportSnapshot: ConnectionSnapshot? = null

    /** The lifecycle generation invalidates every old socket and replay item. */
    private val lifecycleCounter = AtomicLong(0)
    private var lifecycleGeneration = 0L
    private var eventScope: HermesEventScope? = null

    /** Set false by [start] and true by [stop] so late close callbacks never reconnect. */
    @Volatile
    private var stopped = true

    /** Consecutive failures per socket name; reset after a stable open. */
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val stabilityJobs = ConcurrentHashMap<String, Job>()
    private val socketCounters = ConcurrentHashMap<String, AtomicLong>()

    private class SocketRegistration(
        val name: String,
        val identity: String,
        val lifecycleGeneration: Long,
        val generation: Long,
        val scope: HermesEventScope,
    ) {
        @Volatile var socket: WebSocket? = null
        @Volatile var opened = false
        @Volatile var closed = false
    }

    private val currentSockets = ConcurrentHashMap<String, SocketRegistration>()

    /** Replay the bounded startup burst for collectors that subscribe after [start]. */
    private val _events = MutableSharedFlow<HermesEventEnvelope>(
        replay = EVENT_REPLAY_CAPACITY,
    )
    /** Metadata-preserving stream for lifecycle-aware owners and diagnostics. */
    val scopedEvents: Flow<HermesEventEnvelope> = _events.filter(::isCurrentEnvelope)

    /** Compatibility projection; stale generations are filtered before mapping. */
    val events: Flow<HermesSideEvent> = scopedEvents.map { it.event }

    private val _eventsConnected = MutableStateFlow(false)
    val eventsConnected: StateFlow<Boolean> = _eventsConnected.asStateFlow()

    private var eventDispatcher: Job? = scope.launch {
        dispatchLoop()
    }

    private suspend fun dispatchLoop() {
        for (ignored in eventWake) {
            while (true) {
                val event = eventIngress.poll() ?: break
                dispatch(event)
            }
        }
    }

    private data class PendingRpc(
        val callback: (JsonElement?) -> Unit,
        val timeout: Job,
        val registration: SocketRegistration,
    )

    private val pendingRpc = ConcurrentHashMap<Long, PendingRpc>()

    fun currentChannel(): String? = channelId

    fun diagnostics(): HermesEventClientDiagnostics = HermesEventClientDiagnostics(
        ingress = eventIngress.metrics(),
        oversizedTextFrames = oversizedTextFrames.get(),
        oversizedBinaryFrames = oversizedBinaryFrames.get(),
    )

    /** Wait until the `/api/events` socket is open, without making it required. */
    suspend fun awaitEventsConnected(timeoutMs: Long = EVENTS_CONNECT_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) {
            eventsConnected.filter { it }.first()
            true
        } ?: false

    /** Wait until the JSON-RPC socket is open before an ordered RPC workflow. */
    suspend fun awaitRpcConnected(timeoutMs: Long = EVENTS_CONNECT_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val registration = currentSockets["rpc"]
                if (registration?.opened == true &&
                    registration.socket != null &&
                    isCurrentRegistration(registration)
                ) {
                    return@withTimeoutOrNull true
                }
                delay(10L)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false

    fun start(channel: String = UUID.randomUUID().toString(), includeRpc: Boolean = true) {
        stop()
        val startingSnapshot = fixedSnapshot ?: clientFactory.snapshot()
        if (startingSnapshot == null) {
            publishLifecycleError(channel)
            return
        }

        val generation = synchronized(lifecycleLock) {
            val next = lifecycleCounter.incrementAndGet()
            lifecycleGeneration = next
            channelId = channel
            eventScope = (fixedEventScope
                ?: HermesEventScope(
                    connectionId = startingSnapshot.connectionId,
                    managementProfile = startingSnapshot.managementProfile,
                    channelId = channel,
                )).copy(
                connectionId = startingSnapshot.connectionId,
                managementProfile = startingSnapshot.managementProfile,
                channelId = channel,
            )
            transportSnapshot = startingSnapshot
            lastEventSequence = null
            stopped = false
            next
        }
        _eventsConnected.value = false
        job = scope.launch {
            openEvents(channel, generation)
            if (includeRpc) openRpc(generation)
        }
    }

    private fun publishLifecycleError(channel: String) {
        val registration = synchronized(lifecycleLock) {
            val generation = lifecycleCounter.incrementAndGet()
            lifecycleGeneration = generation
            stopped = false
            channelId = channel
            transportSnapshot = null
            val scope = HermesEventScope(
                connectionId = "",
                managementProfile = "",
                channelId = channel,
            )
            eventScope = scope
            val socketGeneration = socketCounters
                .computeIfAbsent("auth") { AtomicLong(0) }
                .incrementAndGet()
            val authRegistration = SocketRegistration(
                name = "auth",
                identity = "auth-${UUID.randomUUID()}",
                lifecycleGeneration = generation,
                generation = socketGeneration,
                scope = scope,
            )
            currentSockets["auth"] = authRegistration
            authRegistration
        }
        publish(
            HermesSideEvent.TransportError("auth", "No active connection profile"),
            registration,
        )
    }

    fun stop() {
        val sockets: List<WebSocket>
        val callbacks: List<(JsonElement?) -> Unit>
        synchronized(lifecycleLock) {
            // Invalidate before closing: OkHttp may deliver close/message callbacks
            // synchronously while the close handshake is being initiated.
            stopped = true
            lifecycleGeneration = lifecycleCounter.incrementAndGet()
            currentSockets.values.forEach { it.closed = true }
            sockets = currentSockets.values.mapNotNull { it.socket }
            currentSockets.clear()
            reconnectAttempts.clear()
            reconnectJobs.values.forEach(Job::cancel)
            reconnectJobs.clear()
            stabilityJobs.values.forEach(Job::cancel)
            stabilityJobs.clear()
            job?.cancel()
            job = null
            eventDispatcher?.cancel()
            eventDispatcher = null
            while (eventWake.tryReceive().isSuccess) {
                // Drop wake-up tokens for the invalidated lifecycle; they carry
                // no event payload and must not restart old dispatch work.
            }
            channelId = null
            transportSnapshot = null
            eventScope = null
            lastEventSequence = null
            eventIngress.clear()
            _events.resetReplayCache()
            _eventsConnected.value = false
            callbacks = pendingRpc.values.map { pending ->
                pending.timeout.cancel()
                pending.callback
            }
            pendingRpc.clear()
        }
        sockets.forEach { it.close(1000, "stop") }
        callbacks.forEach { it(null) }
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    fun sendRpc(method: String, params: JsonObject = JsonObject(emptyMap()), onResult: ((JsonElement?) -> Unit)? = null) {
        val registration = currentSockets["rpc"]
        if (registration == null || !isCurrentRegistration(registration)) {
            onResult?.invoke(null)
            return
        }
        sendRpc(registration, method, params, onResult)
    }

    private fun sendRpc(
        registration: SocketRegistration,
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        onResult: ((JsonElement?) -> Unit)? = null,
    ) {
        if (!isCurrentRegistration(registration)) {
            onResult?.invoke(null)
            return
        }
        val id = rpcId.getAndIncrement()
        if (onResult != null) {
            val timeout = scope.launch {
                delay(RPC_TIMEOUT_MS)
                pendingRpc.remove(id)?.takeIf {
                    it.registration === registration && isCurrentRegistration(registration)
                }?.callback?.invoke(null)
            }
            pendingRpc[id] = PendingRpc(onResult, timeout, registration)
        }
        // Quote the method name — unquoted `method:model.info` is rejected by Hermes
        // (`Expecting value` parse errors on every connect; see gui.log).
        val safeMethod = method.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"jsonrpc":"2.0","id":$id,"method":"$safeMethod","params":$params}"""
        if (!isCurrentRegistration(registration) || registration.socket?.send(body) != true) {
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
    private fun scheduleReconnect(name: String, failedSocket: SocketRegistration) {
        if (!isCurrentOwner(failedSocket) || reconnectJobs.containsKey(name)) return
        stabilityJobs.remove(name)?.cancel()
        val attempt = reconnectAttempts.merge(name, 1, Int::plus) ?: 1
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts.remove(name)
            publish(
                HermesSideEvent.TransportError(
                    name,
                    "reconnect failed after $MAX_RECONNECT_ATTEMPTS attempts",
                ),
                failedSocket,
            )
            return
        }
        val delayMs = reconnectBackoff.getOrElse(attempt - 1) { reconnectBackoff.last() }
        val reconnectJob = scope.launch {
            delay(delayMs)
            reconnectJobs.remove(name)
            if (!isCurrentOwner(failedSocket)) return@launch
            val generation = failedSocket.lifecycleGeneration
            when (name) {
                "events" -> {
                    val ch = channelId ?: return@launch
                    openEvents(ch, generation, retryOnAuthFailure = true)
                }
                "rpc" -> openRpc(generation, retryOnAuthFailure = true)
            }
        }
        reconnectJobs[name] = reconnectJob
    }

    private suspend fun openEvents(
        channel: String,
        lifecycleGeneration: Long,
        retryOnAuthFailure: Boolean = true,
    ) {
        val registration = beginSocket("events", lifecycleGeneration) ?: return
        val auth = authFor(registration, retryOnAuthFailure) ?: return
        val snapshot = transportSnapshot ?: return
        if (!isCurrentRegistration(registration)) return
        val url = HermesWebSocketUrlBuilder.build(
            baseUrl = snapshot.baseUrl,
            endpoint = "api/events",
            authQuery = auth,
            query = listOf("channel" to channel, "profile" to snapshot.managementProfile),
        ) ?: run {
            publish(
                HermesSideEvent.TransportError("events", "Invalid dashboard URL"),
                registration,
            )
            return
        }
        val req = Request.Builder().url(url).build()
        val webSocket = (fixedWebSocketClient ?: clientFactory.webSocketClient(snapshot)).newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (isCurrentSocket(registration, webSocket)) {
                        registration.socket = webSocket
                        registration.opened = true
                        _eventsConnected.value = true
                        markSocketOpen(registration)
                    }
                }
                // The events socket speaks the same JSON-RPC `event` envelope as
                // /api/ws, so unwrap via parseRpc (which also handles flat frames).
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isCurrentSocket(registration, webSocket)) return
                    if (!WebSocketFrameBudget.textWithinLimit(text)) {
                        rejectOversizedFrame(registration, webSocket, binary = false)
                        return
                    }
                    parseRpc(text, registration)
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (!isCurrentSocket(registration, webSocket)) return
                    if (!WebSocketFrameBudget.binaryWithinLimit(bytes)) {
                        rejectOversizedFrame(registration, webSocket, binary = true)
                        return
                    }
                    parseRpc(bytes.utf8(), registration)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!isCurrentSocket(registration, webSocket)) return
                    publish(
                        HermesSideEvent.TransportError("events", t.message ?: "events WS failed"),
                        registration,
                    )
                    markSocketClosed(registration)
                    scheduleReconnect("events", registration)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (isCurrentSocket(registration, webSocket)) {
                        webSocket.close(code, reason)
                    } else {
                        webSocket.close(1000, "stale generation")
                    }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrentSocket(registration, webSocket)) return
                    if (isTerminalCloseCode(code)) {
                        publish(
                            HermesSideEvent.TransportError("events", closeMessage(code, reason)),
                            registration,
                        )
                        markSocketClosed(registration)
                        cancelReconnect("events")
                    } else {
                        markSocketClosed(registration)
                        scheduleReconnect("events", registration)
                    }
                }
            },
        )
        attachSocket(registration, webSocket)
    }

    private suspend fun openRpc(
        lifecycleGeneration: Long,
        retryOnAuthFailure: Boolean = true,
    ) {
        val registration = beginSocket("rpc", lifecycleGeneration) ?: return
        val auth = authFor(registration, retryOnAuthFailure) ?: return
        val snapshot = transportSnapshot ?: return
        if (!isCurrentRegistration(registration)) return
        val url = HermesWebSocketUrlBuilder.build(
            baseUrl = snapshot.baseUrl,
            endpoint = "api/ws",
            authQuery = auth,
            query = listOf("profile" to snapshot.managementProfile),
        ) ?: return
        val req = Request.Builder().url(url).build()
        val webSocket = (fixedWebSocketClient ?: clientFactory.webSocketClient(snapshot)).newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isCurrentSocket(registration, webSocket)) return
                    registration.socket = webSocket
                    registration.opened = true
                    markSocketOpen(registration)
                    // Proactive model-state probe: some dashboards only push
                    // model notifies on change, so ask once on connect.
                    sendRpc(registration, "model.info") { result ->
                        val obj = (result as? JsonObject) ?: return@sendRpc
                        val name = obj["model"]?.jsonPrimitive?.contentOrNull
                            ?: obj["name"]?.jsonPrimitive?.contentOrNull
                            ?: return@sendRpc
                        publish(
                            HermesSideEvent.Model(name, obj["connected"]?.jsonPrimitive?.booleanOrNull),
                            registration,
                        )
                    }
                    // This is the authoritative command surface for the active
                    // Hermes instance: built-ins, quick commands, and skills.
                    sendRpc(registration, "commands.catalog") { result ->
                        parseCommandCatalog(result).takeIf { it.isNotEmpty() }?.let {
                            publish(HermesSideEvent.CommandCatalog(it), registration)
                        }
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isCurrentSocket(registration, webSocket)) return
                    if (!WebSocketFrameBudget.textWithinLimit(text)) {
                        rejectOversizedFrame(registration, webSocket, binary = false)
                        return
                    }
                    parseRpc(text, registration)
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (!isCurrentSocket(registration, webSocket)) return
                    if (!WebSocketFrameBudget.binaryWithinLimit(bytes)) {
                        rejectOversizedFrame(registration, webSocket, binary = true)
                        return
                    }
                    parseRpc(bytes.utf8(), registration)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!isCurrentSocket(registration, webSocket)) return
                    publish(
                        HermesSideEvent.TransportError("ws", t.message ?: "rpc WS failed"),
                        registration,
                    )
                    markSocketClosed(registration)
                    failPendingRpc(registration)
                    scheduleReconnect("rpc", registration)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (isCurrentSocket(registration, webSocket)) {
                        webSocket.close(code, reason)
                    } else {
                        webSocket.close(1000, "stale generation")
                    }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrentSocket(registration, webSocket)) return
                    if (isTerminalCloseCode(code)) {
                        publish(
                            HermesSideEvent.TransportError("ws", closeMessage(code, reason)),
                            registration,
                        )
                        markSocketClosed(registration)
                        cancelReconnect("rpc")
                    } else {
                        markSocketClosed(registration)
                        failPendingRpc(registration)
                        scheduleReconnect("rpc", registration)
                    }
                }
            },
        )
        attachSocket(registration, webSocket)
    }

    private suspend fun authFor(registration: SocketRegistration, retryOnFailure: Boolean): String? {
        val snapshot = transportSnapshot ?: return null
        return try {
            wsAuth.authQueryParam(snapshot).also {
                if (!isCurrentRegistration(registration)) return null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            publish(
                HermesSideEvent.TransportError(
                    registration.name,
                    failure.message ?: "authentication failed",
                ),
                registration,
            )
            markSocketClosed(registration)
            if (retryOnFailure) scheduleReconnect(registration.name, registration)
            null
        }
    }

    private fun parseRpc(text: String, registration: SocketRegistration) {
        if (!isCurrentRegistration(registration)) return
        val el = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = el["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (id != null && el.containsKey("result")) {
            pendingRpc.remove(id)?.let { pending ->
                pending.timeout.cancel()
                if (pending.registration === registration && isCurrentRegistration(registration)) {
                    pending.callback(el["result"])
                }
            }
            return
        }
        if (id != null && el.containsKey("error")) {
            pendingRpc.remove(id)?.let { pending ->
                pending.timeout.cancel()
                if (pending.registration === registration && isCurrentRegistration(registration)) {
                    pending.callback(null)
                }
            }
            return
        }
        // Non-result frames: classify into a typed event (pure logic below).
        SidecarFrameParser.parse(el)?.let { publishParsed(it, registration) }
    }

    /** Convert a skipped server sequence into the same authoritative-read signal as a buffer gap. */
    private fun publishParsed(event: HermesSideEvent, registration: SocketRegistration) {
        val sequenced = event as? HermesSideEvent.SessionsChanged
        val sequence = sequenced?.sequence
        var gap: HermesSideEvent? = null
        if (sequence != null) {
            synchronized(lifecycleLock) {
                if (!isCurrentRegistrationLocked(registration)) return
                val previous = lastEventSequence
                if (previous != null && sequence > previous && sequence - previous > 1L) {
                    gap = HermesSideEvent.EventGap(
                        reason = "event sequence gap: $previous->$sequence",
                        sessionId = sequenced.sessionId,
                    )
                }
                if (previous == null || sequence > previous) lastEventSequence = sequence
            }
        }
        gap?.let { publish(it, registration) }
        publish(event, registration)
    }

    private fun publish(event: HermesSideEvent, registration: SocketRegistration) {
        var closeForIngressOverflow: WebSocket? = null
        synchronized(lifecycleLock) {
            // A terminal reconnect-budget error is published after the failed
            // registration is marked closed. Keep that diagnostic, but reject
            // every other late event from the closed socket.
            val terminalDiagnostic = event is HermesSideEvent.TransportError &&
                isCurrentOwnerLocked(registration)
            if (!isCurrentRegistrationLocked(registration) && !terminalDiagnostic) return
            val scope = eventScope ?: return
            val envelope = HermesEventEnvelope(
                event = event,
                socketName = registration.name,
                socketIdentity = registration.identity,
                generation = registration.generation,
                scope = scope,
            )
            when (eventIngress.offer(envelope)) {
                IngressOffer.REJECTED_LOSSLESS -> {
                    // A critical burst exceeded the documented bound. Closing
                    // the socket applies backpressure and forces authoritative
                    // reconciliation after reconnect; the critical item was
                    // not silently evicted from the bounded FIFO.
                    closeForIngressOverflow = registration.socket
                }
                else -> {
                    ensureEventDispatcherLocked()
                    eventWake.trySend(Unit)
                }
            }
        }
        closeForIngressOverflow?.close(1013, "critical ingress bound exceeded")
    }

    private suspend fun dispatch(envelope: HermesEventEnvelope) {
        if (!isCurrentEnvelope(envelope)) return
        // No DROP_OLDEST buffer is allowed here: a slow subscriber backpressures
        // the measured ingress, while replaceable events are coalesced before
        // reaching this multicast boundary.
        _events.emit(envelope)
    }

    /** Caller holds [lifecycleLock]. */
    private fun ensureEventDispatcherLocked() {
        if (eventDispatcher?.isActive != true) {
            eventDispatcher = scope.launch { dispatchLoop() }
        }
    }

    private fun rejectOversizedFrame(
        registration: SocketRegistration,
        webSocket: WebSocket,
        binary: Boolean,
    ) {
        if (!isCurrentSocket(registration, webSocket)) return
        if (binary) oversizedBinaryFrames.incrementAndGet() else oversizedTextFrames.incrementAndGet()
        // Close first so an ingress-overflow diagnostic cannot replace the
        // protocol-mandated 1009 close with a secondary backpressure close.
        webSocket.close(
            WebSocketFrameBudget.MESSAGE_TOO_BIG_CLOSE_CODE,
            "message too large",
        )
        publish(
            HermesSideEvent.TransportError(
                registration.name,
                "WebSocket ${if (binary) "binary" else "text"} frame exceeds ${WebSocketFrameBudget.MAX_FRAME_BYTES} bytes",
            ),
            registration,
        )
    }

    private fun markSocketOpen(registration: SocketRegistration) {
        stabilityJobs.remove(registration.name)?.cancel()
        stabilityJobs[registration.name] = scope.launch {
            delay(SOCKET_STABILITY_MS)
            if (isCurrentRegistration(registration)) {
                reconnectAttempts.remove(registration.name)
                stabilityJobs.remove(registration.name)
            }
        }
    }

    private fun markSocketClosed(registration: SocketRegistration) {
        registration.closed = true
        stabilityJobs.remove(registration.name)?.cancel()
        if (registration.name == "events" && isCurrentOwner(registration)) {
            _eventsConnected.value = false
        }
    }

    private fun failPendingRpc(registration: SocketRegistration) {
        pendingRpc.entries.removeIf { (_, pending) ->
            if (pending.registration !== registration) return@removeIf false
            pending.timeout.cancel()
            pending.callback(null)
            true
        }
    }

    private fun cancelReconnect(name: String) {
        reconnectJobs.remove(name)?.cancel()
        reconnectAttempts.remove(name)
    }

    private fun beginSocket(name: String, lifecycleGeneration: Long): SocketRegistration? {
        val scope = synchronized(lifecycleLock) {
            if (!isCurrentLifecycleLocked(lifecycleGeneration)) return@synchronized null
            eventScope
        } ?: return null
        val generation = socketCounters
            .computeIfAbsent(name) { AtomicLong(0) }
            .incrementAndGet()
        val registration = SocketRegistration(
            name = name,
            identity = "$name-${UUID.randomUUID()}",
            lifecycleGeneration = lifecycleGeneration,
            generation = generation,
            scope = scope,
        )
        val previous = synchronized(lifecycleLock) {
            if (!isCurrentLifecycleLocked(lifecycleGeneration)) {
                registration.closed = true
                null
            } else {
                currentSockets.put(name, registration)
            }
        }
        previous?.let {
            it.closed = true
            it.socket?.close(1000, "superseded")
        }
        if (name == "events") _eventsConnected.value = false
        return registration.takeIf { !it.closed }
    }

    private fun attachSocket(registration: SocketRegistration, webSocket: WebSocket) {
        val accepted = synchronized(lifecycleLock) {
            if (!isCurrentRegistrationLocked(registration)) {
                false
            } else {
                registration.socket = webSocket
                true
            }
        }
        if (!accepted) webSocket.close(1000, "stale generation")
    }

    private fun isCurrentSocket(registration: SocketRegistration, webSocket: WebSocket): Boolean =
        isCurrentRegistration(registration) &&
            (registration.socket == null || registration.socket === webSocket)

    private fun isCurrentOwner(registration: SocketRegistration): Boolean =
        synchronized(lifecycleLock) { isCurrentOwnerLocked(registration) }

    private fun isCurrentOwnerLocked(registration: SocketRegistration): Boolean =
        !stopped &&
            lifecycleGeneration == registration.lifecycleGeneration &&
            currentSockets[registration.name] === registration

    private fun isCurrentLifecycleLocked(generation: Long): Boolean =
        !stopped && lifecycleGeneration == generation

    private fun isCurrentRegistration(registration: SocketRegistration): Boolean =
        synchronized(lifecycleLock) { isCurrentRegistrationLocked(registration) }

    private fun isCurrentRegistrationLocked(registration: SocketRegistration): Boolean =
        isCurrentOwnerLocked(registration) && !registration.closed

    private fun isCurrentEnvelope(envelope: HermesEventEnvelope): Boolean =
        synchronized(lifecycleLock) { isCurrentEnvelopeLocked(envelope) }

    private fun isCurrentEnvelopeLocked(envelope: HermesEventEnvelope): Boolean {
        val registration = currentSockets[envelope.socketName] ?: return false
        // `registration.closed` is deliberately not checked here: callbacks
        // accepted before close must drain in order. New callbacks are already
        // rejected by [isCurrentRegistrationLocked], and a replacement socket
        // or lifecycle stop invalidates the identity/scope below.
        return !stopped &&
            registration.lifecycleGeneration == lifecycleGeneration &&
            registration.identity == envelope.socketIdentity &&
            registration.generation == envelope.generation &&
            registration.scope == envelope.scope &&
            eventScope == envelope.scope
    }

    private fun isTerminalCloseCode(code: Int): Boolean = code in TERMINAL_CLOSE_CODES

    private fun closeMessage(code: Int, reason: String): String =
        "WebSocket closed permanently ($code)${reason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"

    private fun eventRetention(event: HermesSideEvent): IngressRetention = when (event) {
        // These are boundaries or actions. Losing one cannot be repaired by a
        // later status/delta, so they occupy lossless FIFO capacity.
        is HermesSideEvent.Prompt,
        is HermesSideEvent.PromptExpired,
        is HermesSideEvent.MessageStart,
        is HermesSideEvent.MessageComplete,
        is HermesSideEvent.BackgroundComplete,
        is HermesSideEvent.SessionsChanged,
        is HermesSideEvent.SessionEnded,
        is HermesSideEvent.EventGap,
        is HermesSideEvent.TransportError,
        is HermesSideEvent.SessionInfo,
        -> IngressRetention.LOSSLESS

        // These are snapshots/progress/deltas. The latest value for their
        // scope is sufficient and avoids retaining a burst of stale payloads.
        else -> IngressRetention.REPLACEABLE
    }

    private fun eventKey(event: HermesSideEvent): Any? = when (event) {
        is HermesSideEvent.Status -> "status:${event.sessionId}:${event.kind}"
        is HermesSideEvent.CommandCatalog -> "catalog"
        is HermesSideEvent.MessageDelta -> "delta:${event.sessionId}"
        is HermesSideEvent.MessageInterim -> "interim:${event.sessionId}"
        is HermesSideEvent.Tool -> "tool:${event.id}"
        is HermesSideEvent.Model -> "model"
        is HermesSideEvent.Usage -> "usage"
        is HermesSideEvent.Raw -> "raw:${event.type}"
        else -> null
    }

    companion object {
        const val MAX_RECONNECT_ATTEMPTS = 6
        const val RPC_TIMEOUT_MS = 15_000L
        const val EVENTS_CONNECT_TIMEOUT_MS = 2_000L
        const val SOCKET_STABILITY_MS = 5_000L
        /** Maximum callback ingress before a critical burst applies backpressure. */
        const val EVENT_INGRESS_CAPACITY = 256
        /** Replay matches ingress capacity so an accepted critical burst is not truncated. */
        const val EVENT_REPLAY_CAPACITY = EVENT_INGRESS_CAPACITY
        private val TERMINAL_CLOSE_CODES = setOf(
            4401,
            4403,
            4404,
            4408,
            WebSocketFrameBudget.MESSAGE_TOO_BIG_CLOSE_CODE,
        )
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
            ?: payload["session_id"]?.jsonPrimitive?.contentOrNull
        val revision = payload["revision"]?.jsonPrimitive?.contentOrNull
            ?: payload["version"]?.jsonPrimitive?.contentOrNull
            ?: frame["revision"]?.jsonPrimitive?.contentOrNull
            ?: frame["version"]?.jsonPrimitive?.contentOrNull
        val sequence = payload.longField("sequence", "seq", "event_sequence")
            ?: frame.longField("sequence", "seq", "event_sequence")
        return when {
            type == "sessions.changed" -> HermesSideEvent.SessionsChanged(
                sessionId = sessionId,
                revision = revision,
                sequence = sequence,
            )
            type == "session.ended" || type == "session.end" || type == "session.closed" ->
                HermesSideEvent.SessionEnded(
                    sessionId = sessionId,
                    reason = payload["reason"]?.jsonPrimitive?.contentOrNull
                        ?: payload["end_reason"]?.jsonPrimitive?.contentOrNull,
                )
            type == "event.gap" || type == "events.gap" || type == "sessions.gap" ||
                payload["gap"]?.jsonPrimitive?.booleanOrNull == true ||
                (payload["dropped"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L) > 0L ->
                HermesSideEvent.EventGap(
                    reason = payload["reason"]?.jsonPrimitive?.contentOrNull ?: type,
                    sessionId = sessionId,
                )
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

    /** The session catalog changed; transcript/session list consumers should reconcile. */
    data class SessionsChanged(
        val sessionId: String? = null,
        val revision: String? = null,
        val sequence: Long? = null,
    ) : HermesSideEvent()

    /** A server-side session was closed/reset and may need removal from local state. */
    data class SessionEnded(
        val sessionId: String? = null,
        val reason: String? = null,
    ) : HermesSideEvent()

    /** A transport or bounded-buffer gap means the next read must be authoritative. */
    data class EventGap(
        val reason: String,
        val sessionId: String? = null,
    ) : HermesSideEvent()

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
