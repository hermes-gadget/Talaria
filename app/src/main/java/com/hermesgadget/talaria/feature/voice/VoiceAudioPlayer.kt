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
import android.media.MediaPlayer
import android.net.Uri
import android.util.Base64
import java.io.File

/** Decodes Hermes' audio data URL into a cache file and plays it with Android MediaPlayer. */
internal class VoiceAudioPlayer(
    context: Context,
) {
    private val cacheDir = context.applicationContext.cacheDir
    private var player: MediaPlayer? = null
    private var audioFile: File? = null

    fun play(
        dataUrl: String,
        onCompleted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        try {
            val decoded = decodeDataUrl(dataUrl)
            val file = File.createTempFile("talaria-tts-", extensionFor(decoded.mimeType), cacheDir)
            file.writeBytes(decoded.bytes)

            val next = MediaPlayer()
            next.setDataSource(file.absolutePath)
            next.setOnPreparedListener { prepared -> prepared.start() }
            next.setOnCompletionListener {
                release()
                onCompleted()
            }
            next.setOnErrorListener { _, what, extra ->
                release()
                onError("Android audio playback failed ($what/$extra)")
                true
            }
            player = next
            audioFile = file
            next.prepareAsync()
        } catch (error: Throwable) {
            release()
            onError(error.message ?: "Could not prepare server audio")
        }
    }

    fun stop() {
        release()
    }

    private fun release() {
        val active = player
        player = null
        runCatching { active?.stop() }
        runCatching { active?.release() }
        audioFile?.delete()
        audioFile = null
    }

    private data class DecodedAudio(
        val mimeType: String,
        val bytes: ByteArray,
    )

    private fun decodeDataUrl(value: String): DecodedAudio {
        val dataUrl = value.trim()
        require(dataUrl.startsWith("data:", ignoreCase = true)) {
            "Hermes returned an invalid audio data URL"
        }
        val comma = dataUrl.indexOf(',')
        require(comma > "data:".length) { "Hermes returned an empty audio data URL" }
        val metadata = dataUrl.substring("data:".length, comma)
        val mimeType = metadata.substringBefore(';').trim().ifBlank { "audio/mpeg" }
        val payload = dataUrl.substring(comma + 1)
        val bytes = if (metadata.split(';').any { it.equals("base64", ignoreCase = true) }) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            Uri.decode(payload).orEmpty().toByteArray(Charsets.UTF_8)
        }
        require(bytes.isNotEmpty()) { "Hermes returned empty audio" }
        return DecodedAudio(mimeType, bytes)
    }

    private fun extensionFor(mimeType: String): String = when {
        mimeType.equals("audio/mp4", ignoreCase = true) ||
            mimeType.equals("audio/x-m4a", ignoreCase = true) -> ".m4a"
        mimeType.equals("audio/ogg", ignoreCase = true) -> ".ogg"
        mimeType.equals("audio/wav", ignoreCase = true) ||
            mimeType.equals("audio/x-wav", ignoreCase = true) -> ".wav"
        mimeType.equals("audio/flac", ignoreCase = true) -> ".flac"
        mimeType.equals("audio/3gpp", ignoreCase = true) -> ".3gp"
        else -> ".mp3"
    }
}
