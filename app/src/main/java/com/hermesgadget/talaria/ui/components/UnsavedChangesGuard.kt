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

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Intercepts the system/gesture back press while an editor has unsaved edits and
 * asks the user to confirm before leaving (edits live only in memory, so leaving
 * discards them). Drop this into any screen with a save-then-leave flow.
 *
 * Note: this guards the back gesture — the standard "leave" action for a nested
 * screen. Switching away via the bottom navigation is a deliberate tab change and
 * is not intercepted.
 */
@Composable
fun UnsavedChangesGuard(
    hasUnsavedChanges: Boolean,
    title: String = "Discard unsaved changes?",
    message: String = "You have edits that haven't been saved. Leaving now will discard them.",
) {
    val backOwner = LocalOnBackPressedDispatcherOwner.current
    var showDialog by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    // Enabled only while there are unsaved edits and we're not already leaving, so
    // once the user confirms, the re-fired back press propagates to the NavHost.
    BackHandler(enabled = hasUnsavedChanges && !leaving) { showDialog = true }

    LaunchedEffect(leaving) {
        if (leaving) backOwner?.onBackPressedDispatcher?.onBackPressed()
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    leaving = true
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Keep editing") }
            },
        )
    }
}
