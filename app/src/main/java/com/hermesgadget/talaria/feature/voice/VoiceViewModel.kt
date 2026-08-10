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

package com.hermesgadget.talaria.feature.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.network.ConnectionScope
import com.hermesgadget.talaria.core.network.ConnectionScopeObserver
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.core.network.decodeJsonResponse
import com.hermesgadget.talaria.core.voice.SpeechCoordinator
import com.hermesgadget.talaria.core.voice.SttEvent
import com.hermesgadget.talaria.domain.model.VoiceCapability
import com.hermesgadget.talaria.domain.model.VoiceCapabilities
import com.hermesgadget.talaria.domain.model.VoiceHistoryItem
import com.hermesgadget.talaria.domain.model.VoiceHistoryKind
import com.hermesgadget.talaria.domain.model.VoiceSpeakRequest
import com.hermesgadget.talaria.domain.model.VoiceSpeakResponse
import com.hermesgadget.talaria.domain.model.VoiceTranscriptionRequest
import com.hermesgadget.talaria.domain.model.VoiceTranscriptionResponse
import com.hermesgadget.talaria.domain.model.effectiveManagementProfile
import com.hermesgadget.talaria.core.util.suspendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

class VoiceViewModel(
    api: HermesApi? = null,
    context: Context,
    private val speech: SpeechCoordinator,
    private val profileProvider: () -> String? = { null },
    private val apiProvider: (ConnectionScope?) -> HermesApi = { scope ->
        scope?.snapshot?.let { TalariaApp.instance.container.clientFactory.api(it) }
            ?: TalariaApp.instance.container.clientFactory.api()
    },
    private val scopeFlow: StateFlow<ConnectionScope?>? = null,
) : ViewModel() {
    private val fixedApi = api
    private var boundApi: HermesApi = api ?: apiProvider(scopeFlow?.value)
    private var boundScope: ConnectionScope? = scopeFlow?.value
    private var scopeObserver: ConnectionScopeObserver? = null
    private val appContext = context.applicationContext
    private val recorder = VoiceRecorder(appContext)
    private val audioPlayer = VoiceAudioPlayer(appContext)
    private val _ui = MutableStateFlow(
        VoiceUiState(
            androidSpeechAvailable = speech.isAvailable(),
            microphonePermissionGranted = speech.hasMicPermission(),
        ),
    )
    val ui: StateFlow<VoiceUiState> = _ui.asStateFlow()

    private var fallbackJob: Job? = null
    private var capabilityJob: Job? = null
    private var elevenLabsJob: Job? = null
    private var transcriptionJob: Job? = null
    private var speakJob: Job? = null

    init {
        scopeObserver = scopeFlow?.let { flow ->
            ConnectionScopeObserver(flow, viewModelScope) { next -> rebind(next) }
        }
    }

    private fun rebind(next: ConnectionScope?) {
        boundScope = next
        boundApi = fixedApi ?: apiProvider(next)
        capabilityJob?.cancel()
        elevenLabsJob?.cancel()
        transcriptionJob?.cancel()
        speakJob?.cancel()
        fallbackJob?.cancel()
        recorder.cancel()
        audioPlayer.stop()
        _ui.value = VoiceUiState(
            androidSpeechAvailable = speech.isAvailable(),
            microphonePermissionGranted = speech.hasMicPermission(),
        )
        if (next != null) {
            refreshCapabilities()
            refreshElevenLabsVoices()
        }
    }

    private fun isCurrentScope(expected: ConnectionScope?): Boolean =
        scopeObserver?.isCurrent(expected) != false

    private fun profile(): String? =
        (boundScope?.managementProfile ?: profileProvider())?.takeIf { it.isNotBlank() }

    fun refreshCapabilities() {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val requestApi = boundApi
        val current = _ui.value
        if (current.checkingCapabilities || current.phase in setOf(
                VoicePhase.RECORDING,
                VoicePhase.TRANSCRIBING,
                VoicePhase.PLAYING,
            )
        ) return

        _ui.update { it.copy(checkingCapabilities = true, error = null) }
        capabilityJob?.cancel()
        capabilityJob = viewModelScope.launch {
            suspendResult { VoiceCapabilities.fromOpenApiPaths(openApiPaths(requestApi.getOpenApi())) }
                .fold(
                    onSuccess = { capabilities ->
                        if (isCurrentScope(expectedScope)) {
                            _ui.update { state ->
                                state.copy(
                                    phase = VoiceStateMachine.reduce(
                                        state.phase,
                                        if (capabilities.serverStt || capabilities.serverTts) VoiceEvent.ServerAvailable
                                        else VoiceEvent.ServerUnavailable,
                                    ),
                                    capabilityChecked = true,
                                    checkingCapabilities = false,
                                    capabilities = capabilities,
                                    error = null,
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        if (isCurrentScope(expectedScope)) {
                            _ui.update { state ->
                                state.copy(
                                    phase = VoiceStateMachine.reduce(state.phase, VoiceEvent.ServerUnavailable),
                                    capabilityChecked = true,
                                    checkingCapabilities = false,
                                    capabilities = VoiceCapabilities(),
                                    error = "Could not inspect Hermes voice capabilities: ${messageFor(error)}",
                                )
                            }
                        }
                    },
                )
        }
    }

    fun refreshElevenLabsVoices() {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val requestApi = boundApi
        if (_ui.value.elevenLabsLoading) return
        _ui.update { it.copy(elevenLabsLoading = true, elevenLabsError = null) }
        elevenLabsJob?.cancel()
        elevenLabsJob = viewModelScope.launch {
            suspendResult { parseElevenLabsVoices(requestApi.getElevenLabsVoices()) }
                .fold(
                    onSuccess = { payload ->
                        if (isCurrentScope(expectedScope)) {
                            _ui.update {
                                it.copy(
                                    elevenLabsAvailable = payload.available,
                                    elevenLabsVoices = payload.voices,
                                    elevenLabsError = payload.error,
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        if (isCurrentScope(expectedScope)) {
                            _ui.update {
                                it.copy(
                                    elevenLabsAvailable = false,
                                    elevenLabsVoices = emptyList(),
                                    elevenLabsError = messageFor(error),
                                )
                            }
                        }
                    },
                )
            if (isCurrentScope(expectedScope)) {
                _ui.update { it.copy(elevenLabsLoading = false) }
            }
        }
    }

    fun updateMicrophonePermission(granted: Boolean) {
        _ui.update {
            it.copy(
                microphonePermissionGranted = granted,
                error = if (granted) it.error else "Microphone permission denied",
            )
        }
    }

    fun startRecording() {
        if (scopeFlow != null && boundScope == null) return
        val state = _ui.value
        if (!state.capabilities.serverStt) {
            _ui.update { it.copy(error = "Hermes server STT is unavailable") }
            return
        }
        if (state.phase != VoicePhase.IDLE) return
        if (!speech.hasMicPermission()) {
            updateMicrophonePermission(false)
            return
        }

        recorder.start(onLimitReached = {
            stopRecordingAndTranscribe(autoStopped = true)
        })
            .onSuccess {
                _ui.update { it.copy(
                    phase = VoiceStateMachine.reduce(it.phase, VoiceEvent.StartRecording),
                    error = null,
                    partialText = "",
                ) }
            }
            .onFailure { error ->
                _ui.update { it.copy(error = messageFor(error, "Could not start recording")) }
            }
    }

    fun stopRecordingAndTranscribe() {
        stopRecordingAndTranscribe(autoStopped = false)
    }

    private fun stopRecordingAndTranscribe(autoStopped: Boolean) {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val requestApi = boundApi
        val requestProfile = profile()
        if (_ui.value.phase != VoicePhase.RECORDING || _ui.value.fallbackListening) return

        val recorded = recorder.stop()
        if (recorded.isFailure) {
            _ui.update {
                it.copy(
                    phase = VoiceStateMachine.reduce(it.phase, VoiceEvent.TranscriptionFinished),
                    error = messageFor(recorded.exceptionOrNull(), "Could not save recording"),
                )
            }
            return
        }

        _ui.update { it.copy(
            phase = VoiceStateMachine.reduce(it.phase, VoiceEvent.RecordingFinished),
            error = if (autoStopped) "Recording limit reached; transcribing…" else null,
        ) }
        val audio = recorded.getOrThrow()
        if (!audio.file.isFile || audio.file.length() !in 1L..VoiceAudioLimits.MAX_RECORDING_BYTES) {
            audio.file.delete()
            _ui.update { state ->
                state.copy(
                    phase = VoiceStateMachine.reduce(state.phase, VoiceEvent.TranscriptionFinished),
                    error = "Recording exceeded the ${VoiceAudioLimits.MAX_RECORDING_BYTES} byte audio limit",
                )
            }
            return
        }
        transcriptionJob?.cancel()
        transcriptionJob = viewModelScope.launch {
            suspendResult {
                val dataUrl = withContext(Dispatchers.IO) {
                    try {
                        encodeRecordedVoiceDataUrl(audio.file, audio.mimeType)
                    } finally {
                        audio.file.delete()
                    }
                }
                val response = requestApi.transcribeAudioBody(
                    VoiceTranscriptionRequest(dataUrl = dataUrl, mimeType = audio.mimeType),
                    profile = requestProfile,
                ).decodeJsonResponse<VoiceTranscriptionResponse>()
                val transcript = response.transcript.trim()
                if (!response.ok || transcript.isBlank()) {
                    throw IllegalStateException(response.error ?: "Hermes returned no transcript")
                }
                transcript
            }.fold(
                onSuccess = { transcript ->
                    if (isCurrentScope(expectedScope)) {
                        _ui.update { state ->
                            state.copy(
                                text = transcript,
                                partialText = "",
                                history = addHistory(state.history, transcript, VoiceHistoryKind.SERVER_TRANSCRIPTION),
                            )
                        }
                        _ui.update { it.copy(
                            phase = VoiceStateMachine.reduce(it.phase, VoiceEvent.TranscriptionFinished),
                            error = null,
                        ) }
                    }
                },
                onFailure = { error ->
                    if (isCurrentScope(expectedScope)) {
                        _ui.update { it.copy(
                            phase = VoiceStateMachine.reduce(it.phase, VoiceEvent.TranscriptionFinished),
                            error = messageFor(error, "Server transcription failed"),
                        ) }
                        if (error is HttpException && error.code() == 404) {
                            markCapabilityMissing(
                                VoiceCapability.SERVER_STT,
                                "Hermes no longer exposes server STT",
                                expectedScope,
                            )
                        }
                    }
                },
            )
        }
    }

    fun startOnDeviceDictation() {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val state = _ui.value
        if (!state.androidSpeechAvailable || state.phase !in setOf(VoicePhase.IDLE, VoicePhase.UNAVAILABLE)) {
            _ui.update { it.copy(error = "On-device speech recognition is unavailable on this device") }
            return
        }
        if (!speech.hasMicPermission()) {
            updateMicrophonePermission(false)
            return
        }

        fallbackJob?.cancel()
        _ui.update { it.copy(
            phase = VoiceStateMachine.reduce(it.phase, VoiceEvent.StartRecording),
            fallbackListening = true,
            partialText = "",
            error = null,
        ) }
        fallbackJob = viewModelScope.launch {
            try {
                speech.listen(continuous = false).collect { event ->
                    if (!isCurrentScope(expectedScope)) return@collect
                    when (event) {
                        is SttEvent.Partial -> _ui.update { it.copy(partialText = event.text) }
                        is SttEvent.Final -> {
                            val text = event.text.trim()
                            if (text.isNotBlank()) {
                                _ui.update { state ->
                                    state.copy(
                                        text = text,
                                        partialText = "",
                                        history = addHistory(state.history, text, VoiceHistoryKind.ON_DEVICE_DICTATION),
                                    )
                                }
                            }
                            _ui.update { it.copy(
                                phase = VoiceStateMachine.reduce(it.phase, VoiceEvent.TranscriptionFinished),
                                fallbackListening = false,
                            ) }
                        }
                        is SttEvent.Error -> {
                            _ui.update { it.copy(error = event.message) }
                            _ui.update { state ->
                                state.copy(
                                    phase = VoiceStateMachine.reduce(state.phase, VoiceEvent.TranscriptionFinished),
                                    fallbackListening = false,
                                    partialText = "",
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            } finally {
                if (!currentCoroutineContext().isActive || !isCurrentScope(expectedScope)) return@launch
                _ui.update { state ->
                    state.copy(
                        phase = if (state.phase == VoicePhase.RECORDING) {
                            VoiceStateMachine.reduce(state.phase, VoiceEvent.TranscriptionFinished)
                        } else state.phase,
                        fallbackListening = false,
                    )
                }
            }
        }
    }

    fun stopOnDeviceDictation() {
        fallbackJob?.cancel()
        fallbackJob = null
        _ui.update { state ->
            state.copy(
                phase = if (state.phase == VoicePhase.RECORDING) {
                    VoiceStateMachine.reduce(state.phase, VoiceEvent.TranscriptionFinished)
                } else state.phase,
                fallbackListening = false,
                partialText = "",
            )
        }
    }

    fun updateText(text: String) {
        _ui.update { it.copy(text = text, error = null) }
    }

    fun selectHistory(item: VoiceHistoryItem) {
        updateText(item.text)
    }

    fun speakText() {
        val expectedScope = boundScope
        if (scopeFlow != null && expectedScope == null) return
        val requestApi = boundApi
        val requestProfile = profile()
        val state = _ui.value
        val text = state.text.trim()
        if (!state.capabilities.serverTts) {
            _ui.update { it.copy(error = "Hermes server TTS is unavailable") }
            return
        }
        if (state.phase != VoicePhase.IDLE) return
        if (text.isBlank()) {
            _ui.update { it.copy(error = "Enter text to speak") }
            return
        }

        _ui.update { it.copy(
            phase = VoiceStateMachine.reduce(it.phase, VoiceEvent.StartPlayback),
            error = null,
        ) }
        speakJob?.cancel()
        speakJob = viewModelScope.launch {
            suspendResult {
                val response = requestApi.speakTextBody(
                    VoiceSpeakRequest(text),
                    profile = requestProfile,
                ).decodeJsonResponse<VoiceSpeakResponse>()
                if (!response.ok || response.dataUrl.isBlank()) {
                    throw IllegalStateException(response.error ?: "Hermes returned no audio")
                }
                response.dataUrl
            }.fold(
                onSuccess = { dataUrl ->
                    if (!isCurrentScope(expectedScope)) return@fold
                    _ui.update { current ->
                        current.copy(history = addHistory(current.history, text, VoiceHistoryKind.SERVER_SPEECH))
                    }
                    audioPlayer.play(
                        dataUrl,
                        onCompleted = { finishPlayback(expectedScope = expectedScope) },
                        onError = { error -> finishPlayback(error, expectedScope) },
                    )
                },
                onFailure = { error ->
                    if (isCurrentScope(expectedScope)) {
                        finishPlayback(messageFor(error, "Server speech synthesis failed"), expectedScope)
                        if (error is HttpException && error.code() == 404) {
                            markCapabilityMissing(
                                VoiceCapability.SERVER_TTS,
                                "Hermes no longer exposes server TTS",
                                expectedScope,
                            )
                        }
                    }
                },
            )
        }
    }

    fun stopPlayback() {
        if (_ui.value.phase != VoicePhase.PLAYING) return
        audioPlayer.stop()
        finishPlayback(expectedScope = boundScope)
    }

    private fun finishPlayback(error: String? = null, expectedScope: ConnectionScope? = boundScope) {
        if (!isCurrentScope(expectedScope)) return
        _ui.update { state ->
            state.copy(
                phase = VoiceStateMachine.reduce(state.phase, VoiceEvent.PlaybackFinished),
                error = error,
            )
        }
    }

    private fun markCapabilityMissing(
        capability: VoiceCapability,
        message: String,
        expectedScope: ConnectionScope? = boundScope,
    ) {
        if (!isCurrentScope(expectedScope)) return
        _ui.update { state ->
            val capabilities = when (capability) {
                VoiceCapability.SERVER_STT -> state.capabilities.copy(serverStt = false)
                VoiceCapability.SERVER_TTS -> state.capabilities.copy(serverTts = false)
            }
            state.copy(
                phase = VoiceStateMachine.reduce(
                    state.phase,
                    if (capabilities.serverStt || capabilities.serverTts) {
                        VoiceEvent.ServerAvailable
                    } else {
                        VoiceEvent.ServerUnavailable
                    },
                ),
                capabilityChecked = true,
                capabilities = capabilities,
                error = message,
            )
        }
    }

    private fun addHistory(
        current: List<VoiceHistoryItem>,
        text: String,
        kind: VoiceHistoryKind,
    ): List<VoiceHistoryItem> = (listOf(
        VoiceHistoryItem(text = text, kind = kind, timestampMs = System.currentTimeMillis()),
    ) + current).take(MAX_HISTORY)

    override fun onCleared() {
        capabilityJob?.cancel()
        elevenLabsJob?.cancel()
        transcriptionJob?.cancel()
        speakJob?.cancel()
        fallbackJob?.cancel()
        recorder.cancel()
        audioPlayer.close()
            }

    private fun openApiPaths(root: JsonObject): Set<String> =
        root["paths"]?.jsonObject?.keys.orEmpty()

    private fun messageFor(error: Throwable?, fallback: String = "Voice request failed"): String =
        error?.message?.takeIf { it.isNotBlank() } ?: fallback

    private data class ElevenLabsPayload(
        val available: Boolean,
        val voices: List<ElevenLabsVoice>,
        val error: String?,
    )

    private fun parseElevenLabsVoices(root: JsonElement): ElevenLabsPayload {
        val obj = root as? JsonObject ?: return ElevenLabsPayload(false, emptyList(), messageFor(null))
        val voices = (obj["voices"] as? JsonArray).orEmpty().mapNotNull { element ->
            val voice = element as? JsonObject ?: return@mapNotNull null
            val id = voice.stringValue("voice_id", "id") ?: return@mapNotNull null
            val name = voice.stringValue("name") ?: id
            ElevenLabsVoice(
                voiceId = id,
                name = name,
                label = voice.stringValue("label") ?: name,
            )
        }
        return ElevenLabsPayload(
            available = obj["available"]?.jsonPrimitive?.booleanOrNull ?: voices.isNotEmpty(),
            voices = voices,
            error = obj["error"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun JsonObject.stringValue(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val MAX_HISTORY = 6

        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = TalariaApp.instance.container
                return VoiceViewModel(
                    context = TalariaApp.instance,
                    speech = container.speechCoordinator,
                    profileProvider = {
                        container.connectionStore.activeProfile()?.effectiveManagementProfile()
                    },
                    apiProvider = { scope ->
                        scope?.snapshot?.let { container.clientFactory.api(it) }
                            ?: container.clientFactory.api()
                    },
                    scopeFlow = container.connectionStore.scope,
                ) as T
            }
        }
    }
}
