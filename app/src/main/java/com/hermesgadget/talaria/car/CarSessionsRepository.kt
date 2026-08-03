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

package com.hermesgadget.talaria.car

import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.network.HermesEventClient
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.core.network.PtyPromptDelivery
import com.hermesgadget.talaria.core.network.PtyWebSocketSession
import com.hermesgadget.talaria.core.network.fixedConnectionClient
import com.hermesgadget.talaria.domain.model.HERMES_DEFAULT_PROFILE
import com.hermesgadget.talaria.domain.model.SessionMessage
import com.hermesgadget.talaria.domain.model.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

/** A session plus its most recent messages, for the car conversation list. */
data class CarConversation(
    val session: SessionSummary,
    val messages: List<SessionMessage>,
)

/**
 * Thin data layer for the car screens. Reuses the app's existing Hermes
 * plumbing: the API client for session/message reads, and the short-lived
 * PTY send pattern from [com.hermesgadget.talaria.worker.ReplyWorker] for
 * sending prompts (existing sessions) and creating new sessions.
 */
object CarSessionsRepository {

    private val container get() = TalariaApp.instance.container

    /** True when a saved connection is active — the car UI needs one. */
    fun hasConnection(): Boolean = container.connectionStore.activeProfile() != null

    /**
     * Active sessions (no end marker, non-automation) each with their five
     * most recent messages, ready for the car conversation list.
     */
    suspend fun conversations(): Result<List<CarConversation>> = try {
        Result.success(withTimeout(CONVERSATIONS_TIMEOUT_MS) {
            val active = activeSessions().getOrThrow()
            val permits = Semaphore(MAX_MESSAGE_REQUESTS)
            coroutineScope {
                active.map { conversation ->
                    async(Dispatchers.IO) {
                        permits.withPermit {
                            CarConversation(
                                session = conversation,
                                messages = messages(conversation.id)
                                    .getOrDefault(emptyList())
                                    .takeLast(5),
                            )
                        }
                    }
                }.awaitAll()
            }
        })
    } catch (timeout: TimeoutCancellationException) {
        Result.failure(timeout)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    /**
     * Sessions currently open on the server: no end marker (not reset /
     * closed / compressed) and not an automation (cron/webhook) source.
     */
    suspend fun activeSessions(): Result<List<SessionSummary>> = try {
        val connection = container.connectionStore.activeProfile() ?: return Result.failure(
            IllegalStateException("No active connection"),
        )
        val raw = container.clientFactory.api().getSessionsForProfile(
            profile = connection.managementProfile.trim().ifBlank { HERMES_DEFAULT_PROFILE },
            limit = 100,
            offset = 0,
            order = "recent",
        )
        val sessions = decodeSessions(raw)
        Result.success(sessions.filter { s ->
            s.end_reason == null && s.ended_at == null &&
                !isAutomationSource(s.source)
        })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    suspend fun messages(sessionId: String): Result<List<SessionMessage>> = try {
        val connection = container.connectionStore.activeProfile() ?: return Result.failure(
            IllegalStateException("No active connection"),
        )
        val response = container.clientFactory.api().getSessionMessages(
            id = sessionId,
            profile = connection.managementProfile,
        )
        Result.success(response.messages.filter { !it.content.isNullOrBlank() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    /**
     * Send a prompt to an existing session via a short-lived PTY, exactly
     * like the notification ReplyWorker does.
     */
    suspend fun sendText(sessionId: String, text: String): Result<Unit> =
        try {
            withTimeout(PROMPT_TIMEOUT_MS) {
                ptySend(
                    resumeSessionId = sessionId,
                    text = text,
                    deliveryId = UUID.randomUUID().toString(),
                )
            }
            Result.success(Unit)
        } catch (timeout: TimeoutCancellationException) {
            Result.failure(timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }

    /**
     * Create a brand-new agent session and send the first prompt.
     * Returns the new session id (or null if the handshake predates it).
     */
    suspend fun createSession(prompt: String): Result<String> =
        try {
            Result.success(withTimeout(PROMPT_TIMEOUT_MS) {
                ptySend(
                    resumeSessionId = null,
                    text = prompt,
                    deliveryId = UUID.randomUUID().toString(),
                )
            })
        } catch (timeout: TimeoutCancellationException) {
            Result.failure(timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }

    /**
     * Opens a PTY and uses the first-output plus prompt-accepted acknowledgement
     * handshake. The deterministic delivery id is reused if a caller retries
     * after a frame was rejected, allowing the server-side attach registry to
     * reconnect to the same short-lived TUI.
     */
    private suspend fun ptySend(
        resumeSessionId: String?,
        text: String,
        deliveryId: String,
    ): String {
        val connection = container.connectionStore.activeProfile()
            ?: throw IllegalStateException("No active connection")
        val socketClient = container.clientFactory.webSocketClient().fixedConnectionClient(connection)
        val ptyAuth = container.wsAuthHelper.authQueryParam()
        val eventAuth = container.wsAuthHelper.authQueryParam()
        val channel = "car:$deliveryId"
        val attachToken = "talaria-car:$deliveryId"
        val session = PtyWebSocketSession(
            client = socketClient,
            connectionStore = container.connectionStore,
            wsAuth = container.wsAuthHelper,
            fixedProfile = connection,
            fixedAuthQuery = ptyAuth,
        )
        val eventClient = HermesEventClient(
            clientFactory = container.clientFactory,
            connectionStore = container.connectionStore,
            wsAuth = container.wsAuthHelper,
            profileName = connection.managementProfile,
            fixedProfile = connection,
            fixedAuthQuery = eventAuth,
            fixedWebSocketClient = socketClient,
        )
        try {
            val flow = session.connect(
                resumeSessionId = resumeSessionId,
                channelId = channel,
                attachToken = attachToken,
            )
            eventClient.start(channel, includeRpc = false)
            eventClient.awaitEventsConnected()
            return PtyPromptDelivery.deliver(
                session = session,
                ptyEvents = flow,
                text = text,
                eventClient = eventClient,
            ).sessionKey
        } finally {
            eventClient.dispose()
            session.close()
        }
    }

    private fun decodeSessions(raw: kotlinx.serialization.json.JsonElement): List<SessionSummary> {
        val array: JsonArray? = when (raw) {
            is JsonArray -> raw
            is JsonObject -> raw["sessions"]?.jsonArray ?: raw["results"]?.jsonArray
            else -> null
        }
        return array.orEmpty().mapNotNull { element ->
            runCatching { JsonConfig.json.decodeFromJsonElement<SessionSummary>(element) }.getOrNull()
        }
    }

    private fun isAutomationSource(source: String?): Boolean {
        val src = source.orEmpty().lowercase()
        return listOf("cron", "automat", "webhook").any { src.contains(it) }
    }

    private const val MAX_MESSAGE_REQUESTS = 6
    private const val CONVERSATIONS_TIMEOUT_MS = 10_000L
    private const val PROMPT_TIMEOUT_MS = 20_000L
}
