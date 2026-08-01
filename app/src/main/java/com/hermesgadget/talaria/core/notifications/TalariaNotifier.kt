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

class TalariaNotifier(
    private val context: Context,
    private val settings: SettingsStore,
    private val connections: SecureConnectionStore,
) {
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
    ) {
        if (!hasPermission()) return
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
            .setSmallIcon(R.drawable.ic_talaria)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.notif_action_open), openPi)
            .addAction(0, context.getString(R.string.notif_action_dismiss), dismissPi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

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
                    connections.activeProfile()?.let { profile ->
                        putExtra(NotificationActionReceiver.EXTRA_CONNECTION_ID, profile.id)
                        putExtra(NotificationActionReceiver.EXTRA_MANAGEMENT_PROFILE, profile.managementProfile)
                    }
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
        } catch (_: SecurityException) {
            // Permission was revoked while the notification was being built.
        }
    }

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
