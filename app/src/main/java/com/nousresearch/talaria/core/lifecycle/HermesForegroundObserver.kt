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
package com.nousresearch.talaria.core.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.nousresearch.talaria.core.network.HermesEventClient
import com.nousresearch.talaria.core.network.WsAuthHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Drops chat sidecar sockets when the process backgrounds so tickets/sockets
 * do not linger. WorkManager sync continues independently when enabled.
 * The next Chat connect mints a fresh WS ticket via [WsAuthHelper].
 */
class HermesForegroundObserver(
    private val eventClient: HermesEventClient,
    private val wsAuth: WsAuthHelper,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        eventClient.stop()
        scope.launch { wsAuth.invalidate() }
    }
}
