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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.nousresearch.talaria.worker.PairingApproveWorker
import com.nousresearch.talaria.worker.ReplyWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getIntExtra(EXTRA_NOTIF_ID, -1) ?: return
        when (intent.action) {
            ACTION_DISMISS -> NotificationManagerCompat.from(context).cancel(id)
            ACTION_APPROVE_PAIRING -> {
                val platform = intent.getStringExtra(EXTRA_PAIR_PLATFORM)
                val code = intent.getStringExtra(EXTRA_PAIR_CODE)
                if (!platform.isNullOrBlank() && !code.isNullOrBlank()) {
                    val work = OneTimeWorkRequestBuilder<PairingApproveWorker>()
                        .setInputData(
                            workDataOf(
                                PairingApproveWorker.KEY_PLATFORM to platform,
                                PairingApproveWorker.KEY_CODE to code,
                            ),
                        )
                        .build()
                    WorkManager.getInstance(context).enqueue(work)
                }
                NotificationManagerCompat.from(context).cancel(id)
            }
            ACTION_REPLY -> {
                val reply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY)?.toString()
                if (!reply.isNullOrBlank()) {
                    val work = OneTimeWorkRequestBuilder<ReplyWorker>()
                        .setInputData(
                            workDataOf(
                                ReplyWorker.KEY_TEXT to reply,
                                ReplyWorker.KEY_DEEP_LINK to intent.getStringExtra(EXTRA_DEEP_LINK),
                            ),
                        )
                        .build()
                    WorkManager.getInstance(context).enqueue(work)
                }
                NotificationManagerCompat.from(context).cancel(id)
            }
        }
    }

    companion object {
        const val ACTION_DISMISS = "com.nousresearch.talaria.NOTIF_DISMISS"
        const val ACTION_REPLY = "com.nousresearch.talaria.NOTIF_REPLY"
        const val ACTION_APPROVE_PAIRING = "com.nousresearch.talaria.NOTIF_APPROVE_PAIRING"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val EXTRA_DEEP_LINK = "deep_link"
        const val EXTRA_PAIR_PLATFORM = "pair_platform"
        const val EXTRA_PAIR_CODE = "pair_code"
        const val KEY_REPLY = "talaria_reply"
    }
}
