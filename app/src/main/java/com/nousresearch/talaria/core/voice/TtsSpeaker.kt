package com.nousresearch.talaria.core.voice

import android.content.Context
import com.nousresearch.talaria.core.data.prefs.SettingsStore

/** Stub TTS; replaced by the voice slice. */
class TtsSpeaker(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") settings: SettingsStore,
) {
    fun speak(text: String) = Unit
    fun stop() = Unit
    fun shutdown() = Unit
}
