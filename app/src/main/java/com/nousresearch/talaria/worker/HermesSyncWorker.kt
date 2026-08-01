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
import com.nousresearch.talaria.domain.model.scopeId

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
        val profile = container.connectionStore.activeProfile() ?: return Result.success()
        val scopeId = profile.scopeId()
        return try {
            val snap = container.hermesRepository.pollForNotifications()
            val gatewayRunning = snap.status.gateway?.running ?: snap.status.gateway_running
            val oldGateway = container.settingsStore.syncFingerprint(scopeId, "gateway")
            val newGateway = gatewayRunning?.let { setOf(if (it) "running" else "stopped") }.orEmpty()
            if (gatewayRunning == false && oldGateway != newGateway) {
                container.notifier.notifyGateway("Gateway stopped", "Hermes gateway is not running")
                container.hermesRepository.recordActivity(
                    "gateway",
                    "Gateway stopped",
                    "Hermes gateway is not running (pid=${snap.status.gateway?.pid ?: snap.status.gateway_pid})",
                )
            } else if (gatewayRunning == true && oldGateway != newGateway) {
                container.hermesRepository.recordActivity(
                    "gateway",
                    "Gateway running",
                    "pid=${snap.status.gateway?.pid ?: snap.status.gateway_pid} " +
                        "state=${snap.status.gateway?.state ?: snap.status.gateway_state}",
                )
            }
            if (gatewayRunning != null) {
                container.settingsStore.setSyncFingerprint(scopeId, "gateway", newGateway)
            }

            snap.pairing?.let { pairing ->
                val previous = container.settingsStore.syncFingerprint(scopeId, "pairing")
                val current = pairing.pending.associateBy { p ->
                    "${p.platform}:${p.request_id ?: p.code ?: p.user_id}"
                }
                current.filterKeys { it !in previous }.values.forEach { p ->
                    val body = "${p.platform}: ${p.user_name ?: p.user_id}"
                    container.notifier.notifyPairing(
                        title = "Pairing request",
                        body = body,
                        platform = p.platform,
                        code = p.request_id ?: p.code,
                    )
                    container.hermesRepository.recordActivity("pairing", "Pairing request", body)
                }
                container.settingsStore.setSyncFingerprint(scopeId, "pairing", current.keys)
            }

            snap.cron?.let { jobs ->
                val previous = container.settingsStore.syncFingerprint(scopeId, "cron_errors")
                val current = jobs
                    .filter { it.state.equals("error", ignoreCase = true) }
                    .associateBy { "${it.id}:${it.last_run.orEmpty()}" }
                current.filterKeys { it !in previous }.values.forEach {
                    val body = it.name ?: it.id
                    container.notifier.notifyCron("Cron error", body)
                    container.hermesRepository.recordActivity("cron", "Cron error", body)
                }
                container.settingsStore.setSyncFingerprint(scopeId, "cron_errors", current.keys)
            }
            // Phase 13 offline snapshot: cache a widget-friendly summary + pairing badge.
            val gw = if (gatewayRunning == true) "GW up" else "GW down"
            val pending = snap.pairing?.pending?.size ?: 0
            container.settingsStore.setCachedStatusLine(
                scopeId,
                "Hermes ${snap.status.version ?: "?"} · $gw · sessions ${snap.status.active_sessions ?: 0}",
            )
            container.settingsStore.setCachedStatusUpdatedAt(scopeId, System.currentTimeMillis())
            container.settingsStore.setPendingPairingCount(scopeId, pending)
            Result.success()
        } catch (t: Throwable) {
            if (runAttemptCount == 0) {
                container.notifier.notifyError("Sync failed", t.message ?: "unknown")
                container.hermesRepository.recordActivity("sync", "Sync failed", t.message ?: "unknown")
            }
            if (runAttemptCount < MAX_RETRIES - 1) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
