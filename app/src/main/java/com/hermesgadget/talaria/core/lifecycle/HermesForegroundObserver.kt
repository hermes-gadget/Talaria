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
package com.hermesgadget.talaria.core.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.core.network.HermesEventClient
import com.hermesgadget.talaria.core.network.HermesSideEvent
import com.hermesgadget.talaria.core.network.WsAuthHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manages the shared sidecar sockets against the process lifecycle.
 *
 * Foreground: opens the shared [HermesEventClient] and mirrors live sidecar
 * lifecycle frames (gateway state, session changes, approvals) into the local
 * Activity timeline — the event-driven half of the feed that WorkManager polling
 * cannot provide.
 *
 * Background: drops the sockets so tickets/sockets do not linger; the next Chat
 * connect mints a fresh WS ticket via [WsAuthHelper].
 */
class HermesForegroundObserver(
    private val eventClient: HermesEventClient,
    private val wsAuth: WsAuthHelper,
    private val hermesRepository: HermesRepository,
    private val connectionStore: SecureConnectionStore,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    // Suppresses duplicate rows when the same signal repeats in quick succession.
    private var lastKey: String? = null
    private var lastAt: Long = 0

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (connectionStore.activeProfile() == null) return
        eventClient.start()
        collectJob?.cancel()
        collectJob = scope.launch(Dispatchers.IO) {
            eventClient.events.collect { record(it) }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        collectJob?.cancel()
        collectJob = null
        eventClient.stop()
        scope.launch { wsAuth.invalidate() }
    }

    private suspend fun record(event: HermesSideEvent) {
        val (type, title, body) = when (event) {
            is HermesSideEvent.Prompt ->
                Triple("chat", "Approval requested", event.message.take(160))
            is HermesSideEvent.SessionInfo ->
                Triple("chat", "Session · ${event.model ?: "agent"}", event.approvalMode.orEmpty())
            is HermesSideEvent.Raw -> when {
                event.type.startsWith("gateway") ->
                    Triple("gateway", "Gateway ${event.type.substringAfter('.')}", "")
                event.type == "sessions.changed" ->
                    Triple("chat", "Session activity", "")
                else -> return
            }
            else -> return
        }
        val key = "$type|$title"
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastAt < 4_000) return
        lastKey = key
        lastAt = now
        hermesRepository.recordActivity(type, title, body)
    }
}
