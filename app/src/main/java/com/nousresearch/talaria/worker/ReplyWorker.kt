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


package com.nousresearch.talaria.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nousresearch.talaria.TalariaApp

/** Delivers notification inline-replies by opening a short-lived PTY send. */
class ReplyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val text = inputData.getString(KEY_TEXT) ?: return Result.failure()
        val container = TalariaApp.instance.container
        return try {
            val (session, flow) = container.chatRepository.openPty()
            // Best-effort: wait for connect then send
            kotlinx.coroutines.withTimeout(15_000) {
                flow.collect { event ->
                    if (event is com.nousresearch.talaria.core.network.PtyEvent.Connected) {
                        session.sendText(text)
                        session.close()
                        throw kotlinx.coroutines.CancellationException("sent")
                    }
                    if (event is com.nousresearch.talaria.core.network.PtyEvent.Failure) {
                        error(event.message)
                    }
                }
            }
            Result.success()
        } catch (_: kotlinx.coroutines.CancellationException) {
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_TEXT = "text"
        const val KEY_DEEP_LINK = "deep_link"
    }
}
