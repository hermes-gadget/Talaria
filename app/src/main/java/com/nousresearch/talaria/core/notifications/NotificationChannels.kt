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


package com.nousresearch.talaria.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import com.nousresearch.talaria.R

object NotificationChannels {
    const val REPLIES = "replies"
    const val CRON = "cron"
    const val GATEWAY = "gateway"
    const val PAIRING = "pairing"
    const val ERRORS = "errors"
    const val TASKS = "tasks"
    const val SYNC = "sync"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService<NotificationManager>() ?: return
        val channels = listOf(
            NotificationChannel(REPLIES, context.getString(R.string.notif_channel_replies), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CRON, context.getString(R.string.notif_channel_cron), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(GATEWAY, context.getString(R.string.notif_channel_gateway), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(PAIRING, context.getString(R.string.notif_channel_pairing), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(ERRORS, context.getString(R.string.notif_channel_errors), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(TASKS, context.getString(R.string.notif_channel_tasks), NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(SYNC, context.getString(R.string.notif_channel_sync), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannels(channels)
    }
}
