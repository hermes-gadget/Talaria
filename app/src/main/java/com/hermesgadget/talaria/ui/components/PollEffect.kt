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
package com.hermesgadget.talaria.ui.components

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Runs [block] immediately and then every [intervalMs], but ONLY while the screen
 * is at least STARTED. A plain `LaunchedEffect { while (isActive) … }` keeps firing
 * when the app is backgrounded (the effect isn't lifecycle-aware), so poll-based
 * screens like Status and Logs would keep hitting the network off-screen. Gating on
 * [Lifecycle.State.STARTED] pauses the loop below a visible screen and resumes it on return,
 * saving battery, CPU and network. Pass [keys] to restart the loop (e.g. a manual
 * refresh tick or a changed filter).
 */
@Composable
fun PollEffect(
    intervalMs: Long,
    vararg keys: Any?,
    block: suspend () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(lifecycleOwner, *keys) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                block()
                delay(intervalMs)
            }
        }
    }
}
