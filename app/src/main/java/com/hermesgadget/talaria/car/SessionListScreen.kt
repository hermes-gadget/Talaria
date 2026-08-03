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
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarText
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.messaging.model.CarMessage
import androidx.car.app.messaging.model.ConversationCallback
import androidx.car.app.messaging.model.ConversationItem
import androidx.core.app.Person
import com.hermesgadget.talaria.domain.model.SessionSummary
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Car home screen: a voice-driven "New agent" conversation at the top,
 * followed by one conversation per active agent session. Voice replies
 * and read actions are provided natively by the templated messaging
 * framework through [ConversationCallback] — no manual input handling.
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

        // Voice-driven new agent: the framework's reply affordance is the
        // mic — speaking the first prompt creates the session.
        itemList.addItem(
            ConversationItem.Builder(
                NEW_AGENT_ID,
                CarText.create("New agent"),
                selfPerson,
                listOf(
                    CarMessage.Builder()
                        .setSender(hermesPerson)
                        .setBody(CarText.create("Tap the mic and say what you want this agent to do."))
                        .setRead(true)
                        .build(),
                ),
                newAgentCallback(),
            ).build(),
        )

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
            .setRead(true)
            .build()

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
                        error = result.exceptionOrNull()?.message
                        conversations = null // reload — new session appears as a conversation
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
        private const val NEW_AGENT_ID = "talaria-new-agent"
    }
}
