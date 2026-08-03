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
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.core.network.PtyEvent
import com.hermesgadget.talaria.domain.model.HERMES_DEFAULT_PROFILE
import com.hermesgadget.talaria.domain.model.SessionMessage
import com.hermesgadget.talaria.domain.model.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    suspend fun conversations(): Result<List<CarConversation>> = runCatching {
        val active = activeSessions().getOrThrow()
        active.map { conversation ->
            CarConversation(
                session = conversation,
                messages = messages(conversation.id).getOrDefault(emptyList()).takeLast(5),
            )
        }
    }

    /**
     * Sessions currently open on the server: no end marker (not reset /
     * closed / compressed) and not an automation (cron/webhook) source.
     */
    suspend fun activeSessions(): Result<List<SessionSummary>> = runCatching {
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
        sessions.filter { s ->
            s.end_reason == null && s.ended_at == null &&
                !isAutomationSource(s.source)
        }
    }

    suspend fun messages(sessionId: String): Result<List<SessionMessage>> = runCatching {
        val connection = container.connectionStore.activeProfile() ?: return Result.failure(
            IllegalStateException("No active connection"),
        )
        val response = container.clientFactory.api().getSessionMessages(
            id = sessionId,
            profile = connection.managementProfile,
        )
        response.messages.filter { !it.content.isNullOrBlank() }
    }

    /**
     * Send a prompt to an existing session via a short-lived PTY, exactly
     * like the notification ReplyWorker does.
     */
    suspend fun sendText(sessionId: String, text: String): Result<Unit> =
        runCatching { withTimeout(20_000) { ptySend(resumeSessionId = sessionId, text = text) } }

    /**
     * Create a brand-new agent session and send the first prompt.
     * Returns the new session id (or null if the handshake predates it).
     */
    suspend fun createSession(prompt: String): Result<String> =
        runCatching { withTimeout(20_000) { ptySend(resumeSessionId = null, text = prompt) } }

    /**
     * Opens a PTY, waits for the TUI banner, sends the text, closes the socket.
     * The keep-alive registry (attach token) keeps the TUI process running after
     * the socket closes, so the agent's run completes server-side; the registry
     * reaper closes the PTY after its TTL.
     */
    private suspend fun ptySend(resumeSessionId: String?, text: String): String {
        val attachToken = UUID.randomUUID().toString()
        val (session, flow) = container.chatRepository.openPty(
            resumeSessionId = resumeSessionId,
            attachToken = attachToken,
        )
        var sessionKey = ""
        var sent = false
        try {
            flow.collect { event ->
                when (event) {
                    is PtyEvent.Connected -> sessionKey = event.sessionKey
                    is PtyEvent.Output -> {
                        if (!sent && sessionKey.isNotEmpty()) {
                            sent = true
                            // The Hermes TUI is a fresh process per PTY; the first
                            // output frame is its banner, meaning it has mounted
                            // and is reading stdin. Sending any earlier races its
                            // startup and the prompt is silently dropped. Brief
                            // settle beat after the first render, then send.
                            delay(350)
                            session.sendText(text)
                            session.close()
                            throw PtySendDone
                        }
                    }
                    is PtyEvent.Failure -> throw IllegalStateException(event.message)
                    is PtyEvent.Closed ->
                        if (sessionKey.isEmpty()) throw IllegalStateException("Chat closed before send (${event.code})")
                    else -> Unit
                }
            }
        } catch (_: PtySendDone) {
            // Sent; fall through to return. MUST precede the CancellationException
            // catch — PtySendDone IS a CancellationException, so the generic
            // catch would swallow it and report a null-message failure.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            session.close()
        }
        return sessionKey
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

    private object PtySendDone : kotlinx.coroutines.CancellationException()
}
