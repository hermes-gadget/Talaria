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


package com.nousresearch.talaria.feature.manage.mcp

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nousresearch.talaria.domain.model.McpServer
import com.nousresearch.talaria.feature.manage.SimpleManageViewModel
import com.nousresearch.talaria.ui.components.ErrorBox
import com.nousresearch.talaria.ui.components.LoadingBox
import com.nousresearch.talaria.ui.components.ScreenScaffold
import androidx.compose.ui.Modifier

@Composable
fun McpScreen(
    vm: SimpleManageViewModel = viewModel(factory = SimpleManageViewModel.factory { getMcp() }),
) {
    val ui by vm.ui.collectAsState()
    ScreenScaffold("MCP", "Model Context Protocol servers", actions = {
        TextButton(onClick = vm::refresh) { Text("Refresh") }
    }) {
        when {
            ui.loading -> LoadingBox()
            ui.error != null -> ErrorBox(ui.error!!, vm::refresh)
            else -> {
                @Suppress("UNCHECKED_CAST")
                val list = ui.data as List<McpServer>
                LazyColumn {
                    items(list, key = { it.name }) { s ->
                        Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            androidx.compose.foundation.layout.Column(modifier = Modifier.padding(12.dp)) {
                                Text(s.name, style = MaterialTheme.typography.titleLarge)
                                Text("${s.transport} · enabled=${s.enabled}")
                                Text(s.url ?: s.command ?: "")
                                Text("tools: ${s.tools?.joinToString().orEmpty()}")
                            }
                        }
                    }
                }
            }
        }
    }
}
