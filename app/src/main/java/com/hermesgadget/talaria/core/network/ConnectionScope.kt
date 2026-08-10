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

package com.hermesgadget.talaria.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Immutable identity for all work belonging to one saved connection/profile.
 * The generation changes whenever the active snapshot changes, including a
 * credential or URL edit that keeps the local connection id.
 */
data class ConnectionScope(
    val snapshot: ConnectionSnapshot,
    val generation: Long,
) {
    val connectionId: String get() = snapshot.connectionId
    val managementProfile: String get() = snapshot.managementProfile
    val baseUrl: String get() = snapshot.baseUrl
    val scopeId: String get() = snapshot.scopeId

    /** Stable Compose/ViewModel key; generation prevents same-id edits from being retained. */
    val key: String get() = "$connectionId|$managementProfile|$baseUrl|$generation"

    fun isSameAs(other: ConnectionScope?): Boolean = other?.key == key
}

/**
 * Small lifecycle bridge for retained ViewModels. A null flow keeps legacy
 * constructor/test seams working while production factories opt into scope
 * cancellation and rebinding.
 */
internal class ConnectionScopeObserver(
    private val flow: StateFlow<ConnectionScope?>?,
    coroutineScope: CoroutineScope,
    private val onChanged: (ConnectionScope?) -> Unit,
) {
    var current: ConnectionScope? = flow?.value
        private set

    private val job: Job? = flow?.let { source ->
        coroutineScope.launch {
            source.collect { next ->
                if (current?.key == next?.key) return@collect
                current = next
                onChanged(next)
            }
        }
    }

    fun isCurrent(expected: ConnectionScope?): Boolean {
        val source = flow ?: return true
        return source.value?.key == expected?.key
    }

    fun cancel() {
        job?.cancel()
    }
}
