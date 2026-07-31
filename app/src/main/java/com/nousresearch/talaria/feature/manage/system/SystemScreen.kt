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


package com.nousresearch.talaria.feature.manage.system

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.SystemStats
import com.nousresearch.talaria.feature.manage.SimpleManageViewModel
import com.nousresearch.talaria.ui.components.ErrorBox
import com.nousresearch.talaria.ui.components.KeyValueList
import com.nousresearch.talaria.ui.components.LoadingBox
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch

@Composable
fun SystemScreen(
    vm: SimpleManageViewModel = viewModel(factory = SimpleManageViewModel.factory { getSystemStats() }),
) {
    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    val repo = TalariaApp.instance.container.hermesRepository
    ScreenScaffold("System", "Host stats & gateway ops", actions = {
        TextButton(onClick = vm::refresh) { Text("Refresh") }
    }) {
        when {
            ui.loading -> LoadingBox()
            ui.error != null -> ErrorBox(ui.error!!, vm::refresh)
            else -> {
                val s = ui.data as SystemStats
                KeyValueList(
                    listOf(
                        "OS" to (s.os ?: "—"),
                        "Host" to (s.hostname ?: "—"),
                        "Python" to (s.python ?: "—"),
                        "Hermes" to (s.hermes_version ?: "—"),
                        "CPU %" to (s.cpu_percent?.toString() ?: "—"),
                    ),
                )
                Row {
                    Button(onClick = { scope.launch { repo.gateway("start") } }) { Text("Start GW") }
                    Button(onClick = { scope.launch { repo.gateway("stop") } }) { Text("Stop") }
                    Button(onClick = { scope.launch { repo.gateway("restart") } }) { Text("Restart") }
                }
            }
        }
    }
}
