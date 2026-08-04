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
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.core.data.prefs.PersistedChatState
import com.hermesgadget.talaria.core.data.prefs.PersistedChatTab
import com.hermesgadget.talaria.core.data.prefs.PersistedAgentWatch
import com.hermesgadget.talaria.core.data.repo.ChatRepository
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.core.network.HermesEventClient
import com.hermesgadget.talaria.core.network.HermesSideEvent
import com.hermesgadget.talaria.core.network.ProfileRegistry
import com.hermesgadget.talaria.core.network.PromptKind
import com.hermesgadget.talaria.core.network.PtyEvent
import com.hermesgadget.talaria.core.network.PtyWebSocketSession
import com.hermesgadget.talaria.core.notifications.AgentTaskNotificationService
import com.hermesgadget.talaria.core.notifications.AgentThreadIdentity
import com.hermesgadget.talaria.core.voice.SpeechCoordinator
import com.hermesgadget.talaria.core.voice.SttEvent
import com.hermesgadget.talaria.core.voice.TtsSpeaker
import com.hermesgadget.talaria.domain.model.VoiceCapabilities
import com.hermesgadget.talaria.domain.model.VoiceTranscriptionRequest
import com.hermesgadget.talaria.feature.voice.VoiceRecorder
import com.hermesgadget.talaria.domain.model.ChatLine
import com.hermesgadget.talaria.domain.model.HERMES_DEFAULT_PROFILE
import com.hermesgadget.talaria.domain.model.ModelOption
import com.hermesgadget.talaria.domain.model.MultiProfileSession
import com.hermesgadget.talaria.domain.model.MultiProfileSessionMerger
import com.hermesgadget.talaria.domain.model.ProfileRegistryState
import com.hermesgadget.talaria.domain.model.ProfileStreamingState
import com.hermesgadget.talaria.domain.model.SessionMessage
import com.hermesgadget.talaria.domain.model.SessionSummary
import com.hermesgadget.talaria.domain.model.SlashArgumentMode
import com.hermesgadget.talaria.domain.model.SlashCommand
import com.hermesgadget.talaria.domain.model.SlashCommands
import com.hermesgadget.talaria.domain.model.ToolCallUi
import com.hermesgadget.talaria.domain.model.scopeId
import com.hermesgadget.talaria.domain.model.effectiveManagementProfile
import com.hermesgadget.talaria.feature.manage.sessions.SessionFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import retrofit2.HttpException
import java.util.Base64
import java.util.UUID

enum class TranscriptMode { TERMINAL, READING }

internal val CHAT_REASONING_EFFORTS = listOf(
    "none",
    "minimal",
    "low",
    "medium",
    "high",
    "xhigh",
    "max",
    "ultra",
)

internal val CHAT_APPROVAL_MODES = listOf("manual", "smart", "off")

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
    /** Hermes management profile captured when this runtime was opened. */
    val profileName: String = HERMES_DEFAULT_PROFILE,
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
    val queuedPrompts: List<String> = emptyList(),
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
    /** Profile-tagged, recency-ordered sessions for the future merged rail. */
    val mergedSessions: List<MultiProfileSession> = emptyList(),
    /** Profile names available to an All/<profile> filter row. */
    val sessionProfileOptions: List<String> = emptyList(),
    /** Null means All profiles; a non-null value selects one profile. */
    val selectedSessionProfile: String? = null,
    val sessionListLoading: Boolean = false,
    val profileStreamingStates: Map<String, ProfileStreamingState> = emptyMap(),
    val modelOptions: List<ModelOption> = emptyList(),
    val showSessionRail: Boolean = false,
    val showModelPicker: Boolean = false,
    val showSteerPopover: Boolean = false,
    val showSessionActions: Boolean = false,
    val showSlashPalette: Boolean = false,
    val slashSuggestions: List<SlashCommand> = emptyList(),
    val showTranscriptSearch: Boolean = false,
    val transcriptQuery: String = "",
    val composerReferences: List<ComposerReference> = emptyList(),
    val composerSuggestions: List<ComposerCompletion> = emptyList(),
    /** Parent stored-session id keyed by child id; absent on older dashboards. */
    val sessionBranchOrigins: Map<String, String> = emptyMap(),
    val sessionControls: ChatSessionControlsState = ChatSessionControlsState(),
) {
    val active: ChatTab? get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.firstOrNull()
}

data class ChatPromptUi(
    val kind: PromptKind,
    val message: String,
    val requestId: String? = null,
    val choices: List<String> = emptyList(),
    /** Unique for each gateway prompt, including prompts without a request id. */
    val instanceId: String = UUID.randomUUID().toString(),
)

private class SessionRuntime(
    val session: PtyWebSocketSession,
    val eventClient: HermesEventClient,
    var collectJob: Job? = null,
    var sideJob: Job? = null,
    var readingJob: Job? = null,
    var readingRequestJob: Job? = null,
    var readingGeneration: Long = 0L,
    val readingMutex: Mutex = Mutex(),
    var assistantBuffer: StringBuilder = StringBuilder(),
    var sidecarAssistantBuffer: StringBuilder = StringBuilder(),
    var readingSessionId: String? = null,
    // Sessions that already existed when this tab opened; its own session is a
    // NEW id that appears afterwards, which lets concurrent tabs each claim theirs.
    var baselineSessions: Set<String> = emptySet(),
    var baselineReady: Boolean = false,
    var sidecarEventsSeen: Boolean = false,
)

private data class PendingChatImage(
    val image: ValidatedChatImage,
    var attachedSessionId: String? = null,
)

private data class PendingLocalCreation(
    val tabId: String,
    val channelId: String,
    val profileName: String,
    val startedAtMillis: Long = System.currentTimeMillis(),
)

private data class CachedServerSttCapability(
    val supported: Boolean,
    val checkedAtMillis: Long,
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
    /** The single owner map behind [claimedSessions]; claims are atomic across pollers. */
    private val sessionOwners = mutableMapOf<String, String>()
    private val sessionOwnershipLock = Any()
    /** Local, not-yet-bound tabs keyed by their originating PTY channel. */
    private val pendingLocalCreations = mutableMapOf<String, PendingLocalCreation>()
    /** Tab ids that were created by the auto-open sync, not by the user. */
    private val autoOpenedTabs = mutableSetOf<String>()
    private var sessionCounter = 0
    // Suppresses per-tab persistence while restorePersistedTabs() rebuilds the
    // surface, so we don't write half-restored snapshots; we persist once at the end.
    private var restoring = false
    private var sttJob: Job? = null
    private var slashCompletionJob: Job? = null
    private var scopeLoadJob: Job? = null
    private var sessionPollJob: Job? = null
    private var lastCols = 80
    private var lastRows = 24
    private var initialDraft: String = ""
    private var slashCatalog: List<SlashCommand> = SlashCommands.defaults
    private var slashRequestGeneration: Long = 0
    private var boundConnectionScope: String? = null
    private var boundConnectionId: String? = null
    private var boundManagementProfile: String? = null
    private var connectionScopeGeneration = 0L
    private var loadingConnectionScope = false
    private val inputHistoryStore = ChatInputHistoryStore(TalariaApp.instance)
    private val inputHistories = mutableMapOf<String, InputHistoryNavigator>()
    /** Raw picker bytes stay outside StateFlow so Compose never copies or compares them. */
    private val pendingImages = mutableMapOf<String, LinkedHashMap<String, PendingChatImage>>()
    /** Server STT dictation (the primary voice path — same engine the Voice
     * settings test uses). On-device Android dictation is the fallback for
     * servers without STT; it can fail with a client error on some devices. */
    private val voiceRecorder = VoiceRecorder(TalariaApp.instance)
    private var serverDictation = false
    private var serverDictationTabId: String? = null
    private var serverDictationScopeGeneration: Long? = null
    private var serverSttUnavailable = false
    private var serverSttChecked = false
    private var serverSttScope: String? = null
    private var serverSttProbeGeneration = 0L
    private val serverSttCapabilities = mutableMapOf<String, CachedServerSttCapability>()

    /** Called by the screen: make sure at least one session exists (optionally resuming). */
    fun ensureStarted(resume: String? = null) {
        val activeProfile = container.connectionStore.activeProfile() ?: return
        val scopeId = activeProfile.scopeId()
        val connectionChanged = boundConnectionId != null && boundConnectionId != activeProfile.id
        if (boundConnectionScope == null || connectionChanged) {
            resetForConnectionScope(scopeId)
            loadProfileState(scopeId, resume)
            return
        }
        // A management-profile switch changes the Room/cache scope but must not
        // close PTY or sidecar runtimes belonging to the previous profile.
        if (boundConnectionScope != scopeId ||
            boundManagementProfile != activeProfile.effectiveManagementProfile()
        ) {
            bindManagementProfile(scopeId, activeProfile.effectiveManagementProfile(), resume)
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

    private fun loadProfileState(scopeId: String, resume: String?) {
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
            refreshSessions()
            startSessionPolling()
        }
    }

    /**
     * Periodically re-sync the profile registry so sessions started on other
     * platforms (Discord, Telegram, CLI…) auto-open as tabs and ended sessions
     * auto-close while the app is open — without requiring a manual refresh.
     * Deliberately bypasses refreshSessions() so the rail doesn't flash its
     * loading state every tick.
     */
    private fun startSessionPolling() {
        if (sessionPollJob?.isActive == true) return
        sessionPollJob = viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(SESSION_POLL_INTERVAL_MS)
                ProfileRegistry.refresh(container.clientFactory.api())
                    .onSuccess { applyProfileRegistry(it) }
            }
        }
    }

    private fun bindManagementProfile(scopeId: String, profileName: String, resume: String?) {
        scopeLoadJob?.cancel()
        connectionScopeGeneration += 1
        cancelVoiceInput()
        voiceRecorder.cancel()
        resetServerSttForScope(scopeId)
        boundConnectionScope = scopeId
        boundManagementProfile = profileName
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
            refreshSessions()
        }
    }

    /** Tear down every socket and transient byte buffer before binding another Hermes home. */
    private fun resetForConnectionScope(scopeId: String) {
        scopeLoadJob?.cancel()
        connectionScopeGeneration += 1
        cancelVoiceInput()
        resetServerSttForScope(scopeId)
        slashCompletionJob?.cancel()
        runtimes.values.forEach {
            it.collectJob?.cancel()
            it.sideJob?.cancel()
            it.readingJob?.cancel()
            it.readingRequestJob?.cancel()
            it.session.close()
            it.eventClient.dispose()
        }
        _ui.value.tabs.forEach { AgentTaskNotificationService.stopWatching(TalariaApp.instance, it.id) }
        runtimes.clear()
        claimedSessions.clear()
        synchronized(sessionOwnershipLock) { sessionOwners.clear() }
        pendingLocalCreations.clear()
        autoOpenedTabs.clear()
        pendingImages.clear()
        inputHistories.clear()
        slashCatalog = SlashCommands.defaults
        slashRequestGeneration += 1
        initialDraft = ""
        _ui.value = ChatUiState()
        boundConnectionScope = scopeId
        val active = container.connectionStore.activeProfile()
        boundConnectionId = active?.id
        boundManagementProfile = active?.effectiveManagementProfile()
        ProfileRegistry.reset()
    }

    /** Rebuild the tab list saved for this profile; falls back to one fresh agent. */
    private fun restorePersistedTabs() {
        val activeProfile = container.connectionStore.activeProfile()
        val profileName = activeProfile?.effectiveManagementProfile() ?: HERMES_DEFAULT_PROFILE
        val pid = activeProfile?.scopeId()
        val saved = pid?.let { container.settingsStore.loadChatState(it) } ?: PersistedChatState()
        val existing = _ui.value.tabs.filter { it.profileName == profileName }
        if (saved.tabs.isEmpty()) {
            if (existing.isEmpty()) newSession(draft = initialDraft)
            return
        }
        restoring = true
        saved.tabs.forEachIndexed { index, t ->
            if (t.sessionId.isNullOrBlank() || existing.any { it.resumeSessionId == t.sessionId }) return@forEachIndexed
            newSession(
                resume = t.sessionId,
                titleOverride = t.title,
                // Only the first restored tab inherits the saved composer draft.
                draft = if (index == 0) initialDraft else "",
            )
        }
        // Restore focus to the tab the user was last on.
        val activeId = _ui.value.tabs.firstOrNull {
            it.profileName == profileName &&
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
    private fun persistChatState(profileOverride: String? = null) {
        if (restoring) return
        val activeConnection = container.connectionStore.activeProfile() ?: return
        val profileName = profileOverride
            ?: _ui.value.active?.profileName
            ?: activeConnection.effectiveManagementProfile()
        val pid = activeConnection.copy(managementProfile = profileName).scopeId()
        // Runtime websocket ids are transient; durable ids keep resumed and branched
        // tabs reopen on the same REST/PTY session after process recreation.
        val tabs = _ui.value.tabs.filter {
            it.profileName == profileName && it.id !in autoOpenedTabs
        }.map { t ->
            PersistedChatTab(sessionId = t.resumeSessionId ?: t.liveSessionId, title = t.title)
        }
        container.settingsStore.saveChatState(
            pid,
            PersistedChatState(
                tabs = tabs,
                activeSessionId = _ui.value.active
                    ?.takeIf { it.profileName == profileName }
                    ?.let { it.resumeSessionId ?: it.liveSessionId },
            ),
        )
    }

    /**
     * Re-open PTY + sidecar for any tab that is neither connected nor mid-connect.
     * Prefer resuming the Hermes session id we already claimed so the agent
     * continues instead of spawning an orphaned new TUI.
     */
    fun reconnectDisconnected() {
        val activeProfileName = container.connectionStore.activeProfile()
            ?.effectiveManagementProfile()
            ?: HERMES_DEFAULT_PROFILE
        _ui.value.tabs
            // PtyWebSocketSession reads the active connection profile when it
            // opens. Keep reconnects on the foreground profile; background
            // runtimes already have their sockets and are left untouched.
            .filter { it.profileName == activeProfileName && !it.connected && !it.connecting }
            .forEach { reconnectTab(it.id) }
    }

    fun reconnectTab(tabId: String) {
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.connected || tab.connecting) return
        val snapshot = container.clientFactory.snapshot()
        if (snapshot == null || snapshot.managementProfile != tab.profileName) {
            updateTab(tabId) {
                it.copy(
                    error = "The saved connection changed and the operation was safely canceled.",
                    connecting = false,
                    connected = false,
                )
            }
            return
        }

        val old = runtimes.remove(tabId)
        old?.collectJob?.cancel()
        old?.sideJob?.cancel()
        old?.readingJob?.cancel()
        old?.readingRequestJob?.cancel()
        old?.session?.close()
        old?.eventClient?.dispose()
        removePendingLocalCreation(tabId)

        // The sidecar may expose a runtime id that is not accepted by the PTY
        // resume route; keep the durable branch id first when reconnecting.
        val resume = tab.resumeSessionId ?: tab.liveSessionId
        // A re-created gateway session may share the durable id but not its
        // in-memory image queue. Force any unsent images through attach_bytes again.
        pendingImages[tabId]?.values?.forEach { it.attachedSessionId = null }
        val channel = UUID.randomUUID().toString()
        val eventClient = HermesEventClient(
            container.clientFactory,
            container.wsAuthHelper,
            fixedSnapshot = snapshot,
        )
        val baselineBeforeOpen = ProfileRegistry.state.value
            .sessionsByProfile[tab.profileName]
            .orEmpty()
            .map { it.id }
            .toSet()
        val (pty, flow) = chatRepository.openPty(snapshot, resume, channel, lastCols, lastRows)
        val rt = SessionRuntime(
            session = pty,
            eventClient = eventClient,
            baselineSessions = old?.baselineSessions ?: baselineBeforeOpen,
            baselineReady = old?.baselineReady ?: true,
        )
        // Keep baseline from the prior runtime when present so we don't reclaim
        // unrelated sessions that appeared while we were disconnected.
        if (old != null) {
            rt.baselineSessions = old.baselineSessions
            rt.baselineReady = old.baselineReady
            rt.readingSessionId = old.readingSessionId
            rt.sidecarEventsSeen = old.sidecarEventsSeen
        }
        runtimes[tabId] = rt
        if (resume.isNullOrBlank()) {
            pendingLocalCreations[channel] = PendingLocalCreation(
                tabId = tabId,
                channelId = channel,
                profileName = tab.profileName,
            )
        }

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
        resume?.let { ProfileRegistry.markConnecting(tab.profileName, it) }

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
                val list = sessionsForProfile(tab.profileName)
                if (resume != null) rt.baselineSessions = list.map { it.id }.toSet()
                rt.baselineReady = true
                _ui.update { it.copy(sessions = list.take(40)) }
            }
        }
        if (!resume.isNullOrBlank()) requestReading(tabId, resume)
        startReadingPoll(tabId)
    }

    /** Open a brand-new concurrent agent in its own tab and focus it. */
    fun newSession(resume: String? = null, titleOverride: String? = null, draft: String = "") {
        val snapshot = container.clientFactory.snapshot()
        if (snapshot == null) {
            return
        }
        val id = UUID.randomUUID().toString()
        val channel = UUID.randomUUID().toString()
        sessionCounter += 1
        val title = titleOverride ?: "Agent $sessionCounter"
        val eventClient = HermesEventClient(
            container.clientFactory,
            container.wsAuthHelper,
            fixedSnapshot = snapshot,
        )
        val profileName = snapshot.managementProfile
        val baselineBeforeOpen = ProfileRegistry.state.value
            .sessionsByProfile[profileName]
            .orEmpty()
            .map { it.id }
            .toSet()
        val (pty, flow) = chatRepository.openPty(snapshot, resume, channel, lastCols, lastRows)
        val rt = SessionRuntime(
            session = pty,
            eventClient = eventClient,
            baselineSessions = baselineBeforeOpen,
            baselineReady = true,
        )
        runtimes[id] = rt
        if (resume.isNullOrBlank()) {
            pendingLocalCreations[channel] = PendingLocalCreation(
                tabId = id,
                channelId = channel,
                profileName = profileName,
            )
        }

        _ui.update {
            it.copy(
                tabs = it.tabs + ChatTab(
                    id = id,
                    title = title,
                    channelId = channel,
                    profileName = profileName,
                    resumeSessionId = resume,
                    liveSessionId = resume,
                    connecting = true,
                    draft = draft,
                ),
                activeTabId = id,
            )
        }
        updateComposerAnalysis(draft)
        resume?.let { ProfileRegistry.markConnecting(profileName, it) }
        resume?.let { claimSession(id, it) }

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
            // The registry snapshot above was captured before the PTY was
            // opened. Replacing it with this later response would reclassify
            // the just-created server session as pre-existing.
            _ui.update { it.copy(sessions = list.take(40)) }
        }
        if (!resume.isNullOrBlank()) requestReading(id, resume)
        startReadingPoll(id)
        persistChatState()
    }

    fun switchTab(tabId: String) {
        _ui.update { it.copy(activeTabId = tabId) }
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId }
        updateComposerAnalysis(tab?.draft.orEmpty())
        persistChatState()
    }

    fun closeTab(tabId: String) {
        autoOpenedTabs.remove(tabId)
        removePendingLocalCreation(tabId)
        AgentTaskNotificationService.stopWatching(TalariaApp.instance, tabId)
        pendingImages.remove(tabId)
        val rt = runtimes.remove(tabId)
        rt?.collectJob?.cancel()
        rt?.sideJob?.cancel()
        rt?.readingJob?.cancel()
        rt?.readingRequestJob?.cancel()
        rt?.session?.close()
        rt?.eventClient?.dispose()
        _ui.value.tabs.firstOrNull { it.id == tabId }?.let { tab ->
            releaseSession(tabId, tab.liveSessionId)
            releaseSession(tabId, tab.resumeSessionId)
            (tab.liveSessionId ?: tab.resumeSessionId)?.let {
                ProfileRegistry.markDisconnected(tab.profileName, it)
            }
        }
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
            _ui.update { it.copy(sessionListLoading = true) }
            ProfileRegistry.refresh(container.clientFactory.api()).onSuccess { registry ->
                applyProfileRegistry(registry)
            }.onFailure { failure ->
                _ui.update { it.copy(sessionListLoading = false) }
            }
            refreshSessionBranchOrigins()
        }
    }

    /** Select null for All profiles, or one of [ChatUiState.sessionProfileOptions]. */
    fun selectSessionProfile(profileName: String?) {
        val selected = profileName?.trim()?.takeIf { it.isNotEmpty() }
        _ui.update { it.copy(selectedSessionProfile = selected) }
        applyProfileRegistry(ProfileRegistry.state.value)
    }

    private fun applyProfileRegistry(registry: ProfileRegistryState) {
        val selected = _ui.value.selectedSessionProfile
            ?.takeIf { it in registry.profileNames }
        val merged = MultiProfileSessionMerger.merge(registry.sessionsByProfile, selected)
        val activeProfile = profileNameForActiveConnection()
        // `sessions` remains the legacy active-profile projection for the
        // existing rail. The tagged `mergedSessions` projection is the safe
        // handoff for the profile-aware rail/filter UI.
        _ui.update {
            it.copy(
                sessions = registry.sessionsByProfile[activeProfile].orEmpty().take(40),
                mergedSessions = merged,
                sessionProfileOptions = registry.profileNames,
                selectedSessionProfile = selected,
                sessionListLoading = registry.loading,
                profileStreamingStates = registry.streamingStates,
            )
        }
        syncActiveSessions(registry)
    }

    /**
     * Auto-open a tab for every active user session (not cron/webhook) that the
     * user started on another platform — Discord, Telegram, CLI, etc. The user
     * gets live visibility and notifications for all their chats without
     * manually opening each one.
     */
    private fun syncActiveSessions(registry: ProfileRegistryState) {
        reconcilePendingLocalCreations(registry)
        val activeProfile = profileNameForActiveConnection()
        val snapshot = container.clientFactory.snapshot()
            ?.takeIf { it.managementProfile == activeProfile }
            ?: return
        val openSessionIds = _ui.value.tabs.mapNotNull { it.resumeSessionId ?: it.liveSessionId }.toSet()
        val autoSources = SessionFilters.AUTOMATION_SOURCES.toSet()

        for ((profileName, sessions) in registry.sessionsByProfile) {
            // Only auto-open on the foreground profile; background-profile
            // sessions are visible through the merged rail.
            if (profileName != activeProfile) continue
            val pendingCandidates = pendingLocalCandidateIds(profileName, sessions)
            for (s in sessions) {
                val source = s.source.orEmpty().lowercase()
                // Skip automation sessions and already-open tabs.
                if (autoSources.any { source.contains(it) }) continue
                if (s.id in openSessionIds) continue
                // A newly-created local tab owns the first session(s) that
                // appeared after its channel was opened. Let its discovery
                // poll resolve that ownership before auto-open can claim it.
                if (s.id in pendingCandidates) continue
                // A session with no end marker is still open — the server's
                // `is_active` flag alone would wrongly skip idle-but-running
                // chats (it uses a 5-minute activity window).
                val ended = s.end_reason != null || s.ended_at != null
                if (ended) continue

                val id = UUID.randomUUID().toString()
                val channel = UUID.randomUUID().toString()
                sessionCounter += 1
                val title = s.title?.takeIf { it.isNotBlank() }
                    ?: s.preview?.take(40)
                    ?: "Agent $sessionCounter"
                val eventClient = HermesEventClient(
                    container.clientFactory,
                    container.wsAuthHelper,
                    fixedSnapshot = snapshot,
                )
                val (pty, flow) = chatRepository.openPty(snapshot, s.id, channel, lastCols, lastRows)
                val rt = SessionRuntime(session = pty, eventClient = eventClient)
                runtimes[id] = rt
                autoOpenedTabs.add(id)
                if (!claimSession(id, s.id)) {
                    runtimes.remove(id)
                    rt.session.close()
                    rt.eventClient.dispose()
                    autoOpenedTabs.remove(id)
                    continue
                }

                _ui.update {
                    it.copy(
                        tabs = it.tabs + ChatTab(
                            id = id,
                            title = title,
                            channelId = channel,
                            profileName = profileName,
                            resumeSessionId = s.id,
                            liveSessionId = s.id,
                            connecting = true,
                        ),
                        activeTabId = it.activeTabId ?: id,
                    )
                }
                ProfileRegistry.markConnecting(profileName, s.id)

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
                requestReading(id, s.id)
                startReadingPoll(id)

                // Register for background notifications so the user gets alerts
                // for activity on every auto-opened session, not just the one
                // they're actively looking at.
                AgentTaskNotificationService.startWatching(
                    TalariaApp.instance,
                    PersistedAgentWatch(
                        watcherId = id,
                        agentName = title,
                        channelId = channel,
                        sessionId = s.id,
                        connectionId = boundConnectionId,
                        managementProfile = profileName,
                    ),
                )
            }
        }

        // Close auto-opened tabs whose sessions have ended or been reset
        // (e.g. /new on Discord). Idle-but-running sessions stay open —
        // the server's is_active flag uses a 5-minute activity window.
        val stillActive = registry.sessionsByProfile[activeProfile].orEmpty()
            .filter { it.end_reason == null && it.ended_at == null }
            .map { it.id }
            .toSet()
        autoOpenedTabs.toList().forEach { tabId ->
            val tab = _ui.value.tabs.firstOrNull { it.id == tabId } ?: return@forEach
            val sessionId = tab.resumeSessionId ?: tab.liveSessionId ?: return@forEach
            if (sessionId !in stillActive) {
                closeAutoTab(tabId)
            }
        }
    }

    /**
     * Claiming a server id is the one ownership operation used by both local
     * discovery and the background auto-open poller. The callbacks normally
     * run on the ViewModel dispatcher, but the lock also protects the race
     * when two refresh completions arrive in the same frame.
     */
    private fun claimSession(tabId: String, sessionId: String): Boolean {
        if (tabId.isBlank() || sessionId.isBlank()) return false
        synchronized(sessionOwnershipLock) {
            val owner = sessionOwners[sessionId]
            if (owner != null && owner != tabId) return false
            sessionOwners[sessionId] = tabId
            claimedSessions.add(sessionId)
            return true
        }
    }

    private fun releaseSession(tabId: String, sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        synchronized(sessionOwnershipLock) {
            if (sessionOwners[sessionId] == tabId) {
                sessionOwners.remove(sessionId)
                claimedSessions.remove(sessionId)
            }
        }
    }

    private fun removePendingLocalCreation(tabId: String) {
        val channel = pendingLocalCreations.values
            .firstOrNull { it.tabId == tabId }
            ?.channelId
        if (channel != null) pendingLocalCreations.remove(channel)
    }

    /**
     * Return candidate ids that must stay out of auto-open while a local PTY
     * still has no server id. A baseline that is not ready protects the whole
     * current profile for one refresh; otherwise only ids newer than that
     * tab's pre-open snapshot are protected.
     */
    private fun pendingLocalCandidateIds(
        profileName: String,
        sessions: List<SessionSummary>,
    ): Set<String> {
        val pending = pendingLocalCreations.values.filter { creation ->
            if (creation.profileName != profileName) return@filter false
            val tab = _ui.value.tabs.firstOrNull { it.id == creation.tabId } ?: return@filter false
            val runtime = runtimes[creation.tabId] ?: return@filter false
            tab.hasSent && tab.resumeSessionId == null && tab.liveSessionId == null &&
                !runtime.baselineReady
        }
        if (pending.isNotEmpty()) return sessions.map { it.id }.toSet()

        return pendingLocalCreations.values.asSequence()
            .filter { it.profileName == profileName }
            .mapNotNull { creation ->
                val tab = _ui.value.tabs.firstOrNull { it.id == creation.tabId } ?: return@mapNotNull null
                val runtime = runtimes[creation.tabId] ?: return@mapNotNull null
                if (!tab.hasSent || tab.resumeSessionId != null || tab.liveSessionId != null) {
                    return@mapNotNull null
                }
                val ids = sessions.asSequence()
                    .filter { it.id !in runtime.baselineSessions }
                    .map { it.id }
                    .toSet()
                ids
            }
            .flatten()
            .toSet()
    }

    /**
     * Resolve pending local channels before the next auto-open pass. If a
     * transport died without ever yielding a session id, remove the runtime
     * after a bounded wait so a permanently orphaned tab cannot reserve every
     * future server session.
     */
    private fun reconcilePendingLocalCreations(registry: ProfileRegistryState) {
        val now = System.currentTimeMillis()
        pendingLocalCreations.values.toList().forEach { creation ->
            val tab = _ui.value.tabs.firstOrNull { it.id == creation.tabId }
            val runtime = runtimes[creation.tabId]
            if (tab == null || runtime == null) {
                pendingLocalCreations.remove(creation.channelId)
                return@forEach
            }
            if (tab.resumeSessionId != null || tab.liveSessionId != null) {
                pendingLocalCreations.remove(creation.channelId)
                return@forEach
            }
            if (tab.hasSent && runtime.baselineReady) {
                val candidate = registry.sessionsByProfile[creation.profileName]
                    .orEmpty()
                    .asSequence()
                    .filter { it.id !in runtime.baselineSessions }
                    .filter { it.id !in claimedSessions }
                    .maxByOrNull { MultiProfileSession(creation.profileName, it).recency }
                if (candidate != null && claimSession(creation.tabId, candidate.id)) {
                    bindSession(creation.tabId, candidate.id)
                    return@forEach
                }
            }
            if (tab.hasSent && now - creation.startedAtMillis >= LOCAL_SESSION_DISCOVERY_TIMEOUT_MS) {
                pendingLocalCreations.remove(creation.channelId)
                runtimes.remove(creation.tabId)
                runtime.readingJob?.cancel()
                runtime.readingRequestJob?.cancel()
                runtime.collectJob?.cancel()
                runtime.sideJob?.cancel()
                runtime.session.close()
                runtime.eventClient.dispose()
                AgentTaskNotificationService.stopWatching(TalariaApp.instance, creation.tabId)
                updateTab(creation.tabId) {
                    it.copy(
                        connected = false,
                        connecting = false,
                        working = false,
                        error = "Hermes did not expose the new chat session; reconnect and retry",
                    )
                }
            } else if (!tab.hasSent && now - creation.startedAtMillis >= LOCAL_SESSION_DISCOVERY_TIMEOUT_MS) {
                // Blank tabs do not reserve any server id. Drop their pending
                // record, and re-register it when the user eventually sends.
                pendingLocalCreations.remove(creation.channelId)
            }
        }
    }

    /** Close an auto-opened tab without persisting it or opening a fallback. */
    private fun closeAutoTab(tabId: String) {
        autoOpenedTabs.remove(tabId)
        AgentTaskNotificationService.stopWatching(TalariaApp.instance, tabId)
        pendingImages.remove(tabId)
        val rt = runtimes.remove(tabId)
        rt?.collectJob?.cancel()
        rt?.sideJob?.cancel()
        rt?.readingJob?.cancel()
        rt?.readingRequestJob?.cancel()
        rt?.session?.close()
        rt?.eventClient?.dispose()
        _ui.value.tabs.firstOrNull { it.id == tabId }?.let { tab ->
            releaseSession(tabId, tab.liveSessionId)
            releaseSession(tabId, tab.resumeSessionId)
            (tab.liveSessionId ?: tab.resumeSessionId)?.let {
                ProfileRegistry.markDisconnected(tab.profileName, it)
            }
        }
        _ui.update { state ->
            val remaining = state.tabs.filterNot { it.id == tabId }
            state.copy(
                tabs = remaining,
                activeTabId = if (state.activeTabId == tabId) remaining.lastOrNull()?.id else state.activeTabId,
            )
        }
    }

    private fun profileNameForActiveConnection(): String =
        container.connectionStore.activeProfile()?.effectiveManagementProfile()
            ?: HERMES_DEFAULT_PROFILE

    private fun profileNameForTab(tabId: String): String =
        _ui.value.tabs.firstOrNull { it.id == tabId }?.profileName
            ?: profileNameForActiveConnection()

    /**
     * SessionSummary predates branch lineage, so keep the optional parent field
     * in the chat feature's raw projection. Older gateways simply produce an
     * empty map and the rail remains a normal flat session list.
     */
    private suspend fun refreshSessionBranchOrigins() {
        val raw = runCatching {
            withContext(Dispatchers.IO) {
                container.clientFactory.api().getSessions(limit = 50, offset = 0)
            }
        }.getOrNull() ?: return
        _ui.update { it.copy(sessionBranchOrigins = parseSessionBranchOrigins(raw)) }
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

    fun toggleSteerPopover(show: Boolean = !_ui.value.showSteerPopover) {
        _ui.update { it.copy(showSteerPopover = show) }
    }

    fun toggleSessionActions(show: Boolean = !_ui.value.showSessionActions) {
        _ui.update { it.copy(showSessionActions = show) }
    }

    fun toggleTranscriptSearch(show: Boolean = !_ui.value.showTranscriptSearch) {
        _ui.update {
            it.copy(
                showTranscriptSearch = show,
                transcriptQuery = if (show) it.transcriptQuery else "",
            )
        }
    }

    fun updateTranscriptQuery(query: String) {
        _ui.update { it.copy(transcriptQuery = query) }
    }

    fun pickComposerCompletion(completion: ComposerCompletion) {
        val tab = _ui.value.active ?: return
        val draft = tab.draft
        if (completion.tokenStart !in 0..draft.length || completion.tokenEnd !in 0..draft.length) return
        if (completion.tokenStart > completion.tokenEnd) return
        val replacement = buildString {
            append(draft.substring(0, completion.tokenStart))
            append(completion.insertText)
            append(draft.substring(completion.tokenEnd))
        }
        applyDraft(tab.id, replacement)
    }

    /** Opens the confirmation dialog only; the branch RPC starts after confirmation. */
    fun requestRewind(messageCount: Int, preview: String) {
        val tab = _ui.value.active ?: return
        val sessionId = tab.liveSessionId ?: tab.resumeSessionId ?: return
        if (messageCount <= 0) return
        if (tab.working) {
            setSessionActionFailure(
                ChatSessionActionKind.REWIND,
                TalariaApp.instance.getString(R.string.chat_branch_stop_turn),
            )
            return
        }
        _ui.update {
            it.copy(
                sessionControls = ChatSessionControlsReducer.requestRewind(
                    state = it.sessionControls,
                    tabId = tab.id,
                    sessionId = sessionId,
                    messageCount = messageCount,
                    preview = preview,
                ),
            )
        }
    }

    /** Opens message actions; the branch or edit only starts after the user chooses one. */
    fun requestMessageActions(line: ChatLine, displayedIndex: Int) {
        val tab = _ui.value.active ?: return
        val sessionId = tab.liveSessionId ?: tab.resumeSessionId ?: return
        if (tab.working) {
            setSessionActionFailure(
                ChatSessionActionKind.REWIND,
                TalariaApp.instance.getString(R.string.chat_branch_stop_turn),
            )
            return
        }
        val target = ChatMessageTarget(
            tabId = tab.id,
            sessionId = sessionId,
            messageCount = branchMessageCount(line, displayedIndex),
            role = line.role,
            text = line.text,
        )
        _ui.update {
            it.copy(
                sessionControls = ChatSessionControlsReducer.requestMessageActions(
                    it.sessionControls,
                    target,
                ),
            )
        }
    }

    fun beginMessageBranch() {
        val dialog = _ui.value.sessionControls.dialog as? ChatSessionDialog.MessageActions ?: return
        val target = dialog.target
        _ui.update {
            it.copy(
                sessionControls = ChatSessionControlsReducer.begin(
                    it.sessionControls,
                    ChatSessionActionKind.REWIND,
                ),
            )
        }
        branchSession(
            ChatSessionDialog.Rewind(
                tabId = target.tabId,
                sessionId = target.sessionId,
                messageCount = target.messageCount,
                preview = target.text,
            ),
        )
    }

    fun beginMessageEdit() {
        val dialog = _ui.value.sessionControls.dialog as? ChatSessionDialog.MessageActions ?: return
        if (dialog.target.role != "user") return
        _ui.update {
            it.copy(
                sessionControls = ChatSessionControlsReducer.requestMessageEdit(
                    it.sessionControls,
                    dialog.target,
                ),
            )
        }
    }

    fun confirmMessageEdit(text: String) {
        val dialog = _ui.value.sessionControls.dialog as? ChatSessionDialog.EditMessage ?: return
        val edited = text.trim()
        if (edited.isEmpty()) {
            setSessionActionFailure(
                ChatSessionActionKind.EDIT,
                TalariaApp.instance.getString(R.string.chat_message_edit_empty),
            )
            return
        }
        _ui.update {
            it.copy(
                sessionControls = ChatSessionControlsReducer.begin(
                    it.sessionControls,
                    ChatSessionActionKind.EDIT,
                ),
            )
        }
        branchEditedMessage(dialog.target, edited)
    }

    /** Opens the confirmation dialog only; compaction starts after confirmation. */
    fun requestCompactSession() {
        val tab = _ui.value.active ?: return
        val sessionId = tab.liveSessionId ?: tab.resumeSessionId ?: return
        if (tab.working) {
            setSessionActionFailure(ChatSessionActionKind.COMPACT, "Stop the current turn before compacting")
            return
        }
        _ui.update {
            it.copy(
                sessionControls = ChatSessionControlsReducer.requestCompact(
                    state = it.sessionControls,
                    tabId = tab.id,
                    sessionId = sessionId,
                ),
            )
        }
    }

    /** Opens the title editor for an active or rail-listed stored session. */
    fun requestSessionTitleEdit(sessionId: String? = null) {
        val active = _ui.value.active
        val targetId = sessionId
            ?: active?.resumeSessionId
            ?: active?.liveSessionId
            ?: return
        val tab = _ui.value.tabs.firstOrNull {
            it.resumeSessionId == targetId || it.liveSessionId == targetId
        }
        val existingTitle = _ui.value.sessions.firstOrNull { it.id == targetId }?.title
            ?: tab?.title
            ?: ""
        _ui.update {
            it.copy(
                sessionControls = ChatSessionControlsReducer.requestTitleEdit(
                    state = it.sessionControls,
                    sessionId = targetId,
                    tabId = tab?.id,
                    initialTitle = existingTitle,
                ),
            )
        }
    }

    fun dismissSessionDialog() {
        _ui.update {
            it.copy(sessionControls = ChatSessionControlsReducer.dismissDialog(it.sessionControls))
        }
    }

    /** Confirms either destructive session action currently shown by the dialog. */
    fun confirmSessionDialog() {
        val dialog = _ui.value.sessionControls.dialog ?: return
        when (dialog) {
            is ChatSessionDialog.Rewind -> {
                _ui.update {
                    it.copy(
                        sessionControls = ChatSessionControlsReducer.begin(
                            it.sessionControls,
                            ChatSessionActionKind.REWIND,
                        ),
                    )
                }
                branchSession(dialog)
            }
            is ChatSessionDialog.Compact -> {
                _ui.update {
                    it.copy(
                        sessionControls = ChatSessionControlsReducer.begin(
                            it.sessionControls,
                            ChatSessionActionKind.COMPACT,
                        ),
                    )
                }
                compactSession(dialog)
            }
            is ChatSessionDialog.EditTitle -> Unit
            is ChatSessionDialog.MessageActions -> Unit
            is ChatSessionDialog.EditMessage -> Unit
        }
    }

    fun confirmSessionTitle(title: String) {
        val dialog = _ui.value.sessionControls.dialog as? ChatSessionDialog.EditTitle ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            setSessionActionFailure(ChatSessionActionKind.RENAME, "Session title cannot be blank")
            return
        }
        _ui.update {
            it.copy(
                sessionControls = ChatSessionControlsReducer.begin(
                    it.sessionControls,
                    ChatSessionActionKind.RENAME,
                ),
            )
        }
        viewModelScope.launch {
            hermesRepository.renameSession(dialog.sessionId, trimmed)
                .onSuccess {
                    dialog.tabId?.let { tabId -> updateTab(tabId) { it.copy(title = trimmed) } }
                    _ui.update {
                        it.copy(
                            sessionControls = ChatSessionControlsReducer.succeed(
                                it.sessionControls,
                                ChatSessionActionKind.RENAME,
                                "Session title updated",
                            ),
                        )
                    }
                    refreshSessions()
                }
                .onFailure { failure ->
                    setSessionActionFailure(
                        ChatSessionActionKind.RENAME,
                        failure.message ?: "Could not update the session title",
                    )
                }
        }
    }

    private fun branchSession(request: ChatSessionDialog.Rewind) {
        val runtime = runtimes[request.tabId]
        if (runtime == null) {
            setSessionActionFailure(
                ChatSessionActionKind.REWIND,
                TalariaApp.instance.getString(R.string.chat_branch_connection_error),
            )
            return
        }
        viewModelScope.launch {
            try {
                val root = requestBranch(runtime, request.sessionId, request.messageCount)
                val storedSessionId = root["stored_session_id"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: root["session_id"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                    ?: error("Hermes did not return the new branch session id")
                val title = root["title"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: "Branch"
                val parent = root["parent"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: _ui.value.sessions.firstOrNull { it.id == request.sessionId }?.id
                    ?: request.sessionId
                _ui.update {
                    it.copy(sessionBranchOrigins = it.sessionBranchOrigins + (storedSessionId to parent))
                }
                // Branching creates a live child but leaves the parent tab intact.
                // The stored id is the resumable chat route; the RPC session_id is
                // only the gateway's in-memory runtime id.
                newSession(resume = storedSessionId, titleOverride = title)
                _ui.update {
                    it.copy(
                        sessionControls = ChatSessionControlsReducer.succeed(
                            it.sessionControls,
                            ChatSessionActionKind.REWIND,
                            "Opened branch from ${request.preview.take(48)}",
                        ),
                    )
                }
                refreshSessions()
            } catch (failure: Throwable) {
                setSessionActionFailure(
                    ChatSessionActionKind.REWIND,
                    failure.message ?: TalariaApp.instance.getString(R.string.chat_branch_failed),
                )
            }
        }
    }

    private fun branchEditedMessage(target: ChatMessageTarget, editedText: String) {
        val runtime = runtimes[target.tabId]
        if (runtime == null) {
            setSessionActionFailure(
                ChatSessionActionKind.EDIT,
                TalariaApp.instance.getString(R.string.chat_branch_connection_error),
            )
            return
        }
        viewModelScope.launch {
            try {
                // Editing a prompt means retaining the history before it, then
                // placing the replacement in the new chat's composer.
                val root = requestBranch(
                    runtime = runtime,
                    sessionId = target.sessionId,
                    messageCount = editedMessageBranchCount(target),
                )
                val storedSessionId = root["stored_session_id"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: root["session_id"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                    ?: error(TalariaApp.instance.getString(R.string.chat_branch_id_missing))
                val title = root["title"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: TalariaApp.instance.getString(R.string.chat_edited_branch_title)
                val parent = root["parent"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: target.sessionId
                _ui.update {
                    it.copy(sessionBranchOrigins = it.sessionBranchOrigins + (storedSessionId to parent))
                }
                newSession(
                    resume = storedSessionId,
                    titleOverride = title,
                    draft = editedText,
                )
                _ui.update {
                    it.copy(
                        sessionControls = ChatSessionControlsReducer.succeed(
                            it.sessionControls,
                            ChatSessionActionKind.EDIT,
                            TalariaApp.instance.getString(R.string.chat_edit_branch_success),
                        ),
                    )
                }
                refreshSessions()
            } catch (failure: Throwable) {
                setSessionActionFailure(
                    ChatSessionActionKind.EDIT,
                    failure.message ?: TalariaApp.instance.getString(R.string.chat_edit_branch_failed),
                )
            }
        }
    }

    private suspend fun requestBranch(
        runtime: SessionRuntime,
        sessionId: String,
        messageCount: Int,
    ): JsonObject = runtime.eventClient.requestRpc(
        "session.branch",
        buildJsonObject {
            put("session_id", sessionId)
            put("count", messageCount)
        },
    ) as? JsonObject ?: error(TalariaApp.instance.getString(R.string.chat_branch_failed))

    private fun compactSession(request: ChatSessionDialog.Compact) {
        val runtime = runtimes[request.tabId]
        if (runtime == null) {
            setSessionActionFailure(ChatSessionActionKind.COMPACT, "The chat connection is no longer active")
            return
        }
        viewModelScope.launch {
            try {
                val root = runtime.eventClient.requestRpc(
                    "session.compress",
                    buildJsonObject { put("session_id", request.sessionId) },
                ) as? JsonObject ?: error("Hermes did not return a compaction result")
                val messages = parseSessionActionMessages(root, request.sessionId)
                val storedSessionId = (root["info"] as? JsonObject)
                    ?.get("stored_session_id")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                if (storedSessionId != null) {
                    updateTab(request.tabId) { it.copy(resumeSessionId = storedSessionId) }
                }
                if (messages != null) {
                    updateTab(request.tabId) {
                        it.copy(
                            readingMessages = messages,
                            working = false,
                            tools = emptyList(),
                            error = null,
                        )
                    }
                }
                val status = root["status"]?.jsonPrimitive?.contentOrNull
                val message = when {
                    root["lock_held"]?.jsonPrimitive?.booleanOrNull == true ->
                        root["message"]?.jsonPrimitive?.contentOrNull
                            ?: "Session compaction is already in progress"
                    status == "aborted" -> "Session compaction was aborted"
                    status == "compressed" -> "Session compacted"
                    else -> root["message"]?.jsonPrimitive?.contentOrNull ?: "Session compacted"
                }
                _ui.update {
                    it.copy(
                        sessionControls = ChatSessionControlsReducer.succeed(
                            it.sessionControls,
                            ChatSessionActionKind.COMPACT,
                            message,
                        ),
                    )
                }
                refreshSessions()
            } catch (failure: Throwable) {
                setSessionActionFailure(
                    ChatSessionActionKind.COMPACT,
                    failure.message ?: "Could not compact this session",
                )
            }
        }
    }

    private fun setSessionActionFailure(kind: ChatSessionActionKind, message: String) {
        _ui.update {
            it.copy(
                sessionControls = ChatSessionControlsReducer.fail(
                    it.sessionControls,
                    kind,
                    message,
                ),
            )
        }
    }

    fun selectModel(option: ModelOption) {
        val modelId = option.id ?: option.name ?: option.label ?: return
        val tabId = _ui.value.active?.id ?: return
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId } ?: return
        val sessionId = tab.liveSessionId ?: tab.resumeSessionId
        val runtime = runtimes[tabId] ?: return
        _ui.update { it.copy(showModelPicker = false, showSteerPopover = false) }
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

    private fun historyKey(tab: ChatTab): String =
        tab.liveSessionId ?: tab.resumeSessionId ?: "tab:${tab.id}"

    private fun historyFor(tab: ChatTab): InputHistoryNavigator =
        inputHistories.getOrPut(historyKey(tab)) {
            InputHistoryNavigator(inputHistoryStore.load(historyKey(tab)))
        }

    private fun recordSubmittedDraft(tab: ChatTab, payload: String) {
        if (payload.isBlank()) return
        val history = historyFor(tab)
        history.record(payload)
        inputHistoryStore.save(historyKey(tab), history.snapshot)
    }

    private fun migrateInputHistory(oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        val old = inputHistories.remove(oldKey) ?: return
        val existing = inputHistories.getOrPut(newKey) {
            InputHistoryNavigator(inputHistoryStore.load(newKey))
        }
        val merged = InputHistoryNavigator(
            (existing.snapshot + old.snapshot).takeLast(InputHistoryNavigator.MAX_ENTRIES),
        )
        inputHistories[newKey] = merged
        inputHistoryStore.save(newKey, merged.snapshot)
    }

    fun setReasoningEffort(effort: String) {
        if (effort !in CHAT_REASONING_EFFORTS) return
        val tabId = _ui.value.active?.id ?: return
        sendSessionConfig(
            tabId = tabId,
            key = "reasoning",
            value = effort,
            sessionScoped = true,
        ) { result ->
            updateTab(tabId) {
                it.copy(
                    reasoningEffort = result,
                    error = null,
                )
            }
        }
    }

    fun setApprovalMode(mode: String) {
        if (mode !in CHAT_APPROVAL_MODES) return
        val tabId = _ui.value.active?.id ?: return
        sendSessionConfig(
            tabId = tabId,
            key = "approval_mode",
            value = mode,
            sessionScoped = false,
        ) { result ->
            updateTab(tabId) {
                it.copy(
                    approvalMode = result,
                    error = null,
                )
            }
        }
    }

    fun setYolo(enabled: Boolean) {
        val tabId = _ui.value.active?.id ?: return
        sendSessionConfig(
            tabId = tabId,
            key = "yolo",
            value = if (enabled) "on" else "off",
            sessionScoped = true,
        ) { result ->
            updateTab(tabId) {
                it.copy(
                    yolo = result == "1" || result.equals("on", ignoreCase = true),
                    error = null,
                )
            }
        }
    }

    private fun sendSessionConfig(
        tabId: String,
        key: String,
        value: String,
        sessionScoped: Boolean,
        onSuccess: (String) -> Unit,
    ) {
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId } ?: return
        val runtime = runtimes[tabId]
        val sessionId = tab.liveSessionId ?: tab.resumeSessionId
        if (runtime == null || (sessionScoped && sessionId.isNullOrBlank())) {
            updateTab(tabId) {
                it.copy(error = "${key.replace('_', ' ')} needs an active Hermes session")
            }
            return
        }
        runtime.eventClient.sendRpc(
            "config.set",
            buildJsonObject {
                put("key", key)
                put("value", value)
                if (sessionScoped) put("session_id", sessionId.orEmpty())
            },
        ) { response ->
            val result = (response as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
            if (result == null) {
                updateTab(tabId) {
                    it.copy(error = "Hermes did not accept the ${key.replace('_', ' ')} change")
                }
            } else {
                onSuccess(result)
            }
        }
    }

    fun setTranscriptMode(mode: TranscriptMode) {
        if (mode == TranscriptMode.TERMINAL && _ui.value.active?.working == true) return
        _ui.update { it.copy(transcriptMode = mode) }
        val tab = _ui.value.active ?: return
        val resume = tab.resumeSessionId ?: tab.liveSessionId
        if (mode == TranscriptMode.READING && !resume.isNullOrBlank()) {
            requestReading(tab.id, resume)
        }
    }

    fun updateDraft(text: String) {
        val tabId = _ui.value.active?.id ?: return
        updateDraft(tabId, text, recordManualEdit = true)
    }

    /** Canonical draft mutation for producers that already know their tab. */
    private fun updateDraft(tabId: String, text: String, recordManualEdit: Boolean) {
        if (recordManualEdit) {
            _ui.value.tabs.firstOrNull { it.id == tabId }?.let { historyFor(it).onManualEdit() }
        }
        applyDraft(tabId, text)
    }

    fun historyUp(): Boolean {
        val tab = _ui.value.active ?: return false
        val replacement = historyFor(tab).previous(tab.draft) ?: return false
        applyDraft(tab.id, replacement)
        return true
    }

    fun historyDown(): Boolean {
        val tab = _ui.value.active ?: return false
        val replacement = historyFor(tab).next() ?: return false
        applyDraft(tab.id, replacement)
        return true
    }

    private fun applyDraft(tabId: String, text: String) {
        val slash = text.startsWith('/')
        val suggestions = SlashCommands.suggest(text, slashCatalog)
        val composer = ComposerRefs.analyze(text, knownComposerAgents())
        updateTab(tabId) { it.copy(draft = text) }
        _ui.update {
            it.copy(
                showSlashPalette = suggestions.isNotEmpty(),
                slashSuggestions = suggestions,
                composerReferences = composer.references,
                composerSuggestions = composer.completions,
            )
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
        updateComposerAnalysis(replacement)
        viewModelScope.launch { chatRepository.saveDraft(replacement) }
    }

    private fun knownComposerAgents(): List<String> = buildList {
        add(TalariaApp.instance.getString(R.string.chat_known_agent_hermes))
        add(TalariaApp.instance.getString(R.string.chat_known_participant_you))
        _ui.value.tabs.mapTo(this) { it.title }
        _ui.value.sessions.mapNotNullTo(this) { it.title }
    }.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }

    private fun updateComposerAnalysis(text: String) {
        val composer = ComposerRefs.analyze(text, knownComposerAgents())
        _ui.update {
            it.copy(
                composerReferences = composer.references,
                composerSuggestions = composer.completions,
            )
        }
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
        if (tab.resumeSessionId == null && tab.liveSessionId == null &&
            pendingLocalCreations.values.none { it.tabId == tabId }
        ) {
            pendingLocalCreations[tab.channelId] = PendingLocalCreation(
                tabId = tabId,
                channelId = tab.channelId,
                profileName = tab.profileName,
            )
        }
        val attachments = tab.imageAttachments
        if (payload.isEmpty() && attachments.isEmpty()) return
        if (attachments.any { it.status == ChatImageAttachmentStatus.UPLOADING }) return
        if (tab.working) {
            if (payload.isNotEmpty()) enqueuePrompt(tab, payload)
            return
        }
        if (attachments.isNotEmpty()) {
            val sessionId = tab.liveSessionId ?: tab.resumeSessionId
            if (sessionId.isNullOrBlank()) {
                updateTab(tabId) { it.copy(error = "Wait for Hermes to finish starting before sending an image") }
                return
            }
            recordSubmittedDraft(tab, payload)
            sendWithImages(tabId, sessionId, payload, attachments.map { it.id }, rt)
            return
        }
        recordSubmittedDraft(tab, payload)
        commitSend(tabId, payload, emptyList(), rt)
    }

    private fun enqueuePrompt(tab: ChatTab, payload: String) {
        recordSubmittedDraft(tab, payload)
        updateTab(tab.id) {
            it.copy(
                draft = if (it.draft.trim() == payload) "" else it.draft,
                queuedPrompts = ComposerQueue.enqueue(it.queuedPrompts, payload),
                error = null,
            )
        }
        _ui.update { it.copy(showSlashPalette = false) }
        viewModelScope.launch { chatRepository.saveDraft("") }
    }

    private fun drainQueuedPrompt(tabId: String) {
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId } ?: return
        val runtime = runtimes[tabId] ?: return
        if (!tab.connected || tab.queuedPrompts.isEmpty() || tab.working) return
        val (next, remaining) = ComposerQueue.dequeue(tab.queuedPrompts)
        if (next == null) return
        updateTab(tabId) { it.copy(queuedPrompts = remaining) }
        commitSend(tabId, next, emptyList(), runtime)
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
            if (serverDictation) stopServerDictation() else stopOnDeviceDictation()
            return
        }
        val activeScope = activeChatScopeId()
        if (activeScope != null && serverSttScope != activeScope) {
            resetServerSttForScope(activeScope)
        }
        if (!speech.hasMicPermission()) {
            reportError("Microphone permission required")
            return
        }
        // Server STT is the primary dictation path — the same engine the Voice
        // settings test exercises. On-device Android dictation is only the
        // fallback for Hermes installs that don't expose server STT; on some
        // devices the on-device recognizer errors out ("speech client error").
        if (!serverSttUnavailable) {
            startServerDictation()
            checkServerSttOnce()
            return
        }
        if (!speech.isAvailable()) {
            reportError("Speech recognition unavailable on this device")
            return
        }
        startOnDeviceDictation()
    }

    private fun activeChatScopeId(): String? =
        container.connectionStore.activeProfile()?.scopeId() ?: boundConnectionScope

    /** Keep capability knowledge isolated to one immutable connection/profile scope. */
    private fun resetServerSttForScope(scopeId: String?) {
        serverSttProbeGeneration += 1
        serverSttScope = scopeId
        val cached = scopeId?.let { serverSttCapabilities[it] }
            ?.takeIf { System.currentTimeMillis() - it.checkedAtMillis < SERVER_STT_CAPABILITY_TTL_MS }
        if (scopeId != null && cached == null) serverSttCapabilities.remove(scopeId)
        serverSttChecked = cached != null
        serverSttUnavailable = cached?.supported == false
    }

    private fun invalidateServerStt(scopeId: String?) {
        if (scopeId.isNullOrBlank()) return
        serverSttCapabilities[scopeId] = CachedServerSttCapability(
            supported = false,
            checkedAtMillis = System.currentTimeMillis(),
        )
        if (serverSttScope == scopeId) {
            serverSttChecked = true
            serverSttUnavailable = true
        }
    }

    private fun cancelVoiceInput() {
        sttJob?.cancel()
        sttJob = null
        voiceRecorder.cancel()
        serverDictation = false
        serverDictationTabId = null
        serverDictationScopeGeneration = null
        _ui.update {
            if (it.listening || it.partialDictation.isNotEmpty()) {
                it.copy(listening = false, partialDictation = "")
            } else {
                it
            }
        }
    }

    private fun isCurrentVoiceScope(scopeId: String?, generation: Long, tabId: String): Boolean =
        scopeId != null && scopeId == activeChatScopeId() &&
            generation == connectionScopeGeneration &&
            _ui.value.tabs.any { it.id == tabId }

    /** One-time capability probe: abort a recording if the server lacks STT. */
    private fun checkServerSttOnce() {
        val scopeId = activeChatScopeId() ?: return
        if (serverSttScope != scopeId) resetServerSttForScope(scopeId)
        val cached = serverSttCapabilities[scopeId]
            ?.takeIf { System.currentTimeMillis() - it.checkedAtMillis < SERVER_STT_CAPABILITY_TTL_MS }
        if (cached != null) {
            serverSttChecked = true
            serverSttUnavailable = !cached.supported
            return
        }
        if (serverSttChecked) return
        serverSttChecked = true
        val probeGeneration = ++serverSttProbeGeneration
        viewModelScope.launch {
            val capabilities = runCatching {
                val root = container.clientFactory.api().getOpenApi()
                VoiceCapabilities.fromOpenApiPaths(root["paths"]?.jsonObject?.keys.orEmpty())
            }.getOrNull()
            if (
                probeGeneration != serverSttProbeGeneration ||
                serverSttScope != scopeId ||
                activeChatScopeId() != scopeId ||
                !isActive
            ) return@launch
            if (capabilities == null) {
                // A transport failure is not proof that this scope lacks STT;
                // permit a later tap to retry the probe.
                serverSttChecked = false
                return@launch
            }
            serverSttCapabilities[scopeId] = CachedServerSttCapability(
                supported = capabilities.serverStt,
                checkedAtMillis = System.currentTimeMillis(),
            )
            serverSttUnavailable = !capabilities.serverStt
            if (!capabilities.serverStt && _ui.value.listening && serverDictation) {
                cancelVoiceInput()
                reportError("Server speech-to-text is unavailable on this Hermes — tap the mic again for on-device dictation")
            }
        }
    }

    /** Records locally for server transcription (settings-test proven path). */
    private fun startServerDictation() {
        val tabId = _ui.value.active?.id ?: return
        val scopeId = activeChatScopeId()
        val generation = connectionScopeGeneration
        voiceRecorder.start()
            .onSuccess {
                if (!isCurrentVoiceScope(scopeId, generation, tabId)) {
                    voiceRecorder.cancel()
                    return@onSuccess
                }
                serverDictation = true
                serverDictationTabId = tabId
                serverDictationScopeGeneration = generation
                _ui.update { it.copy(listening = true, partialDictation = "Listening…") }
            }
            .onFailure { reportError(it.message ?: "Could not start recording") }
    }

    private fun stopServerDictation() {
        serverDictation = false
        val tabId = serverDictationTabId ?: _ui.value.active?.id
        val scopeId = activeChatScopeId()
        val generation = serverDictationScopeGeneration ?: connectionScopeGeneration
        serverDictationTabId = null
        serverDictationScopeGeneration = null
        val recorded = voiceRecorder.stop()
        _ui.update { it.copy(listening = false, partialDictation = "") }
        if (recorded.isFailure) {
            reportError(recorded.exceptionOrNull()?.message ?: "Could not save recording")
            return
        }
        if (tabId == null) return
        val audio = recorded.getOrThrow()
        viewModelScope.launch {
            try {
                val dataUrl = withContext(Dispatchers.IO) {
                    try {
                        val encoded = Base64.getEncoder().encodeToString(audio.file.readBytes())
                        "data:${audio.mimeType};base64,$encoded"
                    } finally {
                        audio.file.delete()
                    }
                }
                val response = container.clientFactory.api().transcribeAudio(
                    VoiceTranscriptionRequest(dataUrl = dataUrl, mimeType = audio.mimeType),
                    profile = profileNameForTab(tabId),
                )
                val transcript = response.transcript.trim()
                if (!response.ok || transcript.isBlank()) {
                    throw IllegalStateException(response.error ?: "Hermes returned no transcript")
                }
                if (!isCurrentVoiceScope(scopeId, generation, tabId)) return@launch
                val merged = (_ui.value.tabs.firstOrNull { it.id == tabId }?.draft.orEmpty() + " " + transcript).trim()
                // STT is a tab-scoped producer, but it must use the same
                // analysis/persistence path as ordinary composer edits.
                updateDraft(tabId, merged, recordManualEdit = false)
            } catch (error: HttpException) {
                if (!isCurrentVoiceScope(scopeId, generation, tabId)) return@launch
                if (error.code() == 404) {
                    invalidateServerStt(scopeId)
                    updateTab(tabId) { it.copy(error = "Server speech-to-text is unavailable on this Hermes") }
                } else {
                    updateTab(tabId) { it.copy(error = error.message() ?: "Server transcription failed") }
                }
            } catch (error: Throwable) {
                if (isCurrentVoiceScope(scopeId, generation, tabId)) {
                    updateTab(tabId) { it.copy(error = error.message ?: "Server transcription failed") }
                }
            }
        }
    }

    /** On-device Android dictation fallback for servers without STT. */
    private fun startOnDeviceDictation() {
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

    private fun stopOnDeviceDictation() {
        sttJob?.cancel()
        sttJob = null
        _ui.update { it.copy(listening = false, partialDictation = "") }
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
            persistChatState(it.profileName)
        }
    }

    private fun handlePtyEvent(tabId: String, event: PtyEvent) {
        val profileName = profileNameForTab(tabId)
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId }
        when (event) {
            is PtyEvent.Connected -> {
                tab?.let { it.liveSessionId ?: it.resumeSessionId }
                    ?.let { ProfileRegistry.markActive(profileName, it) }
                updateTab(tabId) { it.copy(connecting = false, connected = true) }
                applyProfileRegistry(ProfileRegistry.state.value)
            }
            is PtyEvent.Output -> appendAssistant(tabId, event.text)
            is PtyEvent.Closed -> {
                AgentTaskNotificationService.stopWatching(TalariaApp.instance, tabId)
                finalizeAssistant(tabId)
                if (tab?.resumeSessionId == null && tab?.liveSessionId == null) {
                    removePendingLocalCreation(tabId)
                }
                tab?.let { it.liveSessionId ?: it.resumeSessionId }
                    ?.let { ProfileRegistry.markDisconnected(profileName, it) }
                updateTab(tabId) { it.copy(connected = false, connecting = false, working = false) }
                applyProfileRegistry(ProfileRegistry.state.value)
            }
            is PtyEvent.Failure -> {
                AgentTaskNotificationService.stopWatching(TalariaApp.instance, tabId)
                if (tab?.resumeSessionId == null && tab?.liveSessionId == null) {
                    removePendingLocalCreation(tabId)
                }
                tab?.let { it.liveSessionId ?: it.resumeSessionId }
                    ?.let { ProfileRegistry.markDisconnected(profileName, it) }
                updateTab(tabId) { it.copy(error = event.message, connecting = false, connected = false, working = false) }
                container.notifier.notifyError("Chat disconnected", event.message)
                applyProfileRegistry(ProfileRegistry.state.value)
            }
        }
    }

    /** Reading mode = clean transcript from the sessions REST API, per tab. */
    private fun startReadingPoll(tabId: String) {
        val rt = runtimes[tabId] ?: return
        rt.readingJob?.cancel()
        rt.readingJob = viewModelScope.launch {
            while (runtimes[tabId] === rt && isActive) {
                val id = discoverSessionForTab(tabId)
                if (id != null) {
                    val tab = _ui.value.tabs.firstOrNull { it.id == tabId }
                    if (tab?.liveSessionId == null && tab?.resumeSessionId != id) {
                        bindSession(tabId, id)
                        // A tab just claimed its session — snapshot so a cold start
                        // resumes this thread (and every sibling) with its title.
                        persistChatState(tab?.profileName ?: profileNameForTab(tabId))
                    }
                    // Await each read in this poll job. One-shot refreshes
                    // cancel their own request and advance the generation so
                    // an older equal-length response cannot overwrite it.
                    rt.readingRequestJob?.cancel()
                    val generation = ++rt.readingGeneration
                    val request = launch {
                        loadReading(tabId, id, rt, generation)
                    }
                    rt.readingRequestJob = request
                    try {
                        request.join()
                    } finally {
                        if (rt.readingRequestJob === request) rt.readingRequestJob = null
                    }
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
        val list = sessionsForProfile(tab.profileName)
        // This tab's session is one that appeared AFTER it opened and isn't owned
        // by another tab — so several concurrent agents each map to their own.
        val candidate = list
            .filter { it.id !in claimedSessions && it.id !in rt.baselineSessions }
            .maxByOrNull { MultiProfileSession(tab.profileName, it).recency }
            ?: return null
        if (!claimSession(tabId, candidate.id)) return null
        return candidate.id
    }

    private fun requestReading(tabId: String, sessionId: String) {
        val rt = runtimes[tabId] ?: return
        rt.readingRequestJob?.cancel()
        val generation = ++rt.readingGeneration
        rt.readingRequestJob = viewModelScope.launch {
            try {
                loadReading(tabId, sessionId, rt, generation)
            } finally {
                if (rt.readingRequestJob === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    rt.readingRequestJob = null
                }
            }
        }
    }

    private suspend fun loadReading(
        tabId: String,
        sessionId: String,
        rt: SessionRuntime,
        generation: Long,
    ) {
        rt.readingMutex.withLock {
            if (runtimes[tabId] !== rt || rt.readingGeneration != generation) {
                return@withLock
            }
            loadMessagesForProfile(tabId, sessionId).onSuccess { msgs ->
                if (runtimes[tabId] !== rt || rt.readingGeneration != generation) {
                    return@onSuccess
                }
                val lines = msgs.mapIndexed { idx, m ->
                    ChatLine(id = "$sessionId-$idx", role = m.role ?: "assistant", text = m.content.orEmpty())
                }.filter {
                    it.text.isNotBlank() &&
                        // Only user and assistant messages belong in the reading transcript.
                        // Tool/system messages are internal machinery — they never appear in
                        // ChatGPT-style UIs and just make the transcript look janky.
                        it.role in setOf("user", "assistant")
                }
                val rt = runtimes[tabId] ?: return@onSuccess
                var shouldDrainQueue = false
                updateTab(tabId) { tab ->
                    // A slower response for the pre-compression/pre-switch id
                    // must not overwrite the transcript after bindSession has
                    // re-anchored this tab to a newer live session.
                    if (tab.liveSessionId != sessionId && tab.resumeSessionId != sessionId) {
                        return@updateTab tab
                    }
                    // Never let a transient/empty server read wipe optimistic messages;
                    // only replace when the server transcript is a superset of what we show.
                    // Equality guard: the 2.5s poll must not churn a full recomposition
                    // when nothing actually changed.
                    if (lines.isNotEmpty() && lines != tab.readingMessages) {
                        rt.readingSessionId = sessionId
                        // The turn is done once the server transcript ends in an
                        // assistant message — drop the working indicator + tool.
                        val replyArrived = lines.lastOrNull()?.role == "assistant"
                        shouldDrainQueue = replyArrived && tab.working && !rt.sidecarEventsSeen
                        tab.copy(
                            readingMessages = lines,
                            working = if (replyArrived) false else tab.working,
                            tools = if (replyArrived) emptyList() else tab.tools,
                        )
                    } else {
                        tab
                    }
                }
                if (shouldDrainQueue) drainQueuedPrompt(tabId)
            }
        }
    }

    private suspend fun sessionsForProfile(profileName: String): List<SessionSummary> {
        if (profileName == profileNameForActiveConnection()) {
            return hermesRepository.refreshSessions().getOrNull().orEmpty()
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                parseSessionsForProfile(
                    container.clientFactory.api().getSessionsForProfile(profile = profileName),
                )
            }.getOrElse { emptyList() }
        }
    }

    private suspend fun loadMessagesForProfile(tabId: String, sessionId: String): Result<List<SessionMessage>> {
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId }
        val profileName = tab?.profileName ?: profileNameForActiveConnection()
        if (profileName == profileNameForActiveConnection()) {
            return hermesRepository.loadMessages(sessionId)
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                container.clientFactory.api()
                    .getSessionMessages(sessionId, profile = profileName)
                    .messages
            }
        }
    }

    private fun parseSessionsForProfile(raw: kotlinx.serialization.json.JsonElement): List<SessionSummary> {
        val array = when (raw) {
            is kotlinx.serialization.json.JsonArray -> raw
            is kotlinx.serialization.json.JsonObject ->
                raw["sessions"] as? kotlinx.serialization.json.JsonArray
                    ?: raw["results"] as? kotlinx.serialization.json.JsonArray
            else -> null
        }
        return array.orEmpty().mapNotNull {
            runCatching { com.hermesgadget.talaria.core.network.JsonConfig.json.decodeFromJsonElement<SessionSummary>(it) }
                .getOrNull()
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
                event.sessionId?.let { ProfileRegistry.markStreaming(profileNameForTab(tabId), it) }
                runtimes[tabId]?.sidecarEventsSeen = true
                runtimes[tabId]?.sidecarAssistantBuffer = StringBuilder()
                updateTab(tabId) {
                    it.copy(working = true, error = null)
                }
            }
            is HermesSideEvent.MessageDelta -> {
                bindSession(tabId, event.sessionId)
                event.sessionId?.let { ProfileRegistry.markStreaming(profileNameForTab(tabId), it) }
                runtimes[tabId]?.sidecarEventsSeen = true
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
                event.sessionId?.let { ProfileRegistry.markStreaming(profileNameForTab(tabId), it) }
                // Interim commentary can contain model thought/reasoning. It is
                // intentionally neither displayed nor added to the final buffer.
                updateTab(tabId) { it.copy(working = true) }
            }
            is HermesSideEvent.MessageComplete -> completeSidecarMessage(tabId, event)
            is HermesSideEvent.BackgroundComplete -> Unit
            is HermesSideEvent.Status -> {
                bindSession(tabId, event.sessionId)
                event.sessionId?.let { ProfileRegistry.markStreaming(profileNameForTab(tabId), it) }
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
                event.sessionId?.let { ProfileRegistry.markStreaming(profileNameForTab(tabId), it) }
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
            is HermesSideEvent.SessionInfo -> {
                bindSession(tabId, event.sessionId)
                event.sessionId?.let { ProfileRegistry.markActive(profileNameForTab(tabId), it) }
                updateTab(tabId) {
                    it.copy(
                        modelLabel = event.model ?: it.modelLabel,
                        modelConnected = it.modelConnected ?: true,
                        provider = event.provider ?: it.provider,
                        reasoningEffort = event.reasoningEffort ?: it.reasoningEffort,
                        approvalMode = event.approvalMode ?: it.approvalMode,
                        yolo = event.yolo ?: it.yolo,
                    )
                }
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
        event.sessionId?.let { ProfileRegistry.markIdle(profileNameForTab(tabId), it) }
        val rt = runtimes[tabId] ?: return
        rt.sidecarEventsSeen = true
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
        AgentTaskNotificationService.stopWatching(TalariaApp.instance, tabId)
        drainQueuedPrompt(tabId)
        // Only read aloud the tab the user is actually looking at — a background
        // auto-opened session finishing must not speak over the active one.
        if (full.isNotEmpty() && _ui.value.activeTabId == tabId) tts.speak(full)
        applyProfileRegistry(ProfileRegistry.state.value)
    }

    /** Prefer the session id carried by live gateway events over polling heuristics. */
    private fun bindSession(tabId: String, sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.liveSessionId == sessionId) return
        if (!claimSession(tabId, sessionId)) return
        val oldHistoryKey = historyKey(tab)
        tab.liveSessionId?.let { releaseSession(tabId, it) }
        removePendingLocalCreation(tabId)
        updateTab(tabId) { it.copy(liveSessionId = sessionId) }
        ProfileRegistry.markActive(tab.profileName, sessionId)
        migrateInputHistory(oldHistoryKey, sessionId)
        runtimes[tabId]?.readingSessionId = sessionId
        persistChatState(tab.profileName)
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
            managementProfile = tab.profileName,
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
            managementProfile = profileName,
        )
    }

    override fun onCleared() {
        cancelVoiceInput()
        voiceRecorder.cancel()
        slashCompletionJob?.cancel()
        scopeLoadJob?.cancel()
        sessionPollJob?.cancel()
        _ui.value.tabs.forEach { tab ->
            (tab.liveSessionId ?: tab.resumeSessionId)?.let {
                ProfileRegistry.markDisconnected(tab.profileName, it)
            }
        }
        runtimes.values.forEach {
            it.collectJob?.cancel()
            it.sideJob?.cancel()
            it.readingJob?.cancel()
            it.readingRequestJob?.cancel()
            it.session.close()
            it.eventClient.dispose()
        }
        runtimes.clear()
        pendingLocalCreations.clear()
        synchronized(sessionOwnershipLock) {
            sessionOwners.clear()
            claimedSessions.clear()
        }
        pendingImages.clear()
        super.onCleared()
    }

    companion object {
        private const val SESSION_POLL_INTERVAL_MS = 30_000L
        private const val LOCAL_SESSION_DISCOVERY_TIMEOUT_MS = 60_000L
        private const val SERVER_STT_CAPABILITY_TTL_MS = 5 * 60 * 1000L
        private val ARGUMENT_HINT = Regex("""\[[^]]+]|<[^>]+>""")

        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel() as T
        }
    }
}
