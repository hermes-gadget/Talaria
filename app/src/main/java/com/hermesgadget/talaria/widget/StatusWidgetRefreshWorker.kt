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

package com.hermesgadget.talaria.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hermesgadget.talaria.TalariaApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

internal object StatusWidgetRefreshScheduler {
    private const val UNIQUE_WORK = "talaria_status_widget_refresh"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<StatusWidgetRefreshWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

class StatusWidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = TalariaApp.instance.container
        val snapshot = container.clientFactory.snapshot() ?: return Result.success()
        return try {
            container.hermesRepository.refreshStatus(snapshot).fold(
                onSuccess = { status ->
                    val scopeId = snapshot.scopeId
                    container.settingsStore.setCachedStatusLine(
                        scopeId,
                        formatWidgetStatus(applicationContext, status),
                    )
                    container.settingsStore.setCachedStatusUpdatedAt(
                        scopeId,
                        System.currentTimeMillis(),
                    )
                    TalariaStatusWidget().updateAll(applicationContext)
                    Result.success()
                },
                onFailure = { retryOrFail() },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            retryOrFail()
        }
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_RETRIES - 1) Result.retry() else Result.failure()

    private companion object {
        const val MAX_RETRIES = 3
    }
}
