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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.hermesgadget.talaria.core.util.suspendResult

/** Decodes Hermes' audio data URL into a bounded cache file and plays it. */
internal class VoiceAudioPlayer(
    context: Context,
) {
    private val cacheDir = context.applicationContext.cacheDir
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var decodeJob: Job? = null
    private var player: MediaPlayer? = null
    private var audioFile: File? = null
    private var generation = 0L

    /** Callback signatures stay synchronous; decoding and file IO happen off the main thread. */
    fun play(
        dataUrl: String,
        onCompleted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        val requestGeneration = generation
        decodeJob = scope.launch {
            var decodedFile: File? = null
            try {
                val decoded = withContext(Dispatchers.IO) {
                    decodeVoiceAudioDataUrl(dataUrl, cacheDir)
                }
                decodedFile = decoded.file
                ensureActive()
                if (requestGeneration != generation) {
                    decoded.file.delete()
                    return@launch
                }

                val next = MediaPlayer()
                player = next
                audioFile = decoded.file
                decodedFile = null
                next.setDataSource(decoded.file.absolutePath)
                next.setOnPreparedListener { prepared ->
                    if (requestGeneration == generation && player === next) {
                        prepared.start()
                    }
                }
                next.setOnCompletionListener {
                    if (requestGeneration == generation && player === next) {
                        release()
                        onCompleted()
                    }
                }
                next.setOnErrorListener { _, what, extra ->
                    if (requestGeneration != generation || player !== next) {
                        true
                    } else {
                        release()
                        onError("Android audio playback failed ($what/$extra)")
                        true
                    }
                }
                next.prepareAsync()
            } catch (cancelled: CancellationException) {
                decodedFile?.delete()
                throw cancelled
            } catch (error: Throwable) {
                decodedFile?.delete()
                if (requestGeneration == generation) {
                    release()
                    onError(error.message ?: "Could not prepare server audio")
                }
            }
        }
    }

    fun stop() {
        generation++
        decodeJob?.cancel()
        decodeJob = null
        release()
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun release() {
        val active = player
        player = null
        runCatching { active?.stop() }
        runCatching { active?.release() }
        audioFile?.delete()
        audioFile = null
    }
}
