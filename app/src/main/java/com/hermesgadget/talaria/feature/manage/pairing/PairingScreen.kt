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
package com.hermesgadget.talaria.feature.manage.pairing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.domain.model.PairingResponse
import com.hermesgadget.talaria.domain.model.PairingUser
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch

@Composable
fun PairingScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    var data by remember { mutableStateOf<PairingResponse?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var revokeTarget by remember { mutableStateOf<PairingUser?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var approvalBusyKey by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() = scope.launch {
        repo.getPairing()
            .onSuccess { data = it }
            .onFailure { message = it.message }
    }
    LaunchedEffect(Unit) { reload() }

    revokeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("Revoke messaging access?") },
            text = { Text("Revoke ${target.user_name ?: target.user_id} on ${target.platform}?") },
            confirmButton = {
                TextButton(onClick = {
                    revokeTarget = null
                    scope.launch {
                        repo.revokePairing(target.platform, target.user_id)
                            .onSuccess { reload() }
                            .onFailure { message = it.message }
                    }
                }) { Text("Revoke") }
            },
            dismissButton = { TextButton(onClick = { revokeTarget = null }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear pending requests?") },
            text = { Text("Discard every pending messaging pairing request?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        repo.clearPendingPairing()
                            .onSuccess { message = "Cleared pending"; reload() }
                            .onFailure { message = it.message }
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    ScreenScaffold("Pairing", "Approve messaging users", actions = {
        TextButton(onClick = { reload() }) { Text("Refresh") }
    }) {
        Row {
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear pending") }
        }
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.secondary)
        }
        LazyColumn {
            item {
                Text("Pending", style = MaterialTheme.typography.titleLarge)
            }
            items(
                data?.pending.orEmpty(),
                key = { it.code ?: it.request_id ?: "${it.platform}-${it.user_id}" },
            ) { p ->
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${p.platform}: ${p.user_name ?: p.user_id}")
                        val code = p.code ?: p.request_id
                        Button(
                            enabled = code != null && approvalBusyKey == null,
                            onClick = {
                                code?.let { requestCode ->
                                    if (approvalBusyKey == null) {
                                        val approvalKey = "${p.platform}:$requestCode"
                                        approvalBusyKey = approvalKey
                                        scope.launch {
                                            try {
                                                repo.approvePairing(p.platform, requestCode)
                                                    .onSuccess {
                                                        message = "Approved ${p.user_name ?: p.user_id}"
                                                        reload()
                                                    }
                                                    .onFailure {
                                                        message = "Approval failed: ${it.message ?: "request rejected"}"
                                                    }
                                            } finally {
                                                approvalBusyKey = null
                                            }
                                        }
                                    }
                                }
                            },
                        ) {
                            Text(if (approvalBusyKey == "${p.platform}:$code") "Approving…" else "Approve")
                        }
                    }
                }
            }
            item {
                Text(
                    "Approved",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            items(
                data?.approved.orEmpty(),
                key = { "${it.platform}-${it.user_id}" },
            ) { p ->
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${p.platform}: ${p.user_name ?: p.user_id}")
                        TextButton(onClick = { revokeTarget = p }) { Text("Revoke") }
                    }
                }
            }
        }
    }
}
