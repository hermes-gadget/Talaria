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


package com.nousresearch.talaria.feature.manage.status

import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nousresearch.talaria.domain.model.StatusResponse
import com.nousresearch.talaria.feature.manage.SimpleManageViewModel
import com.nousresearch.talaria.ui.components.ErrorBox
import com.nousresearch.talaria.ui.components.KeyValueList
import com.nousresearch.talaria.ui.components.LoadingBox
import com.nousresearch.talaria.ui.components.ScreenScaffold

@Composable
fun StatusScreen(
    vm: SimpleManageViewModel = viewModel(factory = SimpleManageViewModel.factory { refreshStatus() }),
) {
    val ui by vm.ui.collectAsState()
    ScreenScaffold("Status", "Live Hermes overview", actions = {
        TextButton(onClick = vm::refresh) { Text("Refresh") }
    }) {
        when {
            ui.loading -> LoadingBox()
            ui.error != null -> ErrorBox(ui.error!!, vm::refresh)
            else -> {
                val s = ui.data as StatusResponse
                KeyValueList(
                    listOf(
                        "Version" to (s.version ?: "—"),
                        "Auth required" to (s.auth_required?.toString() ?: "—"),
                        "Providers" to s.auth_providers.joinToString(),
                        "Gateway" to (if (s.gateway?.running == true) "running pid=${s.gateway?.pid}" else "stopped"),
                        "Active sessions" to (s.active_sessions?.toString() ?: "—"),
                        "Recent sessions" to s.sessions.size.toString(),
                    ),
                )
            }
        }
    }
}
