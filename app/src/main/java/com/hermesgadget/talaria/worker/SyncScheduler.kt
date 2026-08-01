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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hermesgadget.talaria.TalariaApp
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val UNIQUE = "talaria_hermes_sync"

    fun ensurePeriodic(context: Context) {
        val settings = TalariaApp.instance.container.settingsStore
        if (!settings.backgroundSyncEnabled) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
            return
        }
        val minutes = settings.syncIntervalMinutes.coerceIn(15, 360)
        val request = PeriodicWorkRequestBuilder<HermesSyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
