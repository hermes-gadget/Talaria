package com.nousresearch.talaria.core.voice

import android.content.Context
import com.nousresearch.talaria.core.data.prefs.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

sealed class SttEvent {
    data object Ready : SttEvent()
    data class Partial(val text: String) : SttEvent()
    data class Final(val text: String) : SttEvent()
    data class Error(val message: String) : SttEvent()
}

/** Stub STT; replaced by the voice slice. */
class SpeechCoordinator(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") settings: SettingsStore,
) {
    fun isAvailable(): Boolean = false
    fun listen(continuous: Boolean = true): Flow<SttEvent> = emptyFlow()
}
