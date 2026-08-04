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
import androidx.work.workDataOf
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.network.HermesEventClient
import com.hermesgadget.talaria.core.network.HermesEventScope
import com.hermesgadget.talaria.core.network.PtyPromptDelivery
import com.hermesgadget.talaria.core.network.PtyPromptDeliveryException
import com.hermesgadget.talaria.core.network.PtyWebSocketSession
import com.hermesgadget.talaria.core.network.SnapshotAuthGuard
import com.hermesgadget.talaria.ui.navigation.TalariaDeepLink
import com.hermesgadget.talaria.ui.navigation.TalariaDeepLinkParser
import kotlinx.coroutines.CancellationException

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
        // Capture the complete connection snapshot once. The foreground profile can
        // change while WorkManager is running; every socket below is bound to this
        // same profile instead of consulting the mutable global client again.
        val snapshot = container.clientFactory.snapshotFor(expectedConnectionId, expectedProfile)
            ?: return Result.failure(workDataOf(KEY_ERROR to SnapshotAuthGuard.CHANGED_MESSAGE))
        var eventClient: HermesEventClient? = null
        var session: PtyWebSocketSession? = null
        return try {
            val socketClient = container.clientFactory.webSocketClient(snapshot)
            val ptyAuth = container.wsAuthHelper.authQueryParam(snapshot)
            val channel = "reply:${id}"
            val deliveryId = inputData.getString(KEY_MESSAGE_ID).orEmpty()
                .ifBlank { id.toString() }
            val attachToken = "talaria-reply:$deliveryId"
            session = PtyWebSocketSession(
                client = socketClient,
                wsAuth = container.wsAuthHelper,
                snapshot = snapshot,
                fixedAuthQuery = ptyAuth,
            )
            val flow = session!!.connect(
                resumeSessionId = resumeSessionId,
                channelId = channel,
                attachToken = attachToken,
            )
            eventClient = HermesEventClient(
                clientFactory = container.clientFactory,
                wsAuth = container.wsAuthHelper,
                fixedSnapshot = snapshot,
                fixedEventScope = HermesEventScope(
                    connectionId = snapshot.connectionId,
                    managementProfile = snapshot.managementProfile,
                    channelId = channel,
                    tabId = "reply:${id}",
                    sessionId = resumeSessionId,
                ),
                fixedWebSocketClient = socketClient,
            )
            eventClient!!.start(channel, includeRpc = false)
            // Register the sidecar subscriber before opening the PTY. If the
            // server emits message.start immediately after Enter, this avoids
            // racing the acknowledgement itself; PTY output remains the fallback.
            eventClient!!.awaitEventsConnected()
            PtyPromptDelivery.deliver(
                session = session!!,
                ptyEvents = flow,
                text = text,
                eventClient = eventClient,
            )
            Result.success()
        } catch (failure: PtyPromptDeliveryException) {
            val message = failure.message ?: "Reply was not acknowledged"
            // A-37: runAttemptCount is 0-based — the initial attempt plus
            // (MAX_ATTEMPTS - 1) retries, so compare against MAX_ATTEMPTS - 1.
            if (!failure.frameAccepted && runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                container.notifier.notifyError("Reply delivery failed", message)
                Result.failure(workDataOf(KEY_ERROR to message))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val message = failure.message ?: "Reply delivery failed"
            if (message.contains("saved connection changed", ignoreCase = true)) {
                container.notifier.notifyError("Reply delivery canceled", message)
                Result.failure(workDataOf(KEY_ERROR to message))
            } else if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                container.notifier.notifyError("Reply delivery failed", message)
                Result.failure(workDataOf(KEY_ERROR to message))
            }
        } finally {
            eventClient?.dispose()
            session?.close()
        }
    }

    companion object {
        const val KEY_TEXT = "text"
        const val KEY_DEEP_LINK = "deep_link"
        const val KEY_CONNECTION_ID = "connection_id"
        const val KEY_MANAGEMENT_PROFILE = "management_profile"
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 3

        internal fun sessionIdFromDeepLink(deepLink: String?): String? =
            (deepLink?.let(TalariaDeepLinkParser::parse) as? TalariaDeepLink.Session)?.id
    }
}
