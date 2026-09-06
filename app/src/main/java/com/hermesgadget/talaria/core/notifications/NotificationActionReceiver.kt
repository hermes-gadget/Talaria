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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.app.RemoteInput
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.worker.PairingApproveWorker
import com.hermesgadget.talaria.worker.ReplyWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File

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
                                PairingApproveWorker.KEY_CONNECTION_ID to
                                    intent.getStringExtra(EXTRA_CONNECTION_ID),
                                PairingApproveWorker.KEY_MANAGEMENT_PROFILE to
                                    intent.getStringExtra(EXTRA_MANAGEMENT_PROFILE).orEmpty(),
                                PairingApproveWorker.KEY_BASE_URL to
                                    intent.getStringExtra(EXTRA_BASE_URL).orEmpty(),
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
                    // B21: staging (mkdir + spill write for oversized pastes)
                    // can throw on a full disk and previously escaped
                    // onReceive before any error handling. Staging runs off
                    // the main thread inside a goAsync window; any staging
                    // failure degrades to a bounded inline payload instead
                    // of crashing or dropping the reply.
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.Default).launch {
                        try {
                            val payload = withContext(Dispatchers.IO) {
                                try {
                                    ReplyPayloadBuilder(
                                        File(context.cacheDir, "notification-replies"),
                                    ).build(reply)
                                } catch (_: Exception) {
                                    ReplyPayload.Inline(reply.take(MAX_INLINE_REPLY_CHARS))
                                }
                            }
                            enqueueReply(context, intent, id, payload)
                        } finally {
                            NotificationManagerCompat.from(context).cancel(id)
                            pendingResult.finish()
                        }
                    }
                } else {
                    NotificationManagerCompat.from(context).cancel(id)
                }
            }
        }
    }

    /** B21: shared enqueue path with spill-file cleanup on failure. */
    private fun enqueueReply(
        context: Context,
        intent: Intent,
        id: Int,
        payload: ReplyPayload,
    ) {
        try {
            val work = OneTimeWorkRequestBuilder<ReplyWorker>()
                .setInputData(
                    workDataOf(
                        ReplyWorker.KEY_TEXT to payload.text,
                        ReplyWorker.KEY_TEXT_FILE to payload.filePath,
                        ReplyWorker.KEY_DEEP_LINK to intent.getStringExtra(EXTRA_DEEP_LINK),
                        ReplyWorker.KEY_CONNECTION_ID to intent.getStringExtra(EXTRA_CONNECTION_ID),
                        ReplyWorker.KEY_MANAGEMENT_PROFILE to
                            intent.getStringExtra(EXTRA_MANAGEMENT_PROFILE).orEmpty(),
                        ReplyWorker.KEY_BASE_URL to
                            intent.getStringExtra(EXTRA_BASE_URL).orEmpty(),
                        ReplyWorker.KEY_CAR_ORIGIN to
                            intent.getBooleanExtra(EXTRA_CAR_ORIGIN, false),
                    ),
                )
                .build()
            WorkManager.getInstance(context).enqueue(work)
        } catch (enqueueFailure: Exception) {
            // A pathological oversize payload must never crash the
            // receiver; tell the user to open the app instead. Drop any
            // staged spill file so a full disk is not left holding
            // unreadable reply debris.
            (payload as? ReplyPayload.File)?.let { staged ->
                java.io.File(staged.path).delete()
            }
            TalariaApp.instance.container.notifier.notifyError(
                "Reply too long",
                "Open the app to send this reply",
            )
        }
    }

    companion object {
        /** B21: bounded inline fallback when spill staging fails. */
        private const val MAX_INLINE_REPLY_CHARS = 8_192
        const val ACTION_DISMISS = "com.hermesgadget.talaria.NOTIF_DISMISS"
        const val ACTION_REPLY = "com.hermesgadget.talaria.NOTIF_REPLY"
        const val ACTION_APPROVE_PAIRING = "com.hermesgadget.talaria.NOTIF_APPROVE_PAIRING"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val EXTRA_DEEP_LINK = "deep_link"
        const val EXTRA_PAIR_PLATFORM = "pair_platform"
        const val EXTRA_PAIR_CODE = "pair_code"
        const val EXTRA_CONNECTION_ID = "connection_id"
        /** B02: true when the notification came from the Android Auto bridge. */
        const val EXTRA_CAR_ORIGIN = "car_origin"

        const val EXTRA_MANAGEMENT_PROFILE = "management_profile"
        /** S09: endpoint fingerprint bound when the notification was posted. */
        const val EXTRA_BASE_URL = "base_url"
        const val KEY_REPLY = "talaria_reply"
    }
}
