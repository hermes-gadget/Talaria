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

package com.hermesgadget.talaria.feature.chat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.prefs.PersistedChatState
import com.hermesgadget.talaria.core.data.prefs.PersistedChatTab
import com.hermesgadget.talaria.core.data.prefs.PersistedAgentWatch
import com.hermesgadget.talaria.core.data.repo.ChatRepository
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.core.network.HermesEventClient
import com.hermesgadget.talaria.core.network.HermesSideEvent
import com.hermesgadget.talaria.core.network.PromptKind
import com.hermesgadget.talaria.core.network.PtyEvent
import com.hermesgadget.talaria.core.network.PtyWebSocketSession
import com.hermesgadget.talaria.core.notifications.AgentTaskNotificationService
import com.hermesgadget.talaria.core.notifications.AgentThreadIdentity
import com.hermesgadget.talaria.core.voice.SpeechCoordinator
import com.hermesgadget.talaria.core.voice.SttEvent
import com.hermesgadget.talaria.core.voice.TtsSpeaker
import com.hermesgadget.talaria.domain.model.ChatLine
import com.hermesgadget.talaria.domain.model.ModelOption
import com.hermesgadget.talaria.domain.model.SessionSummary
import com.hermesgadget.talaria.domain.model.SlashArgumentMode
import com.hermesgadget.talaria.domain.model.SlashCommand
import com.hermesgadget.talaria.domain.model.SlashCommands
import com.hermesgadget.talaria.domain.model.ToolCallUi
import com.hermesgadget.talaria.domain.model.scopeId
import com.hermesgadget.talaria.domain.model.effectiveManagementProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.UUID

enum class TranscriptMode { TERMINAL, READING }

enum class ChatImageAttachmentStatus { READY, UPLOADING, ATTACHED, ERROR }

data class ChatImageAttachmentUi(
    val id: String,
    val filename: String,
    val sizeBytes: Int,
    val status: ChatImageAttachmentStatus = ChatImageAttachmentStatus.READY,
    val error: String? = null,
)

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
    // True from when the user sends until the assistant's reply lands. Drives the
    // single "working · <current tool>" indicator (reading mode) instead of the
    // raw TUI. Cleared when a new assistant message arrives (or on close/error).
    val working: Boolean = false,
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
    val imageAttachments: List<ChatImageAttachmentUi> = emptyList(),
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
    val requestId: String? = null,
    val choices: List<String> = emptyList(),
)

private class SessionRuntime(
    val session: PtyWebSocketSession,
    val eventClient: HermesEventClient,
    var collectJob: Job? = null,
    var sideJob: Job? = null,
    var readingJob: Job? = null,
    var assistantBuffer: StringBuilder = StringBuilder(),
    var sidecarAssistantBuffer: StringBuilder = StringBuilder(),
    var readingSessionId: String? = null,
    // Sessions that already existed when this tab opened; its own session is a
    // NEW id that appears afterwards, which lets concurrent tabs each claim theirs.
    var baselineSessions: Set<String> = emptySet(),
    var baselineReady: Boolean = false,
)

private data class PendingChatImage(
    val image: ValidatedChatImage,
    var attachedSessionId: String? = null,
)

class ChatViewModel(
    private val chatRepository: ChatRepository = TalariaApp.instance.container.chatRepository,
    private val hermesRepository: HermesRepository = TalariaApp.instance.container.hermesRepository,
    private val speech: SpeechCoordinator = TalariaApp.instance.container.speechCoordinator,
    private val tts: TtsSpeaker = TalariaApp.instance.container.ttsSpeaker,
) : ViewModel() {
    private val container = TalariaApp.instance.container
    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private val runtimes = mutableMapOf<String, SessionRuntime>()
    /** Hermes session ids already mapped to a tab, so concurrent tabs don't collide. */
    private val claimedSessions = mutableSetOf<String>()
    private var sessionCounter = 0
    // Suppresses per-tab persistence while restorePersistedTabs() rebuilds the
    // surface, so we don't write half-restored snapshots; we persist once at the end.
    private var restoring = false
    private var sttJob: Job? = null
    private var slashCompletionJob: Job? = null
    private var scopeLoadJob: Job? = null
    private var lastCols = 80
    private var lastRows = 24
    private var initialDraft: String = ""
    private var slashCatalog: List<SlashCommand> = SlashCommands.defaults
    private var slashRequestGeneration: Long = 0
    private var boundConnectionScope: String? = null
    private var loadingConnectionScope = false
    /** Raw picker bytes stay outside StateFlow so Compose never copies or compares them. */
    private val pendingImages = mutableMapOf<String, LinkedHashMap<String, PendingChatImage>>()

    /** Called by the screen: make sure at least one session exists (optionally resuming). */
    fun ensureStarted(resume: String? = null) {
        val scopeId = container.connectionStore.activeProfile()?.scopeId() ?: return
        if (boundConnectionScope != scopeId) {
            resetForConnectionScope(scopeId)
            loadingConnectionScope = true
            scopeLoadJob = viewModelScope.launch {
                val restored = chatRepository.loadDraft()
                if (boundConnectionScope != scopeId) return@launch
                initialDraft = restored
                loadingConnectionScope = false
                if (!resume.isNullOrBlank()) {
                    newSession(resume = resume, draft = restored)
                } else {
                    restorePersistedTabs()
                }
            }
            return
        }
        if (loadingConnectionScope) return
        if (_ui.value.tabs.isEmpty()) {
            if (!resume.isNullOrBlank()) {
                newSession(resume = resume, draft = initialDraft)
            } else {
                // Cold start (a force-close / process kill wipes the in-memory tabs):
                // restore every persisted thread with its title and resume its
                // session, instead of opening a single blank new agent.
                restorePersistedTabs()
            }
        } else if (!resume.isNullOrBlank() && _ui.value.tabs.none { it.resumeSessionId == resume }) {
            newSession(resume = resume)
        }
        // Soft-background / process pause often drops the PTY while the ViewModel
        // (and its dead tabs) survive — reopen those sockets on the next start.
        reconnectDisconnected()
    }

    /** Tear down every socket and transient byte buffer before binding another Hermes home. */
    private fun resetForConnectionScope(scopeId: String) {
        scopeLoadJob?.cancel()
        sttJob?.cancel()
        slashCompletionJob?.cancel()
        runtimes.values.forEach {
            it.collectJob?.cancel()
            it.sideJob?.cancel()
            it.readingJob?.cancel()
            it.session.close()
            it.eventClient.dispose()
        }
        _ui.value.tabs.forEach { AgentTaskNotificationService.stopWatching(TalariaApp.instance, it.id) }
        runtimes.clear()
        claimedSessions.clear()
        pendingImages.clear()
        slashCatalog = SlashCommands.defaults
        slashRequestGeneration += 1
        initialDraft = ""
        _ui.value = ChatUiState()
        boundConnectionScope = scopeId
    }

    /** Rebuild the tab list saved for this profile; falls back to one fresh agent. */
    private fun restorePersistedTabs() {
        val pid = container.connectionStore.activeProfile()?.scopeId()
        val saved = pid?.let { container.settingsStore.loadChatState(it) } ?: PersistedChatState()
        if (saved.tabs.isEmpty()) {
            newSession(draft = initialDraft)
            return
        }
        restoring = true
        saved.tabs.forEachIndexed { index, t ->
            newSession(
                resume = t.sessionId,
                titleOverride = t.title,
                // Only the first restored tab inherits the saved composer draft.
                draft = if (index == 0) initialDraft else "",
            )
        }
        // Restore focus to the tab the user was last on.
        val activeId = _ui.value.tabs.firstOrNull {
            it.resumeSessionId != null && it.resumeSessionId == saved.activeSessionId
        }?.id
        if (activeId != null) _ui.update { it.copy(activeTabId = activeId) }
        restoring = false
        persistChatState()
    }

    /**
     * Snapshot the whole Chat surface (open tabs, their titles, the focused tab)
     * so a cold start can rebuild it. Called after any tab add/remove/rename/switch
     * and when a tab claims its Hermes session id.
     */
    private fun persistChatState() {
        if (restoring) return
        val pid = container.connectionStore.activeProfile()?.scopeId() ?: return
        val tabs = _ui.value.tabs.map { t ->
            PersistedChatTab(sessionId = t.liveSessionId ?: t.resumeSessionId, title = t.title)
        }
        container.settingsStore.saveChatState(
            pid,
            PersistedChatState(
                tabs = tabs,
                activeSessionId = _ui.value.active?.let { it.liveSessionId ?: it.resumeSessionId },
            ),
        )
    }

    /**
     * Re-open PTY + sidecar for any tab that is neither connected nor mid-connect.
     * Prefer resuming the Hermes session id we already claimed so the agent
     * continues instead of spawning an orphaned new TUI.
     */
    fun reconnectDisconnected() {
        _ui.value.tabs
            .filter { !it.connected && !it.connecting }
            .forEach { reconnectTab(it.id) }
    }

    fun reconnectTab(tabId: String) {
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.connected || tab.connecting) return

        val old = runtimes.remove(tabId)
        old?.collectJob?.cancel()
        old?.sideJob?.cancel()
        old?.readingJob?.cancel()
        old?.session?.close()
        old?.eventClient?.dispose()

        val resume = tab.liveSessionId ?: tab.resumeSessionId
        // A re-created gateway session may share the durable id but not its
        // in-memory image queue. Force any unsent images through attach_bytes again.
        pendingImages[tabId]?.values?.forEach { it.attachedSessionId = null }
        val channel = UUID.randomUUID().toString()
        val eventClient = HermesEventClient(
            container.clientFactory,
            container.connectionStore,
            container.wsAuthHelper,
        )
        val (pty, flow) = chatRepository.openPty(resume, channel, lastCols, lastRows)
        val rt = SessionRuntime(session = pty, eventClient = eventClient)
        // Keep baseline from the prior runtime when present so we don't reclaim
        // unrelated sessions that appeared while we were disconnected.
        if (old != null) {
            rt.baselineSessions = old.baselineSessions
            rt.baselineReady = old.baselineReady
            rt.readingSessionId = old.readingSessionId
        }
        runtimes[tabId] = rt

        updateTab(tabId) {
            it.copy(
                channelId = channel,
                connecting = true,
                connected = false,
                error = null,
                working = false,
                imageAttachments = it.imageAttachments.map { image ->
                    image.copy(status = ChatImageAttachmentStatus.READY, error = null)
                },
            )
        }

        eventClient.start(channel)
        rt.sideJob = viewModelScope.launch {
            eventClient.events.collect { handleSideEvent(tabId, it) }
        }
        rt.collectJob = viewModelScope.launch {
            try {
                flow.collect { event -> handlePtyEvent(tabId, event) }
            } catch (t: Throwable) {
                updateTab(tabId) {
                    it.copy(error = t.message ?: "Chat connection failed", connecting = false, connected = false)
                }
            }
        }
        if (old == null || !rt.baselineReady) {
            viewModelScope.launch {
                val list = hermesRepository.refreshSessions().getOrNull().orEmpty()
                rt.baselineSessions = list.map { it.id }.toSet()
                rt.baselineReady = true
                _ui.update { it.copy(sessions = list.take(40)) }
            }
        }
        if (!resume.isNullOrBlank()) loadReading(tabId, resume)
        startReadingPoll(tabId)
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
        persistChatState()
    }

    fun switchTab(tabId: String) {
        _ui.update { it.copy(activeTabId = tabId) }
        persistChatState()
    }

    fun closeTab(tabId: String) {
        AgentTaskNotificationService.stopWatching(TalariaApp.instance, tabId)
        pendingImages.remove(tabId)
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
        if (_ui.value.tabs.isEmpty()) newSession() else persistChatState()
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
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId } ?: return
        val sessionId = tab.liveSessionId ?: tab.resumeSessionId
        val runtime = runtimes[tabId] ?: return
        _ui.update { it.copy(showModelPicker = false) }
        if (sessionId == null) {
            // Before the gateway has assigned a session id, the PTY command is
            // the only session-scoped path available.
            runtime.session.sendText("/model $modelId")
            updateTab(tabId) { it.copy(modelLabel = modelId) }
            return
        }
        runtime.eventClient.sendRpc(
            "config.set",
            buildJsonObject {
                put("key", "model")
                put("value", modelId)
                put("session_id", sessionId)
            },
        ) { result ->
            val obj = result as? JsonObject
            when {
                obj == null -> updateTab(tabId) { it.copy(error = "Hermes did not accept the model change") }
                obj["confirm_required"]?.jsonPrimitive?.booleanOrNull == true -> updateTab(tabId) {
                    it.copy(error = obj["confirm_message"]?.jsonPrimitive?.contentOrNull
                        ?: "This model requires confirmation; use /model to review it")
                }
                else -> updateTab(tabId) {
                    it.copy(
                        modelLabel = obj["value"]?.jsonPrimitive?.contentOrNull ?: modelId,
                        error = null,
                    )
                }
            }
        }
    }

    fun setTranscriptMode(mode: TranscriptMode) {
        if (mode == TranscriptMode.TERMINAL && _ui.value.active?.working == true) return
        _ui.update { it.copy(transcriptMode = mode) }
        val tab = _ui.value.active ?: return
        val resume = tab.liveSessionId ?: tab.resumeSessionId
        if (mode == TranscriptMode.READING && !resume.isNullOrBlank()) loadReading(tab.id, resume)
    }

    fun updateDraft(text: String) {
        val tabId = _ui.value.active?.id ?: return
        val slash = text.startsWith('/')
        val suggestions = SlashCommands.suggest(text, slashCatalog)
        updateTab(tabId) { it.copy(draft = text) }
        _ui.update {
            it.copy(showSlashPalette = suggestions.isNotEmpty(), slashSuggestions = suggestions)
        }
        viewModelScope.launch { chatRepository.saveDraft(text) }

        slashCompletionJob?.cancel()
        val generation = ++slashRequestGeneration
        if (!slash) return
        slashCompletionJob = viewModelScope.launch {
            kotlinx.coroutines.delay(120)
            runtimes[tabId]?.eventClient?.requestSlashCompletions(text) { completions ->
                if (generation != slashRequestGeneration) return@requestSlashCompletions
                val active = _ui.value.active
                if (active?.id != tabId || active.draft != text || completions.isEmpty()) {
                    return@requestSlashCompletions
                }
                val remote = completions.asSequence().map { completion ->
                    val replacement = completion.replacement.trimEnd()
                    val token = replacement.substringBefore(' ')
                    val known = slashCatalog.firstOrNull { it.command.equals(token, ignoreCase = true) }
                    SlashCommand(
                        command = replacement,
                        description = completion.description.ifBlank { known?.description ?: "Hermes command" },
                        category = known?.category ?: if (completion.kind == "skill") "Skills" else "Commands",
                        aliases = known?.aliases.orEmpty(),
                        argumentMode = known?.argumentMode ?: if (
                            completion.replacement.endsWith(' ') || replacement.contains(' ')
                        ) {
                            SlashArgumentMode.MIXED
                        } else {
                            SlashArgumentMode.NONE
                        },
                    )
                }.distinctBy { it.command.lowercase() }.take(12).toList()
                _ui.update { it.copy(showSlashPalette = true, slashSuggestions = remote) }
            }
        }
    }

    fun pickSlash(cmd: SlashCommand) {
        val tabId = _ui.value.active?.id ?: return
        val command = cmd.command.trimEnd()
        val replacement = if (
            cmd.argumentMode != SlashArgumentMode.NONE && !command.contains(' ')
        ) "$command " else command
        slashRequestGeneration += 1
        slashCompletionJob?.cancel()
        updateTab(tabId) { it.copy(draft = replacement) }
        _ui.update { it.copy(showSlashPalette = false, slashSuggestions = emptyList()) }
        viewModelScope.launch { chatRepository.saveDraft(replacement) }
    }

    /** Read and validate a picker URI off the main thread, scoped to the tab that opened the picker. */
    fun attachImage(uri: Uri) {
        val tabId = _ui.value.active?.id ?: return
        viewModelScope.launch {
            val selected = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = TalariaApp.instance.contentResolver
                    val displayName = resolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment
                    val bytes = resolver.openInputStream(uri)?.use(ChatImageAttachments::readCapped)
                        ?: throw IllegalArgumentException("Could not read the selected image")
                    ChatImageAttachments.validate(bytes, displayName, resolver.getType(uri))
                }
            }
            selected.onSuccess { image ->
                if (_ui.value.tabs.none { it.id == tabId }) return@onSuccess
                val existingBytes = pendingImages[tabId]?.values?.sumOf { it.image.bytes.size } ?: 0
                if (existingBytes + image.bytes.size > ChatImageAttachments.MAX_BYTES) {
                    updateTab(tabId) {
                        it.copy(error = "Selected images exceed the 25 MB attachment limit")
                    }
                    return@onSuccess
                }
                val id = UUID.randomUUID().toString()
                pendingImages.getOrPut(tabId, ::linkedMapOf)[id] = PendingChatImage(image)
                updateTab(tabId) {
                    it.copy(
                        imageAttachments = it.imageAttachments + ChatImageAttachmentUi(
                            id = id,
                            filename = image.filename,
                            sizeBytes = image.bytes.size,
                        ),
                        error = null,
                    )
                }
            }.onFailure { failure ->
                updateTab(tabId) { it.copy(error = failure.message ?: "Could not attach image") }
            }
        }
    }

    fun removeImageAttachment(id: String) {
        val tab = _ui.value.active ?: return
        val attachment = tab.imageAttachments.firstOrNull { it.id == id } ?: return
        val pending = pendingImages[tab.id]?.get(id)
        val sessionId = tab.liveSessionId ?: tab.resumeSessionId
        if (attachment.status == ChatImageAttachmentStatus.UPLOADING || pending?.attachedSessionId == sessionId) {
            updateTab(tab.id) {
                it.copy(error = "This image is already staged for the current turn")
            }
            return
        }
        pendingImages[tab.id]?.remove(id)
        if (pendingImages[tab.id].isNullOrEmpty()) pendingImages.remove(tab.id)
        updateTab(tab.id) {
            it.copy(imageAttachments = it.imageAttachments.filterNot { image -> image.id == id }, error = null)
        }
    }

    fun send(text: String = _ui.value.active?.draft.orEmpty()) {
        val payload = text.trim()
        val tab = _ui.value.active ?: return
        val tabId = tab.id
        val rt = runtimes[tabId] ?: return
        val attachments = tab.imageAttachments
        if (payload.isEmpty() && attachments.isEmpty()) return
        if (attachments.any { it.status == ChatImageAttachmentStatus.UPLOADING }) return
        if (attachments.isNotEmpty()) {
            val sessionId = tab.liveSessionId ?: tab.resumeSessionId
            if (sessionId.isNullOrBlank()) {
                updateTab(tabId) { it.copy(error = "Wait for Hermes to finish starting before sending an image") }
                return
            }
            sendWithImages(tabId, sessionId, payload, attachments.map { it.id }, rt)
            return
        }
        commitSend(tabId, payload, emptyList(), rt)
    }

    private fun sendWithImages(
        tabId: String,
        sessionId: String,
        payload: String,
        attachmentIds: List<String>,
        runtime: SessionRuntime,
    ) {
        updateTab(tabId) { tab ->
            tab.copy(
                imageAttachments = tab.imageAttachments.map { image ->
                    val alreadyAttached = pendingImages[tabId]?.get(image.id)?.attachedSessionId == sessionId
                    if (image.id in attachmentIds) {
                        image.copy(
                            status = if (alreadyAttached) {
                                ChatImageAttachmentStatus.ATTACHED
                            } else {
                                ChatImageAttachmentStatus.UPLOADING
                            },
                            error = null,
                        )
                    } else {
                        image
                    }
                },
                error = null,
            )
        }
        viewModelScope.launch {
            var currentId: String? = null
            try {
                for (id in attachmentIds) {
                    currentId = id
                    val pending = pendingImages[tabId]?.get(id)
                        ?: throw IllegalStateException("An image attachment is no longer available")
                    if (pending.attachedSessionId != sessionId) {
                        val content = withContext(Dispatchers.Default) {
                            Base64.getEncoder().encodeToString(pending.image.bytes)
                        }
                        val result = runtime.eventClient.requestRpc(
                            "image.attach_bytes",
                            buildJsonObject {
                                put("session_id", sessionId)
                                put("content_base64", content)
                                put("filename", pending.image.filename)
                            },
                        ) as? JsonObject
                        if (result?.get("attached")?.jsonPrimitive?.booleanOrNull != true) {
                            throw IllegalStateException(
                                result?.get("message")?.jsonPrimitive?.contentOrNull
                                    ?: "Hermes did not accept ${pending.image.filename}",
                            )
                        }
                        pending.attachedSessionId = sessionId
                    }
                    updateTab(tabId) { tab ->
                        tab.copy(imageAttachments = tab.imageAttachments.map { image ->
                            if (image.id == id) {
                                image.copy(status = ChatImageAttachmentStatus.ATTACHED, error = null)
                            } else {
                                image
                            }
                        })
                    }
                }
                if (runtimes[tabId] !== runtime || _ui.value.tabs.none { it.id == tabId }) return@launch
                val names = attachmentIds.mapNotNull { pendingImages[tabId]?.get(it)?.image?.filename }
                commitSend(tabId, payload, names, runtime)
            } catch (failure: Throwable) {
                updateTab(tabId) { tab ->
                    tab.copy(
                        imageAttachments = tab.imageAttachments.map { image ->
                            when {
                                image.id == currentId -> image.copy(
                                    status = ChatImageAttachmentStatus.ERROR,
                                    error = failure.message,
                                )
                                image.status == ChatImageAttachmentStatus.UPLOADING -> image.copy(
                                    status = ChatImageAttachmentStatus.READY,
                                    error = null,
                                )
                                else -> image
                            }
                        },
                        error = failure.message ?: "Could not attach image",
                    )
                }
            }
        }
    }

    private fun commitSend(
        tabId: String,
        payload: String,
        imageNames: List<String>,
        runtime: SessionRuntime,
    ) {
        val prompt = payload.ifEmpty {
            if (imageNames.size == 1) "What do you see in this image?" else "What do you see in these images?"
        }
        val displayText = buildList {
            if (imageNames.isNotEmpty()) add(imageNames.joinToString(prefix = "🖼 ", separator = ", "))
            if (payload.isNotEmpty()) add(payload)
        }.joinToString("\n\n").ifEmpty { prompt }
        val userLine = ChatLine(UUID.randomUUID().toString(), "user", displayText)
        updateTab(tabId) {
            it.copy(
                draft = if (it.draft.trim() == payload) "" else it.draft,
                imageAttachments = emptyList(),
                lines = it.lines + userLine,
                readingMessages = it.readingMessages + userLine,
                hasSent = true,
                // Fresh turn: start the working indicator and drop the previous
                // turn's tool so we only ever surface the current one.
                working = true,
                tools = emptyList(),
                error = null,
            )
        }
        // Every turn starts in the clean transcript. Raw PTY/TUI output is a
        // diagnostic idle view and must not expose model reasoning in flight.
        _ui.update { it.copy(showSlashPalette = false, transcriptMode = TranscriptMode.READING) }
        pendingImages.remove(tabId)
        runtime.assistantBuffer = StringBuilder()
        _ui.value.tabs.firstOrNull { it.id == tabId }?.let { current ->
            AgentTaskNotificationService.startWatching(TalariaApp.instance, current.toAgentWatch())
        }
        runtime.session.sendText(prompt)
        if (_ui.value.activeTabId == tabId && _ui.value.active?.draft.isNullOrEmpty()) {
            viewModelScope.launch { chatRepository.saveDraft("") }
        }
    }

    /** Send Ctrl-C (interrupt) to the active agent's PTY (terminal pane, 15.13). */
    fun sendInterrupt() {
        val tabId = _ui.value.active?.id ?: return
        runtimes[tabId]?.session?.sendRaw("")
        AgentTaskNotificationService.stopWatching(TalariaApp.instance, tabId)
        updateTab(tabId) { it.copy(working = false, tools = emptyList()) }
        _ui.update { it.copy(transcriptMode = TranscriptMode.READING) }
    }

    fun resizePty(cols: Int, rows: Int) {
        lastCols = cols.coerceIn(20, 200)
        lastRows = rows.coerceIn(10, 80)
        runtimes.values.forEach { it.session.resize(lastCols, lastRows) }
    }

    fun respondPrompt(approved: Boolean, text: String? = null) {
        val tabId = _ui.value.active?.id ?: return
        val rt = runtimes[tabId] ?: return
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId } ?: return
        val prompt = tab.prompt ?: return
        val approvalChoice = prompt.choices.firstOrNull { it != "deny" } ?: "once"
        rt.eventClient.respondPrompt(
            kind = prompt.kind,
            sessionId = tab.liveSessionId ?: tab.resumeSessionId,
            requestId = prompt.requestId,
            approved = approved,
            text = text,
            approvalChoice = approvalChoice,
        ) { success ->
            if (success) {
                dispatchAgentAlert(
                    tabId,
                    HermesSideEvent.PromptExpired(
                        sessionId = tab.liveSessionId ?: tab.resumeSessionId,
                        requestId = prompt.requestId,
                    ),
                )
                updateTab(tabId) { it.copy(prompt = null, error = null) }
            } else {
                updateTab(tabId) { it.copy(error = "Hermes did not accept the prompt response") }
            }
        }
    }

    fun dismissPrompt() {
        val tabId = _ui.value.active?.id ?: return
        updateTab(tabId) { it.copy(prompt = null) }
    }

    fun reportError(message: String) {
        val tabId = _ui.value.active?.id ?: return
        updateTab(tabId) { it.copy(error = message) }
    }

    fun toggleListen() {
        if (_ui.value.listening) {
            sttJob?.cancel()
            _ui.update { it.copy(listening = false, partialDictation = "") }
            return
        }
        if (!speech.hasMicPermission()) {
            reportError("Microphone permission required")
            return
        }
        if (!speech.isAvailable()) {
            reportError("Speech recognition unavailable on this device")
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
                        _ui.update { it.copy(listening = false, partialDictation = "") }
                        reportError(event.message)
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

    /** Rename an agent tab (long-press affordance). Blank names are ignored. */
    fun renameTab(tabId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        updateTab(tabId) { it.copy(title = trimmed) }
        _ui.value.tabs.firstOrNull { it.id == tabId }?.let {
            AgentTaskNotificationService.updateWatching(TalariaApp.instance, it.toAgentWatch())
        }
        // Persist so the renamed title survives a cold start.
        persistChatState()
    }

    private fun handlePtyEvent(tabId: String, event: PtyEvent) {
        when (event) {
            is PtyEvent.Connected -> updateTab(tabId) { it.copy(connecting = false, connected = true) }
            is PtyEvent.Output -> appendAssistant(tabId, event.text)
            is PtyEvent.Closed -> {
                AgentTaskNotificationService.stopWatching(TalariaApp.instance, tabId)
                finalizeAssistant(tabId)
                updateTab(tabId) { it.copy(connected = false, connecting = false, working = false) }
            }
            is PtyEvent.Failure -> {
                AgentTaskNotificationService.stopWatching(TalariaApp.instance, tabId)
                updateTab(tabId) { it.copy(error = event.message, connecting = false, connected = false, working = false) }
                container.notifier.notifyError("Chat disconnected", event.message)
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
                        // A tab just claimed its session — snapshot so a cold start
                        // resumes this thread (and every sibling) with its title.
                        persistChatState()
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
        tab.liveSessionId?.let { return it }
        tab.resumeSessionId?.let { return it }
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
                    // A slower response for the pre-compression/pre-switch id
                    // must not overwrite the transcript after bindSession has
                    // re-anchored this tab to a newer live session.
                    if ((tab.liveSessionId ?: tab.resumeSessionId) != sessionId) {
                        return@updateTab tab
                    }
                    // Never let a transient/empty server read wipe optimistic messages;
                    // only replace when the server transcript is a superset of what we show.
                    // Equality guard: the 2.5s poll must not churn a full recomposition
                    // when nothing actually changed.
                    if (lines.size >= tab.readingMessages.size && lines != tab.readingMessages) {
                        rt.readingSessionId = sessionId
                        // The turn is done once the server transcript ends in an
                        // assistant message — drop the working indicator + tool.
                        val replyArrived = lines.lastOrNull()?.role == "assistant"
                        tab.copy(
                            readingMessages = lines,
                            working = if (replyArrived) false else tab.working,
                            tools = if (replyArrived) emptyList() else tab.tools,
                        )
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
        dispatchAgentAlert(tabId, event)
        when (event) {
            is HermesSideEvent.MessageStart -> {
                bindSession(tabId, event.sessionId)
                runtimes[tabId]?.sidecarAssistantBuffer = StringBuilder()
                updateTab(tabId) {
                    it.copy(working = true, error = null)
                }
            }
            is HermesSideEvent.MessageDelta -> {
                bindSession(tabId, event.sessionId)
                if (event.text.isNotEmpty()) {
                    val rt = runtimes[tabId] ?: return
                    // Buffer final-answer deltas for message.complete fallback,
                    // but do not expose partial output or reasoning in the UI.
                    rt.sidecarAssistantBuffer.append(event.text)
                    updateTab(tabId) { it.copy(working = true) }
                }
            }
            is HermesSideEvent.MessageInterim -> {
                bindSession(tabId, event.sessionId)
                // Interim commentary can contain model thought/reasoning. It is
                // intentionally neither displayed nor added to the final buffer.
                updateTab(tabId) { it.copy(working = true) }
            }
            is HermesSideEvent.MessageComplete -> completeSidecarMessage(tabId, event)
            is HermesSideEvent.BackgroundComplete -> Unit
            is HermesSideEvent.Status -> {
                bindSession(tabId, event.sessionId)
                // Process/goal status is transient activity, not a chat message.
                if (event.text.isNotBlank()) updateTab(tabId) { it.copy(working = true) }
            }
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
            is HermesSideEvent.Prompt -> {
                bindSession(tabId, event.sessionId)
                updateTab(tabId) {
                    it.copy(
                        prompt = ChatPromptUi(
                            kind = event.kind,
                            message = event.message,
                            requestId = event.requestId,
                            choices = event.choices,
                        ),
                    )
                }
            }
            is HermesSideEvent.PromptExpired -> updateTab(tabId) { tab ->
                val current = tab.prompt
                if (current != null && (event.requestId == null || current.requestId == event.requestId)) {
                    tab.copy(prompt = null)
                } else {
                    tab
                }
            }
            is HermesSideEvent.Model -> updateTab(tabId) {
                it.copy(modelLabel = event.name, modelConnected = event.connected)
            }
            is HermesSideEvent.CommandCatalog -> {
                slashCatalog = event.commands.map { live ->
                    val fallback = SlashCommands.defaults.firstOrNull {
                        it.command.equals(live.command, ignoreCase = true)
                    }
                    SlashCommand(
                        command = live.command,
                        description = live.description,
                        category = live.category,
                        aliases = fallback?.aliases.orEmpty(),
                        argumentMode = fallback?.argumentMode ?: if (
                            ARGUMENT_HINT.containsMatchIn(live.description)
                        ) {
                            SlashArgumentMode.MIXED
                        } else {
                            SlashArgumentMode.NONE
                        },
                    )
                }
                val draft = _ui.value.active?.draft.orEmpty()
                val suggestions = SlashCommands.suggest(draft, slashCatalog)
                _ui.update {
                    it.copy(
                        showSlashPalette = suggestions.isNotEmpty(),
                        slashSuggestions = suggestions,
                    )
                }
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

    /** The gateway's message.complete event is the authoritative turn boundary. */
    private fun completeSidecarMessage(tabId: String, event: HermesSideEvent.MessageComplete) {
        bindSession(tabId, event.sessionId)
        val rt = runtimes[tabId] ?: return
        val full = event.text.trim().ifEmpty { rt.sidecarAssistantBuffer.toString().trim() }
        rt.sidecarAssistantBuffer = StringBuilder()

        updateTab(tabId) { tab ->
            val duplicate = full.isNotEmpty() && tab.readingMessages.lastOrNull()?.let {
                it.role == "assistant" && it.text.trim() == full
            } == true
            tab.copy(
                readingMessages = if (full.isNotEmpty() && !duplicate) {
                    tab.readingMessages + ChatLine(UUID.randomUUID().toString(), "assistant", full)
                } else {
                    tab.readingMessages
                },
                working = false,
                tools = emptyList(),
                totalTokens = event.totalTokens ?: tab.totalTokens,
                costUsd = event.costUsd ?: tab.costUsd,
                error = if (event.status == "error" && full.isNotEmpty()) full else tab.error,
            )
        }
        if (full.isNotEmpty()) tts.speak(full)
        AgentTaskNotificationService.stopWatching(TalariaApp.instance, tabId)
    }

    /** Prefer the session id carried by live gateway events over polling heuristics. */
    private fun bindSession(tabId: String, sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.liveSessionId == sessionId) return
        tab.liveSessionId?.let { claimedSessions.remove(it) }
        claimedSessions.add(sessionId)
        updateTab(tabId) { it.copy(liveSessionId = sessionId) }
        runtimes[tabId]?.readingSessionId = sessionId
        persistChatState()
        _ui.value.tabs.firstOrNull { it.id == tabId }?.let {
            AgentTaskNotificationService.updateWatching(TalariaApp.instance, it.toAgentWatch())
        }
    }

    private fun dispatchAgentAlert(tabId: String, event: HermesSideEvent) {
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId } ?: return
        val profile = container.connectionStore.activeProfile()
        container.agentAlertDispatcher.dispatch(
            identity = AgentThreadIdentity(
                watcherId = tab.id,
                agentName = tab.title,
                sessionId = tab.liveSessionId ?: tab.resumeSessionId,
            ),
            event = event,
            connectionId = profile?.id,
            managementProfile = profile?.effectiveManagementProfile(),
        )
    }

    private fun ChatTab.toAgentWatch(): PersistedAgentWatch {
        val profile = container.connectionStore.activeProfile()
        return PersistedAgentWatch(
            watcherId = id,
            agentName = title,
            channelId = channelId,
            sessionId = liveSessionId ?: resumeSessionId,
            connectionId = profile?.id,
            managementProfile = profile?.effectiveManagementProfile(),
        )
    }

    override fun onCleared() {
        sttJob?.cancel()
        slashCompletionJob?.cancel()
        scopeLoadJob?.cancel()
        runtimes.values.forEach {
            it.collectJob?.cancel()
            it.sideJob?.cancel()
            it.readingJob?.cancel()
            it.session.close()
            it.eventClient.dispose()
        }
        runtimes.clear()
        pendingImages.clear()
        super.onCleared()
    }

    companion object {
        private val ARGUMENT_HINT = Regex("""\[[^]]+]|<[^>]+>""")

        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel() as T
        }
    }
}
