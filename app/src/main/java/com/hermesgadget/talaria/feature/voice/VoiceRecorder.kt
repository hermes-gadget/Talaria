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
import android.os.Handler
import android.os.Looper
import java.io.File
import com.hermesgadget.talaria.core.util.suspendResult

internal data class RecordedVoiceFile(
    val file: File,
    val mimeType: String,
)

/** Small cache-backed recorder for the server STT data-URL contract. */
internal class VoiceRecorder(
    context: Context,
) {
    private val cacheDir = context.applicationContext.cacheDir
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var outputMimeType: String? = null
    private var limitReached: (() -> Unit)? = null
    private var limitRunnable: Runnable? = null
    private var completedResult: Result<RecordedVoiceFile>? = null

    /**
     * Existing callers retain the original no-argument start() signature. The
     * callback overload is used by the voice screen to submit automatically
     * when MediaRecorder reaches a quota; chat consumes the pending result when
     * its existing stop() call arrives.
     */
    fun start(): Result<Unit> = startInternal(onLimitReached = null)

    fun start(onLimitReached: () -> Unit): Result<Unit> =
        startInternal(onLimitReached = onLimitReached)

    private fun startInternal(onLimitReached: (() -> Unit)?): Result<Unit> {
        synchronized(lock) {
            if (recorder != null) {
                return Result.failure(IllegalStateException("Recording is already in progress"))
            }
            if (completedResult != null) {
                return Result.failure(IllegalStateException("Previous recording must be consumed or cancelled"))
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
                    candidate.setMaxDuration(VoiceAudioLimits.MAX_RECORDING_DURATION_MS.toInt())
                    candidate.setMaxFileSize(VoiceAudioLimits.MAX_RECORDING_BYTES)
                    candidate.prepare()

                    // Publish the session before start() so a concurrent cancel
                    // cannot leave a started MediaRecorder orphaned.
                    recorder = candidate
                    outputFile = file
                    outputMimeType = configuration.mimeType
                    limitReached = onLimitReached
                    candidate.start()
                    candidate.setOnInfoListener { source, what, _ ->
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                            what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                        ) {
                            completeDueToLimit(source)
                        }
                    }
                    val activeRecorder = candidate
                        ?: throw IllegalStateException("Recorder was not initialized")
                    val timer = Runnable { completeDueToLimit(activeRecorder) }
                    limitRunnable = timer
                    mainHandler.postDelayed(timer, VoiceAudioLimits.MAX_RECORDING_DURATION_MS)
                    return Result.success(Unit)
                } catch (error: Throwable) {
                    lastError = error
                    val session = candidate?.let { clearActiveLocked(it) }
                    runCatching { candidate?.release() }
                    session?.file?.delete()
                    if (session == null) file?.delete()
                }
            }
            return Result.failure(lastError)
        }
    }

    /** Stops the recorder on the caller's thread and leaves the file for an IO reader. */
    fun stop(): Result<RecordedVoiceFile> {
        lateinit var session: RecordingSession
        synchronized(lock) {
            completedResult?.let { result ->
                completedResult = null
                return result
            }
            val active = recorder
                ?: return Result.failure(IllegalStateException("No recording is in progress"))
            session = clearActiveLocked(active)
                ?: return Result.failure(IllegalStateException("No recording is in progress"))
        }
        return finish(session, allowAlreadyStopped = false)
    }

    fun cancel() {
        val pending: Result<RecordedVoiceFile>?
        val session: RecordingSession?
        synchronized(lock) {
            pending = completedResult
            completedResult = null
            session = recorder?.let { clearActiveLocked(it) }
        }

        pending?.getOrNull()?.file?.delete()
        session?.let { active ->
            runCatching { active.recorder.stop() }
            runCatching { active.recorder.release() }
            active.file?.delete()
        }
    }

    private fun completeDueToLimit(source: MediaRecorder) {
        val session: RecordingSession
        synchronized(lock) {
            session = clearActiveLocked(source) ?: return
            // Keep finalization under the same lock as the state transition so
            // a user tap racing the quota callback cannot observe a temporary
            // "no recording" state and lose the completed file.
            val result = finish(session, allowAlreadyStopped = true)
            completedResult = result
            session.limitReached?.let { callback -> runCatching { callback() } }
            return
        }
    }

    private fun clearActiveLocked(active: MediaRecorder): RecordingSession? {
        if (recorder !== active) return null
        limitRunnable?.let(mainHandler::removeCallbacks)
        limitRunnable = null
        val session = RecordingSession(
            recorder = active,
            file = outputFile,
            mimeType = outputMimeType,
            limitReached = limitReached,
        )
        recorder = null
        outputFile = null
        outputMimeType = null
        limitReached = null
        return session
    }

    private fun finish(session: RecordingSession, allowAlreadyStopped: Boolean): Result<RecordedVoiceFile> {
        val stopError = runCatching { session.recorder.stop() }.exceptionOrNull()
        runCatching { session.recorder.release() }

        val file = session.file
        val mimeType = session.mimeType?.takeIf { it.isNotBlank() }
        if (file == null || mimeType == null || !file.isFile || file.length() == 0L) {
            file?.delete()
            return Result.failure(stopError ?: IllegalStateException("Recording produced no audio"))
        }
        if (file.length() > VoiceAudioLimits.MAX_RECORDING_BYTES) {
            file.delete()
            return Result.failure(
                IllegalStateException(
                    "Recording exceeds the ${VoiceAudioLimits.MAX_RECORDING_BYTES} byte audio limit",
                ),
            )
        }
        if (stopError != null && !allowAlreadyStopped) {
            file.delete()
            return Result.failure(stopError)
        }
        return Result.success(RecordedVoiceFile(file, mimeType))
    }

    private data class RecordingSession(
        val recorder: MediaRecorder,
        val file: File?,
        val mimeType: String?,
        val limitReached: (() -> Unit)?,
    )

    private data class RecordingConfiguration(
        val outputFormat: Int,
        val audioEncoder: Int,
        val mimeType: String,
        val extension: String,
    )

    companion object {
        // MP4/AAC is accepted by common remote STT providers. The 3GP fallback
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
