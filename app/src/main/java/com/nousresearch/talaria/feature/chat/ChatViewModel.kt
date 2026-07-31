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
import com.nousresearch.talaria.core.data.repo.HermesRepository
import com.nousresearch.talaria.core.network.HermesEventClient
import com.nousresearch.talaria.core.network.HermesSideEvent
import com.nousresearch.talaria.core.network.PromptKind
import com.nousresearch.talaria.core.network.PtyEvent
import com.nousresearch.talaria.core.network.PtyWebSocketSession
import com.nousresearch.talaria.core.network.ToolCallStatus
import com.nousresearch.talaria.core.notifications.TalariaNotifier
import com.nousresearch.talaria.core.voice.SpeechCoordinator
import com.nousresearch.talaria.core.voice.SttEvent
import com.nousresearch.talaria.core.voice.TtsSpeaker
import com.nousresearch.talaria.domain.model.ChatLine
import com.nousresearch.talaria.domain.model.ModelOption
import com.nousresearch.talaria.domain.model.SessionSummary
import com.nousresearch.talaria.domain.model.SlashCommand
import com.nousresearch.talaria.domain.model.SlashCommands
import com.nousresearch.talaria.domain.model.ToolCallUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class TranscriptMode { TERMINAL, READING }

data class ChatUiState(
    val lines: List<ChatLine> = emptyList(),
    val draft: String = "",
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val error: String? = null,
    val listening: Boolean = false,
    val partialDictation: String = "",
    val modelLabel: String? = null,
    val modelConnected: Boolean? = null,
    val tools: List<ToolCallUi> = emptyList(),
    val sessions: List<SessionSummary> = emptyList(),
    val modelOptions: List<ModelOption> = emptyList(),
    val showSessionRail: Boolean = false,
    val showModelPicker: Boolean = false,
    val showSlashPalette: Boolean = false,
    val slashSuggestions: List<SlashCommand> = emptyList(),
    val prompt: ChatPromptUi? = null,
    val resumeSessionId: String? = null,
    val transcriptMode: TranscriptMode = TranscriptMode.TERMINAL,
    val readingMessages: List<ChatLine> = emptyList(),
    val channelId: String? = null,
)

data class ChatPromptUi(
    val kind: PromptKind,
    val message: String,
)

class ChatViewModel(
    private val chatRepository: ChatRepository = TalariaApp.instance.container.chatRepository,
    private val hermesRepository: HermesRepository = TalariaApp.instance.container.hermesRepository,
    private val eventClient: HermesEventClient = TalariaApp.instance.container.eventClient,
    private val speech: SpeechCoordinator = TalariaApp.instance.container.speechCoordinator,
    private val tts: TtsSpeaker = TalariaApp.instance.container.ttsSpeaker,
    private val notifier: TalariaNotifier = TalariaApp.instance.container.notifier,
) : ViewModel() {
    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var session: PtyWebSocketSession? = null
    private var collectJob: Job? = null
    private var sideJob: Job? = null
    private var sttJob: Job? = null
    private var assistantBuffer = StringBuilder()

    init {
        viewModelScope.launch {
            val draft = chatRepository.loadDraft()
            _ui.update { it.copy(draft = draft) }
        }
        viewModelScope.launch {
            hermesRepository.getModelInfo().onSuccess { info ->
                _ui.update { it.copy(modelLabel = info.model, modelConnected = info.connected) }
            }
        }
    }

    fun connect(resume: String? = null) {
        collectJob?.cancel()
        sideJob?.cancel()
        session?.close()
        eventClient.stop()
        val channel = UUID.randomUUID().toString()
        _ui.update {
            it.copy(
                connecting = true,
                error = null,
                connected = false,
                resumeSessionId = resume,
                channelId = channel,
                tools = emptyList(),
                lines = if (resume != null) it.lines else emptyList(),
            )
        }
        eventClient.start(channel)
        sideJob = viewModelScope.launch {
            eventClient.events.collect { handleSideEvent(it) }
        }
        val (pty, flow) = chatRepository.openPty(resume, channel)
        session = pty
        collectJob = viewModelScope.launch {
            try {
                flow.collect { event ->
                    when (event) {
                        is PtyEvent.Connected -> _ui.update {
                            it.copy(connecting = false, connected = true, channelId = event.channel)
                        }
                        is PtyEvent.Output -> appendAssistant(event.text)
                        is PtyEvent.Closed -> {
                            finalizeAssistant()
                            _ui.update { it.copy(connected = false, connecting = false) }
                        }
                        is PtyEvent.Failure -> {
                            _ui.update {
                                it.copy(error = event.message, connecting = false, connected = false)
                            }
                            notifier.notifyError("Chat disconnected", event.message)
                        }
                    }
                }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        error = t.message ?: "Chat connection failed",
                        connecting = false,
                        connected = false,
                    )
                }
            }
        }
        if (!resume.isNullOrBlank()) loadReading(resume)
        refreshSessions()
    }

    fun newChat() {
        connect(resume = null)
    }

    fun resumeSession(id: String) {
        _ui.update { it.copy(showSessionRail = false) }
        connect(resume = id)
    }

    fun refreshSessions() {
        viewModelScope.launch {
            hermesRepository.refreshSessions().onSuccess { list ->
                _ui.update { it.copy(sessions = list.take(40)) }
            }
        }
    }

    fun toggleSessionRail(show: Boolean = !_ui.value.showSessionRail) {
        _ui.update { it.copy(showSessionRail = show) }
        if (show) refreshSessions()
    }

    fun toggleModelPicker(show: Boolean = !_ui.value.showModelPicker) {
        _ui.update { it.copy(showModelPicker = show) }
        if (show) {
            viewModelScope.launch {
                hermesRepository.getModelOptions().onSuccess { opts ->
                    _ui.update { it.copy(modelOptions = opts) }
                }
            }
        }
    }

    fun selectModel(option: ModelOption) {
        val id = option.id ?: option.name ?: option.label ?: return
        viewModelScope.launch {
            hermesRepository.setModel(id).onSuccess {
                _ui.update { it.copy(modelLabel = id, showModelPicker = false) }
                session?.sendText("/model $id")
            }.onFailure { e ->
                _ui.update { it.copy(error = e.message ?: "Failed to set model") }
            }
        }
    }

    fun setTranscriptMode(mode: TranscriptMode) {
        _ui.update { it.copy(transcriptMode = mode) }
        val resume = _ui.value.resumeSessionId
        if (mode == TranscriptMode.READING && !resume.isNullOrBlank()) loadReading(resume)
    }

    fun updateDraft(text: String) {
        val slash = text.startsWith('/') && !text.contains(' ')
        val suggestions = if (slash) {
            SlashCommands.defaults.filter { it.command.startsWith(text, ignoreCase = true) }
        } else {
            emptyList()
        }
        _ui.update {
            it.copy(
                draft = text,
                showSlashPalette = suggestions.isNotEmpty(),
                slashSuggestions = suggestions,
            )
        }
        viewModelScope.launch { chatRepository.saveDraft(text) }
    }

    fun pickSlash(cmd: SlashCommand) {
        // Bare commands (no trailing args placeholder) send immediately; others insert for editing.
        val needsArgs = cmd.command.trimEnd().endsWith(" ") ||
            (cmd.description?.contains("arg", ignoreCase = true) == true)
        if (!needsArgs && !cmd.command.contains(' ')) {
            _ui.update { it.copy(draft = "", showSlashPalette = false) }
            send(cmd.command)
        } else {
            _ui.update { it.copy(draft = cmd.command.trimEnd() + " ", showSlashPalette = false) }
        }
    }

    fun send(text: String = _ui.value.draft) {
        val payload = text.trim()
        if (payload.isEmpty()) return
        _ui.update {
            it.copy(
                draft = "",
                showSlashPalette = false,
                lines = it.lines + ChatLine(UUID.randomUUID().toString(), "user", payload),
            )
        }
        assistantBuffer = StringBuilder()
        session?.sendText(payload)
        viewModelScope.launch { chatRepository.saveDraft("") }
    }

    fun resizePty(cols: Int, rows: Int) {
        session?.resize(cols.coerceIn(20, 200), rows.coerceIn(10, 80))
    }

    fun respondPrompt(approved: Boolean, text: String? = null) {
        eventClient.respondPrompt(approved, text)
        if (text != null) session?.sendText(text)
        else session?.sendText(if (approved) "y" else "n")
        _ui.update { it.copy(prompt = null) }
    }

    fun dismissPrompt() {
        _ui.update { it.copy(prompt = null) }
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

    private fun loadReading(sessionId: String) {
        viewModelScope.launch {
            hermesRepository.loadMessages(sessionId).onSuccess { msgs ->
                val lines = msgs.mapIndexed { idx, m ->
                    ChatLine(
                        id = "$sessionId-$idx",
                        role = m.role ?: "assistant",
                        text = m.content.orEmpty(),
                    )
                }
                _ui.update { it.copy(readingMessages = lines) }
            }
        }
    }

    private fun appendAssistant(text: String) {
        if (text.isBlank()) return
        assistantBuffer.append(text)
        _ui.update { state ->
            val last = state.lines.lastOrNull()
            if (last?.role == "assistant" && last.streaming) {
                state.copy(lines = state.lines.dropLast(1) + last.copy(text = last.text + text))
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

    private fun handleSideEvent(event: HermesSideEvent) {
        when (event) {
            is HermesSideEvent.Tool -> {
                _ui.update { state ->
                    val existing = state.tools.indexOfFirst { it.id == event.id }
                    val item = ToolCallUi(
                        id = event.id,
                        name = event.name,
                        status = event.status.name,
                        argsPreview = event.argsPreview?.take(240),
                        message = event.message,
                    )
                    val tools = state.tools.toMutableList()
                    if (existing >= 0) tools[existing] = item else tools.add(0, item)
                    state.copy(tools = tools.take(20))
                }
            }
            is HermesSideEvent.Prompt -> {
                _ui.update { it.copy(prompt = ChatPromptUi(event.kind, event.message)) }
            }
            is HermesSideEvent.Model -> {
                _ui.update { it.copy(modelLabel = event.name, modelConnected = event.connected) }
            }
            is HermesSideEvent.TransportError -> {
                // Best-effort: PTY can still work without sidecar.
                if (_ui.value.error == null) {
                    _ui.update { it.copy(error = "Sidecar ${event.socket}: ${event.message}") }
                }
            }
            is HermesSideEvent.Raw -> Unit
        }
    }

    override fun onCleared() {
        sttJob?.cancel()
        collectJob?.cancel()
        sideJob?.cancel()
        session?.close()
        eventClient.stop()
        super.onCleared()
    }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel() as T
        }
    }
}
