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


package com.hermesgadget.talaria.core.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.hermesgadget.talaria.core.data.prefs.SettingsStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.hermesgadget.talaria.core.util.suspendResult

sealed class SttEvent {
    data class Partial(val text: String) : SttEvent()
    data class Final(val text: String) : SttEvent()
    data class Error(val message: String) : SttEvent()
    data object Ready : SttEvent()
    data object End : SttEvent()
}

/**
 * On-device STT via Android SpeechRecognizer (preferred).
 * Cloud STT engines are only used when the user explicitly opts in via Settings.
 *
 * SpeechRecognizer must be created and driven on the main thread. Continuous
 * restarts are deferred briefly so ERROR_CLIENT (5) from overlapping sessions
 * does not immediately abort dictation.
 */
class SpeechCoordinator(
    private val context: Context,
    private val settings: SettingsStore,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun listen(continuous: Boolean = true): Flow<SttEvent> = callbackFlow {
        if (!hasMicPermission()) {
            trySend(SttEvent.Error("Microphone permission required"))
            close()
            return@callbackFlow
        }
        if (!isAvailable()) {
            trySend(SttEvent.Error("Speech recognition unavailable on this device"))
            close()
            return@callbackFlow
        }

        val closed = AtomicBoolean(false)
        val terminal = AtomicBoolean(false)
        val cleanupScheduled = AtomicBoolean(false)
        val listening = AtomicBoolean(false)
        val transientFails = AtomicInteger(0)
        var recognizer: SpeechRecognizer? = null

        fun finish(event: SttEvent? = null) {
            if (!terminal.compareAndSet(false, true)) return
            event?.let { trySend(it) }
            close()
        }

        fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Prefer on-device when available; cloud only with explicit opt-in.
            if (!settings.cloudSttOptIn) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        fun scheduleListen(delayMs: Long = 0L) {
            if (closed.get() || terminal.get()) return
            mainHandler.postDelayed({
                if (closed.get() || terminal.get()) return@postDelayed
                val r = recognizer ?: return@postDelayed
                if (!listening.compareAndSet(false, true)) return@postDelayed
                try {
                    r.startListening(intent())
                } catch (_: Exception) {
                    listening.set(false)
                    if (continuous && !closed.get() && transientFails.incrementAndGet() <= MAX_TRANSIENT) {
                        scheduleListen(RESTART_DELAY_MS)
                    } else {
                        finish(SttEvent.Error("STT failed to start"))
                    }
                }
            }, delayMs)
        }

        fun explain(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech network timed out"
            SpeechRecognizer.ERROR_NETWORK -> "Speech network error"
            SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error"
            SpeechRecognizer.ERROR_SERVER ->
                "Speech server error — enable cloud STT in You, or install an offline speech pack"
            SpeechRecognizer.ERROR_CLIENT ->
                "Speech client error — grant mic permission, or enable cloud STT if no offline pack is installed"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
            // ERROR_LANGUAGE_UNAVAILABLE (13) / ERROR_LANGUAGE_NOT_SUPPORTED (12) are
            // API 33+ codes; match the raw ints so we stay compilable at minSdk 28.
            // Common when no on-device speech pack is installed (e.g. a fresh device
            // or emulator). We don't silently fall back to cloud — that would break
            // the offline-by-default privacy promise — so point the user at both fixes.
            12, 13 ->
                "On-device speech pack unavailable — install it in Settings › System › " +
                    "Languages & input › On-device recognition, or enable cloud STT in You"
            else -> "STT error $error"
        }

        fun isTransient(error: Int): Boolean = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT,
            -> true
            else -> false
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                transientFails.set(0)
                trySend(SttEvent.Ready)
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                listening.set(false)
                trySend(SttEvent.End)
                if (continuous) scheduleListen(RESTART_DELAY_MS)
            }
            override fun onError(error: Int) {
                listening.set(false)
                if (continuous && isTransient(error)) {
                    // Normal continuous idle: no-match / speech-timeout just restart.
                    // ERROR_CLIENT (5) / busy usually mean overlapping restarts or a
                    // missing offline pack — retry a few times, then surface.
                    val soft = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (soft || transientFails.incrementAndGet() <= MAX_TRANSIENT) {
                        scheduleListen(RESTART_DELAY_MS)
                        return
                    }
                }
                finish(SttEvent.Error(explain(error)))
            }
            override fun onResults(results: Bundle?) {
                listening.set(false)
                transientFails.set(0)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!continuous) {
                    // A one-shot flow must have a terminal transition even when
                    // Android returns no match or an empty result bundle.
                    finish(SttEvent.Final(text.orEmpty()))
                } else if (!terminal.get()) {
                    if (!text.isNullOrBlank()) trySend(SttEvent.Final(text))
                    scheduleListen(RESTART_DELAY_MS)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) trySend(SttEvent.Partial(text))
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        // Create + start on the main looper — required by SpeechRecognizer.
        // Never block this collector waiting on main (viewModelScope is Main).
        mainHandler.post {
            if (closed.get()) return@post
            try {
                val r = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer = r
                r.setRecognitionListener(listener)
                scheduleListen()
            } catch (t: Throwable) {
                finish(SttEvent.Error(t.message ?: "STT init failed"))
            }
        }

        awaitClose {
            if (cleanupScheduled.compareAndSet(false, true)) {
                closed.set(true)
                mainHandler.post {
                    listening.set(false)
                    runCatching {
                        recognizer?.setRecognitionListener(null)
                        recognizer?.cancel()
                        recognizer?.destroy()
                    }
                    recognizer = null
                }
            }
        }
    }

    companion object {
        private const val RESTART_DELAY_MS = 350L
        private const val MAX_TRANSIENT = 3
    }
}
