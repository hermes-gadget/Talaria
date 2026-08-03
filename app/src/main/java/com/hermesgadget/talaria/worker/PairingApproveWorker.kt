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
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/** Approves a pending pairing request straight from a notification action. */
class PairingApproveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val platform = inputData.getString(KEY_PLATFORM) ?: return Result.failure()
        val code = inputData.getString(KEY_CODE) ?: return Result.failure()
        val container = TalariaApp.instance.container
        val expectedConnectionId = inputData.getString(KEY_CONNECTION_ID) ?: return Result.failure()
        val expectedProfile = inputData.getString(KEY_MANAGEMENT_PROFILE).orEmpty()
        val snapshot = container.clientFactory.snapshotFor(expectedConnectionId, expectedProfile)
            ?: return Result.failure()
        // The request and its auth/profile query are now bound to this snapshot;
        // a foreground connection switch cannot redirect the approval.
        return container.hermesRepository.approvePairing(platform, code, snapshot).fold(
            onSuccess = {
                container.hermesRepository.recordActivity(
                    "pairing",
                    "Pairing approved",
                    "$platform approved from notification",
                    snapshot,
                )
                Result.success()
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                val retriable = (error as? HttpException)?.code()?.let { it == 408 || it == 429 || it >= 500 }
                    ?: true
                if (retriable && runAttemptCount < MAX_RETRIES - 1) Result.retry() else Result.failure()
            },
        )
    }

    companion object {
        const val KEY_PLATFORM = "platform"
        const val KEY_CODE = "code"
        const val KEY_CONNECTION_ID = "connection_id"
        const val KEY_MANAGEMENT_PROFILE = "management_profile"
        private const val MAX_RETRIES = 3
    }
}
