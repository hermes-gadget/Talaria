package com.nousresearch.talaria.core.notifications

import android.content.Context
import com.nousresearch.talaria.core.data.prefs.SettingsStore

/** Stub notifier; replaced by the notifications slice. */
class TalariaNotifier(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") settings: SettingsStore,
) {
    fun notifyReply(title: String, body: String, sessionId: String? = null) = Unit
    fun notifyCron(title: String, body: String) = Unit
    fun notifyGateway(title: String, body: String) = Unit
    fun notifyPairing(title: String, body: String) = Unit
    fun notifyError(title: String, body: String) = Unit
    fun notifyLongTask(title: String, body: String) = Unit
}
