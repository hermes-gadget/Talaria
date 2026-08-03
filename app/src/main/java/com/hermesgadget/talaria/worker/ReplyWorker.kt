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


package com.hermesgadget.talaria.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.ui.navigation.TalariaDeepLink
import com.hermesgadget.talaria.ui.navigation.TalariaDeepLinkParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.util.UUID

private class ReplySent : CancellationException()

/** Delivers notification inline-replies by opening a short-lived PTY send. */
class ReplyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val text = inputData.getString(KEY_TEXT) ?: return Result.failure()
        val resumeSessionId = sessionIdFromDeepLink(inputData.getString(KEY_DEEP_LINK))
        val container = TalariaApp.instance.container
        val expectedConnectionId = inputData.getString(KEY_CONNECTION_ID) ?: return Result.failure()
        val expectedProfile = inputData.getString(KEY_MANAGEMENT_PROFILE).orEmpty()
        val active = container.connectionStore.activeProfile() ?: return Result.failure()
        if (active.id != expectedConnectionId || active.managementProfile != expectedProfile) {
            return Result.failure()
        }
        return try {
            val attachToken = UUID.randomUUID().toString()
            val (session, flow) = container.chatRepository.openPty(
                resumeSessionId = resumeSessionId,
                attachToken = attachToken,
            )
            // The TUI is a fresh process per PTY: wait for its first output frame
            // (the banner) before sending — an immediate send races startup and
            // the prompt is silently dropped. Brief settle beat, then send.
            // The socket then closes, but the keep-alive registry keeps the PTY
            // (and the agent's run) alive server-side.
            var sent = false
            kotlinx.coroutines.withTimeout(15_000) {
                flow.collect { event ->
                    if (event is com.hermesgadget.talaria.core.network.PtyEvent.Connected) {
                        // Connected only marks the WS open; hold until output.
                    }
                    if (event is com.hermesgadget.talaria.core.network.PtyEvent.Output && !sent) {
                        sent = true
                        kotlinx.coroutines.delay(350)
                        session.sendText(text)
                        session.close()
                        throw ReplySent()
                    }
                    if (event is com.hermesgadget.talaria.core.network.PtyEvent.Failure) {
                        throw IOException(event.message)
                    }
                    if (event is com.hermesgadget.talaria.core.network.PtyEvent.Closed && !sent) {
                        throw IOException("Chat closed before reply was sent (${event.code})")
                    }
                }
            }
            Result.success()
        } catch (_: ReplySent) {
            Result.success()
        } catch (_: TimeoutCancellationException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_TEXT = "text"
        const val KEY_DEEP_LINK = "deep_link"
        const val KEY_CONNECTION_ID = "connection_id"
        const val KEY_MANAGEMENT_PROFILE = "management_profile"

        internal fun sessionIdFromDeepLink(deepLink: String?): String? =
            (deepLink?.let(TalariaDeepLinkParser::parse) as? TalariaDeepLink.Session)?.id
    }
}
