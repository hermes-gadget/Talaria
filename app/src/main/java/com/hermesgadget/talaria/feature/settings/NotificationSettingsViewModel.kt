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

package com.hermesgadget.talaria.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.prefs.SettingsStore
import com.hermesgadget.talaria.core.notifications.AgentNotificationChannel
import com.hermesgadget.talaria.core.notifications.NotificationChannels
import com.hermesgadget.talaria.core.notifications.QuietHoursPolicy
import com.hermesgadget.talaria.core.notifications.TestNotificationResult
import com.hermesgadget.talaria.core.notifications.TalariaNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationChannelStatus(
    val channel: AgentNotificationChannel,
    val agents: List<String>,
)

data class NotificationSettingsUiState(
    val notifyAgentPermissions: Boolean,
    val notifyTaskCompletions: Boolean,
    val quietHoursEnabled: Boolean,
    val quietHoursStartMinutes: Int,
    val quietHoursEndMinutes: Int,
    val quietHoursActive: Boolean,
    val perAgentChannelsEnabled: Boolean,
    val channels: List<NotificationChannelStatus>,
    val message: String? = null,
)

class NotificationSettingsViewModel(
    private val settings: SettingsStore = TalariaApp.instance.container.settingsStore,
    private val notifier: TalariaNotifier = TalariaApp.instance.container.notifier,
) : ViewModel() {
    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<NotificationSettingsUiState> = _ui.asStateFlow()

    fun refresh() {
        _ui.value = snapshot(_ui.value.message)
    }

    /** True once no alert kind is left enabled, so the caller can stop watchers. */
    fun setNotifyAgentPermissions(enabled: Boolean): Boolean {
        settings.notifyAgentPermissions = enabled
        refresh()
        return !enabled && !settings.notifyTaskCompletions
    }

    /** True once no alert kind is left enabled, so the caller can stop watchers. */
    fun setNotifyTaskCompletions(enabled: Boolean): Boolean {
        settings.notifyTaskCompletions = enabled
        refresh()
        return !enabled && !settings.notifyAgentPermissions
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        settings.quietHoursEnabled = enabled
        refresh()
    }

    fun setQuietHoursStart(minutes: Int) {
        settings.quietHoursStartMinutes = minutes
        refresh()
    }

    fun setQuietHoursEnd(minutes: Int) {
        settings.quietHoursEndMinutes = minutes
        refresh()
    }

    fun setPerAgentChannelsEnabled(enabled: Boolean) {
        settings.perAgentChannelsEnabled = enabled
        refresh()
    }

    fun sendTestNotification() {
        val message = when (notifier.postTestNotification()) {
            TestNotificationResult.POSTED -> "Test notification sent."
            TestNotificationResult.POSTED_SILENTLY ->
                "Test notification sent silently because quiet hours are active."
            TestNotificationResult.NOTIFICATIONS_DISABLED -> "Enable notifications on You first."
            TestNotificationResult.PERMISSION_REQUIRED -> "Allow notification permission on You first."
            TestNotificationResult.FAILED -> "The test notification could not be posted."
        }
        _ui.value = snapshot(message)
    }

    private fun snapshot(message: String? = null): NotificationSettingsUiState {
        val quietHours = settings.quietHoursSettings()
        return NotificationSettingsUiState(
            notifyAgentPermissions = settings.notifyAgentPermissions,
            notifyTaskCompletions = settings.notifyTaskCompletions,
            quietHoursEnabled = quietHours.enabled,
            quietHoursStartMinutes = quietHours.startMinutes,
            quietHoursEndMinutes = quietHours.endMinutes,
            quietHoursActive = QuietHoursPolicy.isActive(quietHours),
            perAgentChannelsEnabled = settings.perAgentChannelsEnabled,
            channels = channelStatuses(),
            message = message,
        )
    }

    private fun channelStatuses(): List<NotificationChannelStatus> {
        val activeAgents = settings.loadAgentWatches()
            .groupBy { NotificationChannels.channelForAgent(it.sessionId ?: it.watcherId).id }
            .mapValues { (_, watches) ->
                watches.map { watch ->
                    val label = watch.agentName.trim().ifBlank { watch.watcherId }
                    watch.sessionId?.takeIf(String::isNotBlank)?.let { "$label · $it" } ?: label
                }.distinct()
            }
        return NotificationChannels.agentChannelSlots.map { channel ->
            NotificationChannelStatus(channel, activeAgents[channel.id].orEmpty())
        }
    }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NotificationSettingsViewModel() as T
        }
    }
}
