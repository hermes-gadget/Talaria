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


package com.nousresearch.talaria.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.nousresearch.talaria.core.data.prefs.SettingsStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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
 */
class SpeechCoordinator(
    private val context: Context,
    private val settings: SettingsStore,
) {
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(continuous: Boolean = true): Flow<SttEvent> = callbackFlow {
        if (!isAvailable()) {
            trySend(SttEvent.Error("Speech recognition unavailable on this device"))
            close()
            return@callbackFlow
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { trySend(SttEvent.Ready) }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                trySend(SttEvent.End)
                if (continuous) recognizer.startListening(intent())
            }
            override fun onError(error: Int) {
                if (continuous && error == SpeechRecognizer.ERROR_NO_MATCH) {
                    recognizer.startListening(intent())
                    return
                }
                trySend(SttEvent.Error("STT error $error"))
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) trySend(SttEvent.Final(text))
                if (continuous) recognizer.startListening(intent())
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) trySend(SttEvent.Partial(text))
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
        recognizer.setRecognitionListener(listener)
        recognizer.startListening(intent())
        awaitClose { recognizer.destroy() }
    }

    private fun intent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Prefer on-device when available; cloud only with explicit opt-in.
            if (!settings.cloudSttOptIn) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
    }
}
