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


package com.nousresearch.talaria.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.core.data.repo.ChatRepository
import com.nousresearch.talaria.core.network.PtyEvent
import com.nousresearch.talaria.core.network.PtyWebSocketSession
import com.nousresearch.talaria.core.voice.SpeechCoordinator
import com.nousresearch.talaria.core.voice.SttEvent
import com.nousresearch.talaria.core.notifications.TalariaNotifier
import com.nousresearch.talaria.core.voice.TtsSpeaker
import com.nousresearch.talaria.domain.model.ChatLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatUiState(
    val lines: List<ChatLine> = emptyList(),
    val draft: String = "",
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val error: String? = null,
    val listening: Boolean = false,
    val partialDictation: String = "",
)

class ChatViewModel(
    private val chatRepository: ChatRepository = TalariaApp.instance.container.chatRepository,
    private val speech: SpeechCoordinator = TalariaApp.instance.container.speechCoordinator,
    private val tts: TtsSpeaker = TalariaApp.instance.container.ttsSpeaker,
    private val notifier: TalariaNotifier = TalariaApp.instance.container.notifier,
) : ViewModel() {
    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var session: PtyWebSocketSession? = null
    private var collectJob: Job? = null
    private var sttJob: Job? = null
    private var assistantBuffer = StringBuilder()

    init {
        viewModelScope.launch {
            val draft = chatRepository.loadDraft()
            _ui.update { it.copy(draft = draft) }
        }
    }

    fun connect(resume: String? = null) {
        collectJob?.cancel()
        session?.close()
        _ui.update { it.copy(connecting = true, error = null, connected = false) }
        val (pty, flow) = chatRepository.openPty(resume)
        session = pty
        collectJob = viewModelScope.launch {
            flow.collect { event ->
                when (event) {
                    is PtyEvent.Connected -> _ui.update { it.copy(connecting = false, connected = true) }
                    is PtyEvent.Output -> {
                        assistantBuffer.append(event.text)
                        val text = event.text
                        if (text.isNotBlank()) {
                            _ui.update { state ->
                                val last = state.lines.lastOrNull()
                                if (last?.role == "assistant" && last.streaming) {
                                    state.copy(
                                        lines = state.lines.dropLast(1) + last.copy(text = last.text + text),
                                    )
                                } else {
                                    state.copy(
                                        lines = state.lines + ChatLine(
                                            id = UUID.randomUUID().toString(),
                                            role = "assistant",
                                            text = text,
                                            streaming = true,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    is PtyEvent.Closed -> {
                        finalizeAssistant()
                        _ui.update { it.copy(connected = false, connecting = false) }
                    }
                    is PtyEvent.Failure -> {
                        _ui.update { it.copy(error = event.message, connecting = false, connected = false) }
                        notifier.notifyError("Chat disconnected", event.message)
                    }
                }
            }
        }
    }

    private fun finalizeAssistant() {
        val full = assistantBuffer.toString().trim()
        if (full.isNotEmpty()) {
            tts.speak(full)
            notifier.notifyReply("Hermes", full.take(180))
        }
        assistantBuffer = StringBuilder()
        _ui.update { state ->
            state.copy(lines = state.lines.map { if (it.streaming) it.copy(streaming = false) else it })
        }
    }

    fun updateDraft(text: String) {
        _ui.update { it.copy(draft = text) }
        viewModelScope.launch { chatRepository.saveDraft(text) }
    }

    fun send(text: String = _ui.value.draft) {
        val payload = text.trim()
        if (payload.isEmpty()) return
        _ui.update {
            it.copy(
                draft = "",
                lines = it.lines + ChatLine(UUID.randomUUID().toString(), "user", payload),
            )
        }
        assistantBuffer = StringBuilder()
        session?.sendText(payload)
        viewModelScope.launch { chatRepository.saveDraft("") }
    }

    fun toggleListen() {
        if (_ui.value.listening) {
            sttJob?.cancel()
            _ui.update { it.copy(listening = false, partialDictation = "") }
            return
        }
        _ui.update { it.copy(listening = true) }
        sttJob = viewModelScope.launch {
            speech.listen(continuous = true).collect { event ->
                when (event) {
                    is SttEvent.Partial -> _ui.update { it.copy(partialDictation = event.text) }
                    is SttEvent.Final -> {
                        val merged = (_ui.value.draft + " " + event.text).trim()
                        updateDraft(merged)
                        _ui.update { it.copy(partialDictation = "") }
                    }
                    is SttEvent.Error -> _ui.update { it.copy(listening = false, error = event.message) }
                    else -> Unit
                }
            }
        }
    }

    override fun onCleared() {
        sttJob?.cancel()
        collectJob?.cancel()
        session?.close()
        super.onCleared()
    }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel() as T
        }
    }
}
