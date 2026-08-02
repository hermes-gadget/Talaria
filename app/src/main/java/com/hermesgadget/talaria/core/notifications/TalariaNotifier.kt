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


package com.hermesgadget.talaria.core.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.hermesgadget.talaria.MainActivity
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.data.prefs.SettingsStore
import com.hermesgadget.talaria.domain.model.effectiveManagementProfile

data class AgentNotificationTarget(
    val watcherId: String,
    val agentName: String,
    val sessionId: String?,
    val connectionId: String?,
    val managementProfile: String?,
)

enum class TestNotificationResult {
    POSTED,
    POSTED_SILENTLY,
    NOTIFICATIONS_DISABLED,
    PERMISSION_REQUIRED,
    FAILED,
}

class TalariaNotifier(
    private val context: Context,
    private val settings: SettingsStore,
    private val connections: SecureConnectionStore,
) {
    fun canMonitorAgentTasks(): Boolean =
        settings.notificationsEnabled &&
            (settings.notifyAgentPermissions || settings.notifyTaskCompletions) &&
            hasPermission()

    fun notifyAgentPermission(
        target: AgentNotificationTarget,
        notificationKey: String,
        fingerprint: String,
        body: String,
    ) {
        if (!settings.notificationsEnabled || !settings.notifyAgentPermissions || !hasPermission()) return
        val lane = permissionLane(target, notificationKey)
        if (!settings.claimAgentNotification("permission|$lane", fingerprint)) return
        val id = stableId("agent-permission|${scopeKey(target)}|$notificationKey")
        settings.addActiveAgentPermission(permissionWatcherLane(target), id)
        show(
            channel = agentChannel(target, NotificationChannels.AGENT_PERMISSIONS),
            id = id,
            title = "${target.agentName} needs permission",
            body = body,
            deepLink = sessionDeepLink(target),
            target = target,
            autoCancel = false,
            priority = NotificationCompat.PRIORITY_HIGH,
            category = NotificationCompat.CATEGORY_REMINDER,
            groupKey = agentGroupKey(target),
        )
    }

    fun cancelAgentPermission(target: AgentNotificationTarget, notificationKey: String) {
        val id = stableId("agent-permission|${scopeKey(target)}|$notificationKey")
        NotificationManagerCompat.from(context).cancel(id)
        settings.removeActiveAgentPermission(permissionWatcherLane(target), id)
    }

    fun notifyAgentTaskFinished(
        target: AgentNotificationTarget,
        fingerprint: String,
        body: String,
        failed: Boolean,
        background: Boolean,
    ) {
        cancelAgentPermissionsForSession(target)
        if (!settings.notificationsEnabled || !settings.notifyTaskCompletions || !hasPermission()) return
        val lane = "${scopeKey(target)}|${target.sessionId ?: target.agentName}"
        if (!settings.claimAgentNotification("completion|$lane", fingerprint)) return
        val title = when {
            failed -> "${target.agentName}'s task failed"
            background -> "${target.agentName} finished a background task"
            else -> "${target.agentName} completed the task"
        }
        show(
            channel = agentChannel(target, NotificationChannels.AGENT_TASKS),
            id = stableId("agent-complete|$lane"),
            title = title,
            body = body.take(MAX_NOTIFICATION_BODY),
            deepLink = sessionDeepLink(target),
            target = target,
            actionableReply = !failed,
            priority = if (failed) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT,
            category = if (failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS,
            groupKey = agentGroupKey(target),
        )
    }

    fun buildAgentMonitorNotification(agentNames: Collection<String>): Notification {
        val names = agentNames.map(String::trim).filter(String::isNotBlank).distinct()
        val title = when (names.size) {
            0 -> "Monitoring Hermes tasks"
            1 -> "${names.single()} is working"
            else -> "Monitoring ${names.size} Hermes agents"
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = "talaria://chat".toUri()
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context,
            AGENT_MONITOR_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, NotificationChannels.AGENT_MONITOR)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText("Watching for permission requests and task completion")
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    fun notifyReply(title: String, body: String, sessionId: String? = null) {
        if (!settings.notificationsEnabled || !settings.notifyReplies) return
        val scope = connections.activeProfile()
        show(
            channel = NotificationChannels.REPLIES,
            id = "${scope?.id}|${scope?.managementProfile}|${sessionId ?: title}".hashCode(),
            title = title,
            body = body,
            deepLink = sessionId?.let {
                android.net.Uri.Builder()
                    .scheme("talaria")
                    .authority("session")
                    .appendPath(it)
                    .apply {
                        scope?.id?.let { id -> appendQueryParameter("connection", id) }
                        scope?.effectiveManagementProfile()
                            ?.let { profile -> appendQueryParameter("profile", profile) }
                    }
                    .build()
                    .toString()
            } ?: "talaria://chat",
            actionableReply = true,
        )
    }

    fun notifyCron(title: String, body: String) {
        if (!settings.notificationsEnabled || !settings.notifyCron) return
        show(NotificationChannels.CRON, title.hashCode(), title, body, "talaria://chat")
    }

    fun notifyGateway(title: String, body: String) {
        if (!settings.notificationsEnabled || !settings.notifyGateway) return
        show(NotificationChannels.GATEWAY, "gateway".hashCode(), title, body, "talaria://connect")
    }

    fun notifyPairing(
        title: String,
        body: String,
        platform: String? = null,
        code: String? = null,
    ) {
        if (!settings.notificationsEnabled || !settings.notifyPairing) return
        val scope = connections.activeProfile()
        val pairingLink = android.net.Uri.Builder()
            .scheme("talaria")
            .authority("pairing")
            .apply {
                scope?.id?.let { appendQueryParameter("connection", it) }
                scope?.effectiveManagementProfile()?.let { appendQueryParameter("profile", it) }
            }
            .build()
            .toString()
        show(
            channel = NotificationChannels.PAIRING,
            id = "${scope?.id}|${scope?.managementProfile}|$body".hashCode(),
            title = title,
            body = body,
            deepLink = pairingLink,
            approvePairing = if (platform != null && code != null) platform to code else null,
        )
    }

    fun notifyError(title: String, body: String) {
        if (!settings.notificationsEnabled || !settings.notifyErrors) return
        show(NotificationChannels.ERRORS, (title + body).hashCode(), title, body, "talaria://chat")
    }

    fun notifyLongTask(title: String, body: String) {
        if (!settings.notificationsEnabled) return
        show(NotificationChannels.TASKS, title.hashCode(), title, body, "talaria://chat")
    }

    fun postTestNotification(): TestNotificationResult {
        if (!settings.notificationsEnabled) return TestNotificationResult.NOTIFICATIONS_DISABLED
        if (!hasPermission()) return TestNotificationResult.PERMISSION_REQUIRED
        val quietHoursActive = QuietHoursPolicy.isActive(settings.quietHoursSettings())
        val posted = show(
            channel = if (settings.perAgentChannelsEnabled) {
                NotificationChannels.channelForAgent("talaria-test-agent").id
            } else {
                NotificationChannels.AGENT_TASKS
            },
            id = TEST_NOTIFICATION_ID,
            title = "Talaria test notification",
            body = if (quietHoursActive) {
                "Quiet hours are active; this notification is silent."
            } else {
                "Agent notification settings are working."
            },
            deepLink = "talaria://chat",
            priority = NotificationCompat.PRIORITY_DEFAULT,
            category = NotificationCompat.CATEGORY_STATUS,
            onlyAlertOnce = true,
        )
        if (!posted) return TestNotificationResult.FAILED
        return if (quietHoursActive) TestNotificationResult.POSTED_SILENTLY else TestNotificationResult.POSTED
    }

    // Permission is checked immediately below. Keep the SecurityException guard as
    // well because notification permission/app-op state can change between the
    // check and the binder call.
    @SuppressLint("MissingPermission")
    private fun show(
        channel: String,
        id: Int,
        title: String,
        body: String,
        deepLink: String,
        actionableReply: Boolean = false,
        approvePairing: Pair<String, String>? = null,
        target: AgentNotificationTarget? = null,
        autoCancel: Boolean = true,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT,
        category: String? = null,
        groupKey: String? = null,
        onlyAlertOnce: Boolean = false,
    ): Boolean {
        if (!hasPermission()) return false
        val quietHoursActive = QuietHoursPolicy.isActive(settings.quietHoursSettings())
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = deepLink.toUri()
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, id, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissPi = PendingIntent.getBroadcast(
            context, id + 11,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_DISMISS
                putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openPi)
            .setAutoCancel(autoCancel)
            .setOnlyAlertOnce(onlyAlertOnce || quietHoursActive)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, context.getString(R.string.notif_action_open), openPi)
            .addAction(0, context.getString(R.string.notif_action_dismiss), dismissPi)
            .setPriority(if (quietHoursActive) NotificationCompat.PRIORITY_LOW else priority)
            .setSilent(quietHoursActive)
        category?.let(builder::setCategory)
        groupKey?.let(builder::setGroup)

        if (actionableReply) {
            val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY)
                .setLabel(context.getString(R.string.notif_action_reply))
                .build()
            val replyPi = PendingIntent.getBroadcast(
                context, id + 22,
                Intent(context, NotificationActionReceiver::class.java).apply {
                    action = NotificationActionReceiver.ACTION_REPLY
                    putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, id)
                    putExtra(NotificationActionReceiver.EXTRA_DEEP_LINK, deepLink)
                    val profile = connections.activeProfile()
                    putExtra(NotificationActionReceiver.EXTRA_CONNECTION_ID, target?.connectionId ?: profile?.id)
                    putExtra(
                        NotificationActionReceiver.EXTRA_MANAGEMENT_PROFILE,
                        target?.managementProfile ?: profile?.managementProfile,
                    )
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(0, context.getString(R.string.notif_action_reply), replyPi)
                    .addRemoteInput(remoteInput)
                    .build(),
            )
        }

        if (approvePairing != null) {
            val (platform, code) = approvePairing
            val approvePi = PendingIntent.getBroadcast(
                context, id + 33,
                Intent(context, NotificationActionReceiver::class.java).apply {
                    action = NotificationActionReceiver.ACTION_APPROVE_PAIRING
                    putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, id)
                    putExtra(NotificationActionReceiver.EXTRA_PAIR_PLATFORM, platform)
                    putExtra(NotificationActionReceiver.EXTRA_PAIR_CODE, code)
                    connections.activeProfile()?.let { profile ->
                        putExtra(NotificationActionReceiver.EXTRA_CONNECTION_ID, profile.id)
                        putExtra(NotificationActionReceiver.EXTRA_MANAGEMENT_PROFILE, profile.managementProfile)
                    }
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, context.getString(R.string.notif_action_approve), approvePi)
        }
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
            return true
        } catch (_: SecurityException) {
            // Permission was revoked while the notification was being built.
            return false
        }
    }

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun cancelAgentPermissionsForSession(target: AgentNotificationTarget) {
        val manager = NotificationManagerCompat.from(context)
        settings.takeActiveAgentPermissions(permissionWatcherLane(target)).forEach(manager::cancel)
    }

    private fun sessionDeepLink(target: AgentNotificationTarget): String {
        val sessionId = target.sessionId
        if (sessionId.isNullOrBlank()) return "talaria://chat"
        return android.net.Uri.Builder()
            .scheme("talaria")
            .authority("session")
            .appendPath(sessionId)
            .apply {
                target.connectionId?.let { appendQueryParameter("connection", it) }
                target.managementProfile?.takeIf(String::isNotBlank)
                    ?.let { appendQueryParameter("profile", it) }
            }
            .build()
            .toString()
    }

    private fun permissionLane(target: AgentNotificationTarget, notificationKey: String): String =
        "${scopeKey(target)}|${target.sessionId ?: notificationKey}"

    private fun permissionWatcherLane(target: AgentNotificationTarget): String =
        "${scopeKey(target)}|${target.watcherId}"

    private fun scopeKey(target: AgentNotificationTarget): String =
        "${target.connectionId.orEmpty()}|${target.managementProfile.orEmpty()}"

    private fun agentGroupKey(target: AgentNotificationTarget): String =
        "talaria-agent|${scopeKey(target)}|${target.sessionId ?: target.agentName}"

    private fun agentChannel(target: AgentNotificationTarget, fallback: String): String =
        if (settings.perAgentChannelsEnabled) {
            NotificationChannels.channelForAgent(target.sessionId ?: target.watcherId).id
        } else {
            fallback
        }

    private fun stableId(value: String): Int = value.hashCode()

    companion object {
        const val AGENT_MONITOR_NOTIFICATION_ID = 0x54414C
        private const val TEST_NOTIFICATION_ID = 0x544553
        private const val MAX_NOTIFICATION_BODY = 1_000
    }
}
