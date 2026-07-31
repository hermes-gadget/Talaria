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
package com.nousresearch.talaria.feature.manage.pairing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.PairingResponse
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch

@Composable
fun PairingScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    var data by remember { mutableStateOf<PairingResponse?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() = scope.launch {
        repo.getPairing()
            .onSuccess { data = it }
            .onFailure { message = it.message }
    }
    LaunchedEffect(Unit) { reload() }

    ScreenScaffold("Pairing", "Approve messaging users", actions = {
        TextButton(onClick = { reload() }) { Text("Refresh") }
    }) {
        Row {
            OutlinedButton(onClick = {
                scope.launch {
                    repo.clearPendingPairing()
                        .onSuccess {
                            message = "Cleared pending"
                            reload()
                        }
                        .onFailure { message = it.message }
                }
            }) { Text("Clear pending") }
        }
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.secondary)
        }
        LazyColumn {
            item {
                Text("Pending", style = MaterialTheme.typography.titleLarge)
            }
            items(data?.pending.orEmpty()) { p ->
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${p.platform}: ${p.user_name ?: p.user_id}")
                        Button(onClick = {
                            scope.launch {
                                val code = p.code ?: p.request_id ?: return@launch
                                repo.approvePairing(p.platform, code)
                                reload()
                            }
                        }) { Text("Approve") }
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
            items(data?.approved.orEmpty()) { p ->
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${p.platform}: ${p.user_name ?: p.user_id}")
                        TextButton(onClick = {
                            scope.launch { repo.revokePairing(p.platform, p.user_id); reload() }
                        }) { Text("Revoke") }
                    }
                }
            }
        }
    }
}
