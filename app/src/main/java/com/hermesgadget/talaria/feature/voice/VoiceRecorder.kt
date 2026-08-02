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
import android.media.MediaRecorder
import java.io.File

internal data class RecordedVoiceFile(
    val file: File,
    val mimeType: String,
)

/** Small cache-backed recorder for the server STT data-URL contract. */
internal class VoiceRecorder(
    context: Context,
) {
    private val cacheDir = context.applicationContext.cacheDir
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var outputMimeType: String? = null

    fun start(): Result<Unit> {
        if (recorder != null) {
            return Result.failure(IllegalStateException("Recording is already in progress"))
        }

        var lastError: Throwable = IllegalStateException("No recording format is available")
        for (configuration in CONFIGURATIONS) {
            var file: File? = null
            var candidate: MediaRecorder? = null
            try {
                file = File.createTempFile("talaria-stt-", configuration.extension, cacheDir)
                candidate = MediaRecorder()
                candidate.setAudioSource(MediaRecorder.AudioSource.MIC)
                candidate.setOutputFormat(configuration.outputFormat)
                candidate.setAudioEncoder(configuration.audioEncoder)
                candidate.setOutputFile(file.absolutePath)
                candidate.prepare()
                candidate.start()
                recorder = candidate
                outputFile = file
                outputMimeType = configuration.mimeType
                return Result.success(Unit)
            } catch (error: Throwable) {
                lastError = error
                runCatching { candidate?.release() }
                file?.delete()
            }
        }
        return Result.failure(lastError)
    }

    /** Stops the recorder on the caller's thread and leaves the file for an IO reader. */
    fun stop(): Result<RecordedVoiceFile> {
        val activeRecorder = recorder
            ?: return Result.failure(IllegalStateException("No recording is in progress"))
        val file = outputFile
        val mimeType = outputMimeType
        recorder = null
        outputFile = null
        outputMimeType = null

        return try {
            activeRecorder.stop()
            activeRecorder.release()
            val outputMime = mimeType?.takeIf { it.isNotBlank() }
            if (file == null || outputMime == null || !file.isFile || file.length() == 0L) {
                file?.delete()
                throw IllegalStateException("Recording produced no audio")
            }
            Result.success(RecordedVoiceFile(file, outputMime))
        } catch (error: Throwable) {
            runCatching { activeRecorder.release() }
            file?.delete()
            Result.failure(error)
        }
    }

    fun cancel() {
        val activeRecorder = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        outputMimeType = null
        runCatching { activeRecorder?.stop() }
        runCatching { activeRecorder?.release() }
        file?.delete()
    }

    private data class RecordingConfiguration(
        val outputFormat: Int,
        val audioEncoder: Int,
        val mimeType: String,
        val extension: String,
    )

    companion object {
        // MP4/AAC is accepted by common remote STT providers.  The 3GP fallback
        // keeps recording usable on older or vendor-limited Android codecs.
        private val CONFIGURATIONS = listOf(
            RecordingConfiguration(
                MediaRecorder.OutputFormat.MPEG_4,
                MediaRecorder.AudioEncoder.AAC,
                "audio/mp4",
                ".m4a",
            ),
            RecordingConfiguration(
                MediaRecorder.OutputFormat.THREE_GPP,
                MediaRecorder.AudioEncoder.AMR_NB,
                "audio/3gpp",
                ".3gp",
            ),
        )
    }
}
