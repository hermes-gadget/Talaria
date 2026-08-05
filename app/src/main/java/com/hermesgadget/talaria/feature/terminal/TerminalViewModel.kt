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

package com.hermesgadget.talaria.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.repo.ChatRepository
import com.hermesgadget.talaria.core.network.HermesEventClient
import com.hermesgadget.talaria.core.network.HermesEventScope
import com.hermesgadget.talaria.core.network.HermesSideEvent
import com.hermesgadget.talaria.core.network.PtyEvent
import com.hermesgadget.talaria.core.network.PtyWebSocketSession
import com.hermesgadget.talaria.domain.model.TerminalBackendsResponse
import com.hermesgadget.talaria.core.util.suspendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import com.hermesgadget.talaria.domain.model.effectiveManagementProfile

sealed interface TerminalConnectionState {
    data class Disconnected(
        val explicit: Boolean = false,
        val reason: String? = null,
    ) : TerminalConnectionState

    data object Connecting : TerminalConnectionState
    data object Connected : TerminalConnectionState
    data class Failed(val message: String) : TerminalConnectionState
}

data class TerminalUiState(
    val connection: TerminalConnectionState = TerminalConnectionState.Disconnected(),
    val output: String = "",
    val input: String = "",
    val sidecarError: String? = null,
    val backends: TerminalBackendsResponse? = null,
    val backendsLoading: Boolean = false,
    val backendSelecting: String? = null,
    val backendError: String? = null,
)

/**
 * Raw terminal surface: one PTY and one same-channel Hermes sidecar per
 * ViewModel. The nav destination owns this ViewModel, so changing top-level
 * tabs does not discard the live output or command history.
 */
class TerminalViewModel(
    private val chatRepository: ChatRepository = TalariaApp.instance.container.chatRepository,
) : ViewModel() {
    private val container = TalariaApp.instance.container
    private val _ui = MutableStateFlow(TerminalUiState())
    val ui: StateFlow<TerminalUiState> = _ui.asStateFlow()

    private val output = TerminalOutputBuffer()
    private val history = TerminalInputHistory()
    private var pty: PtyWebSocketSession? = null
    private var eventClient: HermesEventClient? = null
    private var ptyJob: Job? = null
    private var sidecarJob: Job? = null
    private var connectionGeneration = 0L
    private var explicitlyDisconnected = false

    /** Start once a profile exists; later resume calls reconnect only if needed. */
    fun ensureStarted() {
        if (container.connectionStore.activeProfile() == null) {
            _ui.update {
                it.copy(
                    connection = TerminalConnectionState.Disconnected(reason = "No active connection"),
                )
            }
            return
        }
        if (explicitlyDisconnected) return
        if (_ui.value.connection is TerminalConnectionState.Connected ||
            _ui.value.connection is TerminalConnectionState.Connecting
        ) return
        connect()
    }

    /** Called from the screen's STARTED lifecycle block after app/tab resume. */
    fun reconnectOnResume() {
        if (!explicitlyDisconnected) ensureStarted()
    }

    fun loadBackends() {
        if (_ui.value.backendsLoading) return
        val profile = container.connectionStore.activeProfile()?.effectiveManagementProfile() ?: return
        viewModelScope.launch {
            _ui.update { it.copy(backendsLoading = true, backendError = null) }
            suspendResult {
                container.clientFactory.api().getTerminalBackends(profile)
            }.fold(
                onSuccess = { response ->
                    _ui.update {
                        it.copy(
                            backends = response,
                            backendsLoading = false,
                            backendSelecting = null,
                            backendError = null,
                        )
                    }
                },
                onFailure = { failure ->
                    _ui.update {
                        it.copy(
                            backendsLoading = false,
                            backendError = failure.message,
                        )
                    }
                },
            )
        }
    }

    fun selectBackend(backend: String) {
        val requested = backend.trim()
        val profile = container.connectionStore.activeProfile()?.effectiveManagementProfile() ?: return
        if (requested.isEmpty() || _ui.value.backendSelecting != null) return
        viewModelScope.launch {
            _ui.update { it.copy(backendSelecting = requested, backendError = null) }
            suspendResult {
                container.clientFactory.api().selectTerminalBackend(
                    body = buildJsonObject { put("backend", requested) },
                    profile = profile,
                )
            }.fold(
                onSuccess = {
                    _ui.update { state ->
                        state.copy(
                            backends = state.backends?.copy(
                                active = requested,
                                backends = state.backends.backends.map { row ->
                                    row.copy(active = row.name == requested)
                                },
                            ),
                            backendSelecting = null,
                            backendError = null,
                        )
                    }
                    loadBackends()
                },
                onFailure = { failure ->
                    _ui.update {
                        it.copy(
                            backendSelecting = null,
                            backendError = failure.message,
                        )
                    }
                },
            )
        }
    }

    fun reconnect() {
        explicitlyDisconnected = false
        connect()
    }

    /** Stop all sockets and keep the terminal visibly, explicitly disconnected. */
    fun disconnect() {
        explicitlyDisconnected = true
        connectionGeneration += 1
        closeTransport()
        _ui.update {
            it.copy(
                connection = TerminalConnectionState.Disconnected(explicit = true),
                sidecarError = null,
            )
        }
    }

    fun clearOutput() {
        _ui.update { it.copy(output = output.clear()) }
    }

    fun updateInput(text: String) {
        if (text == _ui.value.input) return
        history.onManualEdit()
        _ui.update { it.copy(input = text) }
    }

    fun historyUp(): Boolean {
        val replacement = history.previous(_ui.value.input) ?: return false
        _ui.update { it.copy(input = replacement) }
        return true
    }

    fun historyDown(): Boolean {
        val replacement = history.next() ?: return false
        _ui.update { it.copy(input = replacement) }
        return true
    }

    fun sendInput() {
        val session = pty ?: return
        if (_ui.value.connection !is TerminalConnectionState.Connected) return
        val line = _ui.value.input
        if (line.isNotBlank()) history.record(line)
        session.sendText(line)
        _ui.update { it.copy(input = "", sidecarError = null) }
    }

    private fun connect() {
        val snapshot = container.clientFactory.snapshot()
        if (snapshot == null) {
            _ui.update {
                it.copy(
                    connection = TerminalConnectionState.Disconnected(reason = "No active connection"),
                )
            }
            return
        }

        val generation = ++connectionGeneration
        closeTransport()
        val channel = UUID.randomUUID().toString()
        val sidecar = HermesEventClient(
            container.clientFactory,
            container.wsAuthHelper,
            fixedSnapshot = snapshot,
            fixedEventScope = HermesEventScope(
                connectionId = snapshot.connectionId,
                managementProfile = snapshot.managementProfile,
                channelId = channel,
                tabId = "terminal",
            ),
        )
        val (session, flow) = chatRepository.openPty(snapshot, channelId = channel)
        pty = session
        eventClient = sidecar
        _ui.update {
            it.copy(
                connection = TerminalConnectionState.Connecting,
                sidecarError = null,
            )
        }

        // Keep the same sidecar channel contract as Chat. The raw terminal has
        // no transcript semantics, but transport/auth failures remain visible.
        sidecar.start(channel)
        sidecarJob = viewModelScope.launch {
            sidecar.events.collect { event ->
                if (generation != connectionGeneration) return@collect
                if (event is HermesSideEvent.TransportError) {
                    _ui.update {
                        it.copy(sidecarError = "${event.socket}: ${event.message}")
                    }
                }
            }
        }
        ptyJob = viewModelScope.launch {
            try {
                flow.collect { event -> handlePtyEvent(generation, event) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (generation == connectionGeneration) {
                    sidecar.stop()
                    _ui.update {
                        it.copy(connection = TerminalConnectionState.Failed(
                            failure.message ?: "Terminal connection failed",
                        ))
                    }
                }
            }
        }
    }

    private fun handlePtyEvent(generation: Long, event: PtyEvent) {
        if (generation != connectionGeneration) return
        when (event) {
            is PtyEvent.Connected -> _ui.update {
                it.copy(
                    connection = TerminalConnectionState.Connected,
                    sidecarError = null,
                )
            }
            is PtyEvent.Output -> {
                val raw = event.raw.ifEmpty { event.text }
                output.append(raw)
                _ui.update { it.copy(output = output.displayText) }
            }
            is PtyEvent.Closed -> {
                eventClient?.stop()
                _ui.update {
                    it.copy(
                        connection = TerminalConnectionState.Disconnected(reason = "Connection closed"),
                    )
                }
            }
            is PtyEvent.Failure -> {
                eventClient?.stop()
                _ui.update {
                    it.copy(connection = TerminalConnectionState.Failed(event.message))
                }
            }
        }
    }

    private fun closeTransport() {
        ptyJob?.cancel()
        sidecarJob?.cancel()
        ptyJob = null
        sidecarJob = null
        pty?.close()
        pty = null
        eventClient?.dispose()
        eventClient = null
    }

    override fun onCleared() {
        explicitlyDisconnected = true
        connectionGeneration += 1
        closeTransport()
            }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TerminalViewModel() as T
        }
    }
}
