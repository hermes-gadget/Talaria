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

/** One running Hermes agent (its own PTY + sidecar), shown as a tab. */
data class ChatTab(
    val id: String,
    val title: String,
    val channelId: String,
    val resumeSessionId: String? = null,
    val liveSessionId: String? = null,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val lines: List<ChatLine> = emptyList(),
    // The in-flight assistant turn lives OUTSIDE `lines`: every PTY chunk used
    // to rebuild the whole list (dropLast + copy) which recomposed the entire
    // transcript at stream rate. Streaming text is one field; it becomes a
    // finished ChatLine only when the turn completes.
    val assistantStreaming: Boolean = false,
    val streamingText: String = "",
    val readingMessages: List<ChatLine> = emptyList(),
    val tools: List<ToolCallUi> = emptyList(),
    val modelLabel: String? = null,
    val modelConnected: Boolean? = null,
    // Live agent status from the sidecar `session.info` frame.
    val provider: String? = null,
    val reasoningEffort: String? = null,
    val approvalMode: String? = null,
    val yolo: Boolean = false,
    // Token/cost accounting when the provider emits it.
    val totalTokens: Long? = null,
    val costUsd: Double? = null,
    val prompt: ChatPromptUi? = null,
    val error: String? = null,
    val draft: String = "",
    val hasSent: Boolean = false,
)

data class ChatUiState(
    val tabs: List<ChatTab> = emptyList(),
    val activeTabId: String? = null,
    val transcriptMode: TranscriptMode = TranscriptMode.READING,
    val listening: Boolean = false,
    val partialDictation: String = "",
    val sessions: List<SessionSummary> = emptyList(),
    val modelOptions: List<ModelOption> = emptyList(),
    val showSessionRail: Boolean = false,
    val showModelPicker: Boolean = false,
    val showSlashPalette: Boolean = false,
    val slashSuggestions: List<SlashCommand> = emptyList(),
) {
    val active: ChatTab? get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.firstOrNull()
}

data class ChatPromptUi(
    val kind: PromptKind,
    val message: String,
)

private class SessionRuntime(
    val session: PtyWebSocketSession,
    val eventClient: HermesEventClient,
    var collectJob: Job? = null,
    var sideJob: Job? = null,
    var readingJob: Job? = null,
    var assistantBuffer: StringBuilder = StringBuilder(),
    var readingSessionId: String? = null,
    // Sessions that already existed when this tab opened; its own session is a
    // NEW id that appears afterwards, which lets concurrent tabs each claim theirs.
    var baselineSessions: Set<String> = emptySet(),
    var baselineReady: Boolean = false,
)

class ChatViewModel(
    private val chatRepository: ChatRepository = TalariaApp.instance.container.chatRepository,
    private val hermesRepository: HermesRepository = TalariaApp.instance.container.hermesRepository,
    private val speech: SpeechCoordinator = TalariaApp.instance.container.speechCoordinator,
    private val tts: TtsSpeaker = TalariaApp.instance.container.ttsSpeaker,
    private val notifier: TalariaNotifier = TalariaApp.instance.container.notifier,
) : ViewModel() {
    private val container = TalariaApp.instance.container
    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private val runtimes = mutableMapOf<String, SessionRuntime>()
    /** Hermes session ids already mapped to a tab, so concurrent tabs don't collide. */
    private val claimedSessions = mutableSetOf<String>()
    private var sessionCounter = 0
    private var sttJob: Job? = null
    private var lastCols = 80
    private var lastRows = 24
    private var initialDraft: String = ""

    init {
        viewModelScope.launch { initialDraft = chatRepository.loadDraft() }
    }

    /** Called by the screen: make sure at least one session exists (optionally resuming). */
    fun ensureStarted(resume: String? = null) {
        if (_ui.value.tabs.isEmpty()) {
            newSession(resume = resume, draft = initialDraft)
        } else if (!resume.isNullOrBlank() && _ui.value.tabs.none { it.resumeSessionId == resume }) {
            newSession(resume = resume)
        }
    }

    /** Open a brand-new concurrent agent in its own tab and focus it. */
    fun newSession(resume: String? = null, titleOverride: String? = null, draft: String = "") {
        val id = UUID.randomUUID().toString()
        val channel = UUID.randomUUID().toString()
        sessionCounter += 1
        val title = titleOverride ?: "Agent $sessionCounter"
        val eventClient = HermesEventClient(
            container.clientFactory,
            container.connectionStore,
            container.wsAuthHelper,
        )
        val (pty, flow) = chatRepository.openPty(resume, channel, lastCols, lastRows)
        val rt = SessionRuntime(session = pty, eventClient = eventClient)
        runtimes[id] = rt

        _ui.update {
            it.copy(
                tabs = it.tabs + ChatTab(
                    id = id,
                    title = title,
                    channelId = channel,
                    resumeSessionId = resume,
                    liveSessionId = resume,
                    connecting = true,
                    draft = draft,
                ),
                activeTabId = id,
            )
        }
        resume?.let { claimedSessions.add(it) }

        viewModelScope.launch {
            hermesRepository.getModelInfo().onSuccess { info ->
                updateTab(id) { it.copy(modelLabel = info.model, modelConnected = info.connected) }
            }
        }

        eventClient.start(channel)
        rt.sideJob = viewModelScope.launch {
            eventClient.events.collect { handleSideEvent(id, it) }
        }
        rt.collectJob = viewModelScope.launch {
            try {
                flow.collect { event -> handlePtyEvent(id, event) }
            } catch (t: Throwable) {
                updateTab(id) {
                    it.copy(error = t.message ?: "Chat connection failed", connecting = false, connected = false)
                }
            }
        }
        // Snapshot existing sessions so this tab only claims the new one it creates.
        viewModelScope.launch {
            val list = hermesRepository.refreshSessions().getOrNull().orEmpty()
            rt.baselineSessions = list.map { it.id }.toSet()
            rt.baselineReady = true
            _ui.update { it.copy(sessions = list.take(40)) }
        }
        if (!resume.isNullOrBlank()) loadReading(id, resume)
        startReadingPoll(id)
    }

    fun switchTab(tabId: String) {
        _ui.update { it.copy(activeTabId = tabId) }
    }

    fun closeTab(tabId: String) {
        val rt = runtimes.remove(tabId)
        rt?.collectJob?.cancel()
        rt?.sideJob?.cancel()
        rt?.readingJob?.cancel()
        rt?.session?.close()
        rt?.eventClient?.dispose()
        _ui.value.tabs.firstOrNull { it.id == tabId }?.liveSessionId?.let { claimedSessions.remove(it) }
        _ui.update { state ->
            val remaining = state.tabs.filterNot { it.id == tabId }
            state.copy(
                tabs = remaining,
                activeTabId = if (state.activeTabId == tabId) remaining.lastOrNull()?.id else state.activeTabId,
            )
        }
        // Never leave the user on an empty Chats tab.
        if (_ui.value.tabs.isEmpty()) newSession()
    }

    fun resumeSession(id: String) {
        _ui.update { it.copy(showSessionRail = false) }
        val existing = _ui.value.tabs.firstOrNull { it.resumeSessionId == id || it.liveSessionId == id }
        if (existing != null) switchTab(existing.id) else newSession(resume = id)
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
        val modelId = option.id ?: option.name ?: option.label ?: return
        val tabId = _ui.value.active?.id ?: return
        viewModelScope.launch {
            hermesRepository.setModel(modelId).onSuccess {
                updateTab(tabId) { it.copy(modelLabel = modelId) }
                _ui.update { it.copy(showModelPicker = false) }
                runtimes[tabId]?.session?.sendText("/model $modelId")
            }.onFailure { e ->
                updateTab(tabId) { it.copy(error = e.message ?: "Failed to set model") }
            }
        }
    }

    fun setTranscriptMode(mode: TranscriptMode) {
        _ui.update { it.copy(transcriptMode = mode) }
        val tab = _ui.value.active ?: return
        val resume = tab.liveSessionId ?: tab.resumeSessionId
        if (mode == TranscriptMode.READING && !resume.isNullOrBlank()) loadReading(tab.id, resume)
    }

    fun updateDraft(text: String) {
        val tabId = _ui.value.active?.id ?: return
        val slash = text.startsWith('/') && !text.contains(' ')
        val suggestions = if (slash) {
            SlashCommands.defaults.filter { it.command.startsWith(text, ignoreCase = true) }
        } else {
            emptyList()
        }
        updateTab(tabId) { it.copy(draft = text) }
        _ui.update {
            it.copy(showSlashPalette = suggestions.isNotEmpty(), slashSuggestions = suggestions)
        }
        viewModelScope.launch { chatRepository.saveDraft(text) }
    }

    fun pickSlash(cmd: SlashCommand) {
        val needsArgs = cmd.command.trimEnd().endsWith(" ") ||
            (cmd.description?.contains("arg", ignoreCase = true) == true)
        val tabId = _ui.value.active?.id ?: return
        if (!needsArgs && !cmd.command.contains(' ')) {
            updateTab(tabId) { it.copy(draft = "") }
            _ui.update { it.copy(showSlashPalette = false) }
            send(cmd.command)
        } else {
            updateTab(tabId) { it.copy(draft = cmd.command.trimEnd() + " ") }
            _ui.update { it.copy(showSlashPalette = false) }
        }
    }

    fun send(text: String = _ui.value.active?.draft.orEmpty()) {
        val payload = text.trim()
        if (payload.isEmpty()) return
        val tabId = _ui.value.active?.id ?: return
        val rt = runtimes[tabId] ?: return
        val userLine = ChatLine(UUID.randomUUID().toString(), "user", payload)
        updateTab(tabId) {
            it.copy(
                draft = "",
                lines = it.lines + userLine,
                readingMessages = it.readingMessages + userLine,
                hasSent = true,
            )
        }
        _ui.update { it.copy(showSlashPalette = false) }
        rt.assistantBuffer = StringBuilder()
        rt.session.sendText(payload)
        viewModelScope.launch { chatRepository.saveDraft("") }
    }

    fun resizePty(cols: Int, rows: Int) {
        lastCols = cols.coerceIn(20, 200)
        lastRows = rows.coerceIn(10, 80)
        runtimes.values.forEach { it.session.resize(lastCols, lastRows) }
    }

    fun respondPrompt(approved: Boolean, text: String? = null) {
        val tabId = _ui.value.active?.id ?: return
        val rt = runtimes[tabId] ?: return
        rt.eventClient.respondPrompt(approved, text)
        if (text != null) rt.session.sendText(text) else rt.session.sendText(if (approved) "y" else "n")
        updateTab(tabId) { it.copy(prompt = null) }
    }

    fun dismissPrompt() {
        val tabId = _ui.value.active?.id ?: return
        updateTab(tabId) { it.copy(prompt = null) }
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
                        val merged = (_ui.value.active?.draft.orEmpty() + " " + event.text).trim()
                        updateDraft(merged)
                        _ui.update { it.copy(partialDictation = "") }
                    }
                    is SttEvent.Error -> {
                        _ui.update { it.copy(listening = false) }
                        _ui.value.active?.id?.let { id -> updateTab(id) { t -> t.copy(error = event.message) } }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun updateTab(tabId: String, transform: (ChatTab) -> ChatTab) {
        _ui.update { state ->
            state.copy(tabs = state.tabs.map { if (it.id == tabId) transform(it) else it })
        }
    }

    private fun handlePtyEvent(tabId: String, event: PtyEvent) {
        when (event) {
            is PtyEvent.Connected -> updateTab(tabId) { it.copy(connecting = false, connected = true) }
            is PtyEvent.Output -> appendAssistant(tabId, event.text)
            is PtyEvent.Closed -> {
                finalizeAssistant(tabId)
                updateTab(tabId) { it.copy(connected = false, connecting = false) }
            }
            is PtyEvent.Failure -> {
                updateTab(tabId) { it.copy(error = event.message, connecting = false, connected = false) }
                notifier.notifyError("Chat disconnected", event.message)
            }
        }
    }

    /** Reading mode = clean transcript from the sessions REST API, per tab. */
    private fun startReadingPoll(tabId: String) {
        val rt = runtimes[tabId] ?: return
        rt.readingJob?.cancel()
        rt.readingJob = viewModelScope.launch {
            while (runtimes.containsKey(tabId)) {
                val id = discoverSessionForTab(tabId)
                if (id != null) {
                    if (id != _ui.value.tabs.firstOrNull { it.id == tabId }?.liveSessionId) {
                        updateTab(tabId) { it.copy(liveSessionId = id) }
                    }
                    loadReading(tabId, id)
                }
                kotlinx.coroutines.delay(2500)
            }
        }
    }

    /**
     * Map a tab to its Hermes session. Resumed tabs know it up front; new tabs
     * claim the most-recent session not already owned by another tab (so several
     * concurrent agents each read their own transcript).
     */
    private suspend fun discoverSessionForTab(tabId: String): String? {
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId } ?: return null
        tab.resumeSessionId?.let { return it }
        tab.liveSessionId?.let { return it }
        val rt = runtimes[tabId] ?: return null
        if (!tab.hasSent || !rt.baselineReady) return null
        val list = hermesRepository.refreshSessions().getOrNull().orEmpty()
        // This tab's session is one that appeared AFTER it opened and isn't owned
        // by another tab — so several concurrent agents each map to their own.
        val candidate = list
            .filter { it.id !in claimedSessions && it.id !in rt.baselineSessions }
            .maxByOrNull { it.last_active ?: it.started_at ?: "" }
            ?: return null
        claimedSessions.add(candidate.id)
        return candidate.id
    }

    private fun loadReading(tabId: String, sessionId: String) {
        viewModelScope.launch {
            hermesRepository.loadMessages(sessionId).onSuccess { msgs ->
                val lines = msgs.mapIndexed { idx, m ->
                    ChatLine(id = "$sessionId-$idx", role = m.role ?: "assistant", text = m.content.orEmpty())
                }.filter { it.text.isNotBlank() }
                val rt = runtimes[tabId] ?: return@onSuccess
                updateTab(tabId) { tab ->
                    // Never let a transient/empty server read wipe optimistic messages;
                    // only replace when the server transcript is a superset of what we show.
                    // Equality guard: the 2.5s poll must not churn a full recomposition
                    // when nothing actually changed.
                    if (lines.size > tab.readingMessages.size || lines != tab.readingMessages) {
                        rt.readingSessionId = sessionId
                        tab.copy(readingMessages = lines)
                    } else {
                        tab
                    }
                }
            }
        }
    }

    private fun appendAssistant(tabId: String, text: String) {
        if (text.isBlank()) return
        val rt = runtimes[tabId] ?: return
        rt.assistantBuffer.append(text)
        updateTab(tabId) { tab ->
            tab.copy(
                assistantStreaming = true,
                streamingText = rt.assistantBuffer.toString(),
            )
        }
    }

    private fun finalizeAssistant(tabId: String) {
        val rt = runtimes[tabId] ?: return
        val full = rt.assistantBuffer.toString().trim()
        if (full.isNotEmpty()) {
            tts.speak(full)
            notifier.notifyReply("Hermes", full.take(180))
        }
        rt.assistantBuffer = StringBuilder()
        updateTab(tabId) { tab ->
            if (!tab.assistantStreaming) return@updateTab tab
            tab.copy(
                assistantStreaming = false,
                streamingText = "",
                lines = if (full.isNotEmpty()) {
                    tab.lines + ChatLine(
                        id = UUID.randomUUID().toString(),
                        role = "assistant",
                        text = full,
                    )
                } else {
                    tab.lines
                },
            )
        }
    }

    private fun handleSideEvent(tabId: String, event: HermesSideEvent) {
        when (event) {
            is HermesSideEvent.Tool -> {
                updateTab(tabId) { tab ->
                    val existing = tab.tools.indexOfFirst { it.id == event.id }
                    val item = ToolCallUi(
                        id = event.id,
                        name = event.name,
                        status = event.status.name,
                        argsPreview = event.argsPreview?.take(240),
                        message = event.message,
                    )
                    val tools = tab.tools.toMutableList()
                    if (existing >= 0) tools[existing] = item else tools.add(0, item)
                    tab.copy(tools = tools.take(20))
                }
            }
            is HermesSideEvent.Prompt -> updateTab(tabId) {
                it.copy(prompt = ChatPromptUi(event.kind, event.message))
            }
            is HermesSideEvent.Model -> updateTab(tabId) {
                it.copy(modelLabel = event.name, modelConnected = event.connected)
            }
            is HermesSideEvent.SessionInfo -> updateTab(tabId) {
                it.copy(
                    modelLabel = event.model ?: it.modelLabel,
                    modelConnected = it.modelConnected ?: true,
                    provider = event.provider ?: it.provider,
                    reasoningEffort = event.reasoningEffort ?: it.reasoningEffort,
                    approvalMode = event.approvalMode ?: it.approvalMode,
                    yolo = event.yolo ?: it.yolo,
                )
            }
            is HermesSideEvent.Usage -> updateTab(tabId) {
                it.copy(
                    totalTokens = event.totalTokens ?: it.totalTokens,
                    costUsd = event.costUsd ?: it.costUsd,
                )
            }
            is HermesSideEvent.TransportError -> updateTab(tabId) {
                if (it.error == null) it.copy(error = "Sidecar ${event.socket}: ${event.message}") else it
            }
            is HermesSideEvent.Raw -> Unit
        }
    }

    override fun onCleared() {
        sttJob?.cancel()
        runtimes.values.forEach {
            it.collectJob?.cancel()
            it.sideJob?.cancel()
            it.readingJob?.cancel()
            it.session.close()
            it.eventClient.dispose()
        }
        runtimes.clear()
        super.onCleared()
    }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel() as T
        }
    }
}
