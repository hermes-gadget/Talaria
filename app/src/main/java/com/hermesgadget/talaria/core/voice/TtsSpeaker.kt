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

import android.content.Context
import android.speech.tts.TextToSpeech
import com.hermesgadget.talaria.core.data.prefs.SettingsStore
import java.util.Locale

class TtsSpeaker(
    context: Context,
    private val settings: SettingsStore,
) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts?.language = Locale.getDefault()
    }

    fun speak(text: String) {
        if (!settings.ttsEnabled || !ready) return
        val clipped = text.take(800)
        tts?.speak(clipped, TextToSpeech.QUEUE_FLUSH, null, "talaria-${clipped.hashCode()}")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
