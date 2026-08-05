/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.car

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.hermesgadget.talaria.MainActivity
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.notifications.NotificationActionReceiver
import com.hermesgadget.talaria.core.notifications.NotificationChannels
import com.hermesgadget.talaria.domain.model.SessionMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * Android Auto message-center bridge.
 *
 * The car app declares the MESSAGING category plus the `notification`
 * capability (required for phone-projection discovery). The projection host
 * therefore surfaces Talaria through the **message center**, which renders
 * phone notifications — NOT the CarAppService template. Without
 * conversation notifications the message center is permanently empty
 * ("No new messages during this drive") even with live sessions.
 *
 * This notifier posts one message-category notification per active
 * conversation whenever the car screen refreshes. Each notification:
 *  - opens the session deep link on tap,
 *  - supports voice reply via the existing `NotificationActionReceiver`
 *    ACTION_REPLY -> ReplyWorker (PTY) path,
 *  - is cancelled when the session disappears from the active list.
 *
 * Notifications are only posted while the car screen is alive, so the phone
 * shade is not spammed outside car sessions; the AA message center renders
 * them for the duration of the drive.
 */
object CarConversationNotifier {

    private val postedIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Refresh message-center notifications to match the active conversations. */
    fun update(context: Context, snapshot: ConnectionSnapshot, conversations: List<CarConversation>) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val currentIds = conversations.mapNotNull { conversation ->
            postConversation(context, manager, snapshot, conversation)
        }.toSet()

        // Cancel notifications for sessions that are no longer active.
        postedIds.filterNot { it in currentIds }.forEach { id ->
            manager.cancel(id)
            postedIds.remove(id)
        }
        postedIds.addAll(currentIds)
    }

    /** Cancel everything this notifier posted (car screen destroyed / host disconnect). */
    fun clear(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        postedIds.toList().forEach { id ->
            manager.cancel(id)
            postedIds.remove(id)
        }
    }

    // Runtime-guarded via hasNotificationPermission() immediately before the
    // notify below; lint can't see across the helper call, so suppress at the
    // same granularity TalariaNotifier.show does.
    @SuppressLint("MissingPermission")
    private fun postConversation(
        context: Context,
        manager: NotificationManagerCompat,
        snapshot: ConnectionSnapshot,
        conversation: CarConversation,
    ): Int? {
        val session = conversation.session
        val id = notificationId(snapshot, session.id)
        val title = session.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.car_untitled_agent)
        val deepLink = sessionDeepLink(snapshot, session.id)

        val hermes = Person.Builder().setName("Hermes").setKey("hermes").build()
        val me = Person.Builder().setName("Me").setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(title)
        conversation.messages.takeLast(3).forEach { message ->
            style.addMessage(
                message.content.orEmpty().trim().takeIf { it.isNotEmpty() } ?: "…",
                message.timestamp.toEpochMillis() ?: System.currentTimeMillis(),
                if (message.role == "user") me else hermes,
            )
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = deepLink.toUri()
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, id, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val replyPi = PendingIntent.getBroadcast(
            context, id + 22,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_REPLY
                putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, id)
                putExtra(NotificationActionReceiver.EXTRA_DEEP_LINK, deepLink)
                putExtra(NotificationActionReceiver.EXTRA_CONNECTION_ID, snapshot.connectionId)
                snapshot.managementProfile.takeIf { it.isNotBlank() }
                    ?.let { putExtra(NotificationActionReceiver.EXTRA_MANAGEMENT_PROFILE, it) }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY)
            .setLabel(context.getString(R.string.car_reply_to_hermes))
            .build()

        val notification = NotificationCompat.Builder(context, NotificationChannels.CONVERSATIONS)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(conversation.messages.lastOrNull()?.content?.trim() ?: context.getString(R.string.car_tap_to_open))
            .setStyle(style)
            .setContentIntent(openPi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setSilent(true)
            .addAction(
                NotificationCompat.Action.Builder(
                    0, // no icon — matches TalariaNotifier's reply action pattern
                    context.getString(R.string.car_reply),
                    replyPi,
                ).addRemoteInput(remoteInput).build(),
            )
            .build()

        if (!hasNotificationPermission(context)) return null
        manager.notify(id, notification)
        return id
    }

    /** POST_NOTIFICATIONS is runtime-granted; gate on it (lint requires the
     * check or an explicit security consideration — see TalariaNotifier.show). */
    @SuppressLint("MissingPermission")
    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Stable per-connection/session notification id (32-bit, namespaced). */
    internal fun notificationId(snapshot: ConnectionSnapshot, sessionId: String): Int =
        ("car-conv|${snapshot.connectionId}|$sessionId").hashCode()

    /** `talaria://session/<id>?connection=..&profile=..` — same shape as TalariaNotifier replies. */
    internal fun sessionDeepLink(snapshot: ConnectionSnapshot, sessionId: String): String {
        // Pure string building (no android.net.Uri) so the shape is unit-testable
        // on the JVM; parts are URL-encoded exactly like the deep-link parser
        // (TalariaDeepLinkParser) expects to decode them.
        val sb = StringBuilder("talaria://session/")
        sb.append(encode(sessionId))
        sb.append("?connection=").append(encode(snapshot.connectionId))
        snapshot.managementProfile.takeIf { it.isNotBlank() }?.let {
            sb.append("&profile=").append(encode(it))
        }
        return sb.toString()
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")

    /** Timestamps arrive as epoch-seconds-double or ISO-8601 — normalize to millis. */
    private fun String?.toEpochMillis(): Long? = this?.let { raw ->
        raw.toDoubleOrNull()?.let { d ->
            // Hermes stores epoch SECONDS (1.78e9); notifications want MILLIS.
            if (d < 1_000_000_000_000L) (d * 1000).toLong() else d.toLong()
        } ?: runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
    }
}
