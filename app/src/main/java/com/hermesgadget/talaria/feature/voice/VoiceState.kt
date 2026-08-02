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

import com.hermesgadget.talaria.domain.model.VoiceCapabilities
import com.hermesgadget.talaria.domain.model.VoiceHistoryItem

data class ElevenLabsVoice(
    val voiceId: String,
    val name: String,
    val label: String,
)

enum class VoicePhase {
    IDLE,
    RECORDING,
    TRANSCRIBING,
    PLAYING,
    UNAVAILABLE,
}

sealed interface VoiceEvent {
    data object StartRecording : VoiceEvent
    data object RecordingFinished : VoiceEvent
    data object TranscriptionFinished : VoiceEvent
    data object StartPlayback : VoiceEvent
    data object PlaybackFinished : VoiceEvent
    data object ServerAvailable : VoiceEvent
    data object ServerUnavailable : VoiceEvent
}

/** Pure reducer for the server voice lifecycle; kept independent of Android for unit tests. */
object VoiceStateMachine {
    fun reduce(current: VoicePhase, event: VoiceEvent): VoicePhase = when (event) {
        VoiceEvent.StartRecording -> when (current) {
            VoicePhase.IDLE, VoicePhase.UNAVAILABLE -> VoicePhase.RECORDING
            else -> current
        }
        VoiceEvent.RecordingFinished -> when (current) {
            VoicePhase.RECORDING -> VoicePhase.TRANSCRIBING
            else -> current
        }
        VoiceEvent.TranscriptionFinished -> when (current) {
            VoicePhase.RECORDING, VoicePhase.TRANSCRIBING -> VoicePhase.IDLE
            else -> current
        }
        VoiceEvent.StartPlayback -> when (current) {
            VoicePhase.IDLE -> VoicePhase.PLAYING
            else -> current
        }
        VoiceEvent.PlaybackFinished -> when (current) {
            VoicePhase.PLAYING -> VoicePhase.IDLE
            else -> current
        }
        VoiceEvent.ServerAvailable -> when (current) {
            VoicePhase.UNAVAILABLE -> VoicePhase.IDLE
            else -> current
        }
        VoiceEvent.ServerUnavailable -> VoicePhase.UNAVAILABLE
    }
}

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val capabilityChecked: Boolean = false,
    val checkingCapabilities: Boolean = false,
    val capabilities: VoiceCapabilities = VoiceCapabilities(),
    val androidSpeechAvailable: Boolean = false,
    val microphonePermissionGranted: Boolean = false,
    val text: String = "",
    val partialText: String = "",
    val fallbackListening: Boolean = false,
    val history: List<VoiceHistoryItem> = emptyList(),
    val elevenLabsLoading: Boolean = false,
    val elevenLabsAvailable: Boolean? = null,
    val elevenLabsVoices: List<ElevenLabsVoice> = emptyList(),
    val elevenLabsError: String? = null,
    val error: String? = null,
)
