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

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.messaging.model.CarMessage
import androidx.car.app.messaging.model.ConversationCallback
import androidx.car.app.messaging.model.ConversationItem
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.domain.model.SessionSummary
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Car home screen, designed for minimal glances while driving:
 *
 * 1. **Create new agent** — a distinct action entry (plus avatar, voice
 *    via the framework mic). Speaking the first prompt creates the session.
 * 2. **Quick start** — one-tap rows that kick off a fresh agent with a
 *    canned prompt. No dictation needed: a single tap while driving.
 * 3. **Active agent conversations** — one row per live session; voice
 *    replies via [ConversationCallback].
 */
class SessionListScreen(carContext: CarContext) : Screen(carContext) {

    private val executor: Executor = Executors.newSingleThreadExecutor()
    private val mainExecutor: Executor = carContext.mainExecutor

    private var conversations: List<CarConversation>? = null
    private var error: String? = null
    private var loading = false

    private val selfPerson: Person = Person.Builder()
        .setName("Me")
        .setKey("self_id")
        .build()

    private val hermesPerson: Person = Person.Builder()
        .setName("Hermes")
        .setKey("hermes_id")
        .build()

    /** Distinct "+" avatar so the create entry reads as an action, not a chat. */
    private val createPerson: Person = Person.Builder()
        .setName("Me")
        .setKey("create_agent")
        .build()

    private data class QuickStart(val title: String, val prompt: String)

    private val quickStarts = listOf(
        QuickStart(
            "Draft a release note",
            "Draft a release note covering the latest changes.",
        ),
        QuickStart(
            "Summarize my sessions",
            "Summarize my active agent sessions and what each is working on.",
        ),
        QuickStart(
            "Plan today",
            "Plan today's work based on my recent activity.",
        ),
    )

    override fun onGetTemplate(): Template {
        if (conversations == null) {
            if (error == null && !loading) loadConversations()
            return if (error != null) {
                MessageTemplate.Builder(error!!)
                    .setTitle("Talaria")
                    .setHeaderAction(Action.APP_ICON)
                    .addAction(
                        Action.Builder()
                            .setTitle("Retry")
                            .setOnClickListener { error = null; loading = false; invalidate() }
                            .build(),
                    )
                    .build()
            } else {
                MessageTemplate.Builder("Loading your agents…")
                    .setTitle("Talaria")
                    .setHeaderAction(Action.APP_ICON)
                    .build()
            }
        }

        val itemList = ItemList.Builder()

        // 1) Create-agent entry. The framework's reply affordance is the
        //    mic — speaking the first prompt creates the session.
        itemList.addItem(
            ConversationItem.Builder(
                CREATE_AGENT_ID,
                CarText.create("Create new agent"),
                createPerson,
                listOf(
                    CarMessage.Builder()
                        .setSender(hermesPerson)
                        .setBody(CarText.create("Tap the mic and say what you want this agent to do."))
                        .setReceivedTimeEpochMillis(System.currentTimeMillis())
                        .setRead(true)
                        .build(),
                ),
                newAgentCallback(),
            )
                .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_add)).build())
                .build(),
        )

        // 2) Quick-start one-tap actions (no dictation needed while driving).
        quickStarts.forEach { quick ->
            itemList.addItem(
                Row.Builder()
                    .setTitle(quick.title)
                    .setImage(CarIcon.APP_ICON)
                    .setOnClickListener { startQuickAction(quick) }
                    .build(),
            )
        }

        // 3) Active agent conversations.
        conversations.orEmpty().forEach { conversation ->
            val session = conversation.session
            itemList.addItem(
                ConversationItem.Builder(
                    session.id,
                    CarText.create(session.title ?: "Untitled agent"),
                    selfPerson,
                    conversation.messages.map { toCarMessage(it) },
                    sessionCallback(session),
                ).build(),
            )
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.APP_ICON)
                    .setTitle("Talaria agents")
                    .build(),
            )
            .setSingleList(itemList.build())
            .build()
    }

    private fun toCarMessage(message: com.hermesgadget.talaria.domain.model.SessionMessage): CarMessage =
        CarMessage.Builder()
            .setSender(if (message.role == "user") selfPerson else hermesPerson)
            .setBody(CarText.create(message.content.orEmpty().trim().replace('\n', ' ')))
            .setReceivedTimeEpochMillis(message.timestamp?.toEpochMillis() ?: System.currentTimeMillis())
            .setRead(true)
            .build()

    /** Message timestamps arrive as epoch-seconds-double or ISO-8601 — normalize to millis. */
    private fun String.toEpochMillis(): Long? =
        toDoubleOrNull()?.let { d ->
            // Hermes stores epoch SECONDS (1.78e9); the car API wants MILLIS.
            if (d < 1_000_000_000_000L) (d * 1000).toLong() else d.toLong()
        } ?: runCatching { java.time.Instant.parse(this).toEpochMilli() }.getOrNull()

    private fun newAgentCallback(): ConversationCallback =
        object : ConversationCallback {
            override fun onTextReply(text: String) {
                val prompt = text.trim()
                if (prompt.isEmpty()) return
                executor.execute {
                    val result = kotlinx.coroutines.runBlocking {
                        CarSessionsRepository.createSession(prompt)
                    }
                    mainExecutor.execute {
                        result.fold(
                            onSuccess = {
                                CarToast.makeText(
                                    carContext,
                                    "Agent created — it's in your list.",
                                    CarToast.LENGTH_LONG,
                                ).show()
                            },
                            onFailure = { error = it.message ?: "Failed to create agent" },
                        )
                        conversations = null // reload — the new session appears as a conversation
                        invalidate()
                    }
                }
            }

            override fun onMarkAsRead() = Unit
        }

    private fun sessionCallback(session: SessionSummary): ConversationCallback =
        object : ConversationCallback {
            override fun onTextReply(text: String) {
                val prompt = text.trim()
                if (prompt.isEmpty()) return
                executor.execute {
                    val result = kotlinx.coroutines.runBlocking {
                        CarSessionsRepository.sendText(session.id, prompt)
                    }
                    mainExecutor.execute {
                        if (result.isFailure) error = result.exceptionOrNull()?.message
                        conversations = null // reload — new reply shows in the preview
                        invalidate()
                    }
                }
            }

            override fun onMarkAsRead() = Unit
        }

    /** One-tap kick-off: create a fresh agent with a canned prompt. */
    private fun startQuickAction(quick: QuickStart) {
        executor.execute {
            val result = kotlinx.coroutines.runBlocking {
                CarSessionsRepository.createSession(quick.prompt)
            }
            mainExecutor.execute {
                result.fold(
                    onSuccess = {
                        CarToast.makeText(
                            carContext,
                            "Agent started — it's in your list.",
                            CarToast.LENGTH_LONG,
                        ).show()
                    },
                    onFailure = { error = it.message ?: "Failed to start agent" },
                )
                conversations = null
                invalidate()
            }
        }
    }

    private fun loadConversations() {
        loading = true
        invalidate()
        executor.execute {
            val result = kotlinx.coroutines.runBlocking {
                if (!CarSessionsRepository.hasConnection()) {
                    Result.failure(IllegalStateException("Connect Talaria to Hermes on your phone first"))
                } else {
                    CarSessionsRepository.conversations()
                }
            }
            mainExecutor.execute {
                loading = false
                result.fold(
                    onSuccess = { conversations = it },
                    onFailure = { error = it.message ?: "Failed to load sessions" },
                )
                invalidate()
            }
        }
    }

    companion object {
        private const val CREATE_AGENT_ID = "create-agent"
    }
}
