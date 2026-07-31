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

/**
 * Doze-aware periodic poll of Hermes status / pairing / cron for notifications.
 * Respects user toggles in SettingsStore; never phones home outside the user Hermes URL.
 */
class HermesSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = TalariaApp.instance.container
        if (!container.settingsStore.backgroundSyncEnabled) return Result.success()
        if (container.connectionStore.activeProfile() == null) return Result.success()
        return try {
            val snap = container.hermesRepository.pollForNotifications()
            val gatewayRunning = snap.status.gateway?.running
            if (gatewayRunning == false) {
                container.notifier.notifyGateway("Gateway stopped", "Hermes gateway is not running")
                container.hermesRepository.recordActivity(
                    "gateway",
                    "Gateway stopped",
                    "Hermes gateway is not running (pid=${snap.status.gateway?.pid})",
                )
            } else if (gatewayRunning == true) {
                container.hermesRepository.recordActivity(
                    "gateway",
                    "Gateway running",
                    "pid=${snap.status.gateway?.pid} state=${snap.status.gateway?.state}",
                )
            }
            snap.pairing?.pending?.forEach { p ->
                val body = "${p.platform}: ${p.user_name ?: p.user_id}"
                container.notifier.notifyPairing(
                    title = "Pairing request",
                    body = body,
                    platform = p.platform,
                    code = p.code ?: p.request_id,
                )
                container.hermesRepository.recordActivity("pairing", "Pairing request", body)
            }
            snap.cron.filter { it.state.equals("error", ignoreCase = true) }.forEach {
                val body = it.name ?: it.id
                container.notifier.notifyCron("Cron error", body)
                container.hermesRepository.recordActivity("cron", "Cron error", body)
            }
            // Phase 13 offline snapshot: cache a widget-friendly summary + pairing badge.
            val gw = if (gatewayRunning == true) "GW up" else "GW down"
            val pending = snap.pairing?.pending?.size ?: 0
            container.settingsStore.cachedStatusLine =
                "Hermes ${snap.status.version ?: "?"} · $gw · sessions ${snap.status.active_sessions ?: 0}"
            container.settingsStore.cachedStatusUpdatedAt = System.currentTimeMillis()
            container.settingsStore.pendingPairingCount = pending
            container.hermesRepository.recordActivity(
                "sync",
                "Background sync",
                "status ok; pending pairing=${snap.pairing?.pending?.size ?: 0}",
            )
            Result.success()
        } catch (t: Throwable) {
            container.notifier.notifyError("Sync failed", t.message ?: "unknown")
            container.hermesRepository.recordActivity("sync", "Sync failed", t.message ?: "unknown")
            Result.retry()
        }
    }
}
