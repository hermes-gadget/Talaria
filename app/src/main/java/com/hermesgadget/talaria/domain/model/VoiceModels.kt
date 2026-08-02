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

package com.hermesgadget.talaria.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** JSON body accepted by Hermes' server-side transcription endpoint. */
@Serializable
data class VoiceTranscriptionRequest(
    @SerialName("data_url") val dataUrl: String,
    @SerialName("mime_type") val mimeType: String? = null,
)

/** Response returned by Hermes' server-side transcription endpoint. */
@Serializable
data class VoiceTranscriptionResponse(
    val ok: Boolean = false,
    val provider: String? = null,
    val transcript: String = "",
    val error: String? = null,
)

/** JSON body accepted by Hermes' server-side speech synthesis endpoint. */
@Serializable
data class VoiceSpeakRequest(
    val text: String,
)

/** Response returned by Hermes' server-side speech synthesis endpoint. */
@Serializable
data class VoiceSpeakResponse(
    val ok: Boolean = false,
    @SerialName("data_url") val dataUrl: String = "",
    @SerialName("mime_type") val mimeType: String = "audio/mpeg",
    val provider: String? = null,
    val error: String? = null,
)

enum class VoiceCapability(
    val label: String,
    val route: String,
) {
    SERVER_STT("Server STT", "/api/audio/transcribe"),
    SERVER_TTS("Server TTS", "/api/audio/speak"),
}

/** The two server capabilities needed by the standalone voice surface. */
data class VoiceCapabilities(
    val serverStt: Boolean = false,
    val serverTts: Boolean = false,
) {
    val isComplete: Boolean
        get() = serverStt && serverTts

    val missing: List<VoiceCapability>
        get() = buildList {
            if (!serverStt) add(VoiceCapability.SERVER_STT)
            if (!serverTts) add(VoiceCapability.SERVER_TTS)
        }

    companion object {
        fun fromOpenApiPaths(paths: Set<String>): VoiceCapabilities {
            val normalizedPaths = paths.map { it.trimEnd('/') }.toSet()
            return VoiceCapabilities(
                serverStt = VoiceCapability.SERVER_STT.route in normalizedPaths,
                serverTts = VoiceCapability.SERVER_TTS.route in normalizedPaths,
            )
        }
    }
}

enum class VoiceHistoryKind(val label: String) {
    SERVER_TRANSCRIPTION("Server transcription"),
    ON_DEVICE_DICTATION("On-device dictation"),
    SERVER_SPEECH("Server speech"),
}

data class VoiceHistoryItem(
    val text: String,
    val kind: VoiceHistoryKind,
    val timestampMs: Long,
)
