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


package com.nousresearch.talaria.feature.manage.logs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier

@Composable
fun LogsScreen() {
    var file by remember { mutableStateOf("agent") }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch {
            TalariaApp.instance.container.hermesRepository.getLogs(file, 200)
                .onSuccess { lines = it }
                .onFailure { error = it.message }
        }
    }
    LaunchedEffect(file) { reload() }
    ScreenScaffold("Logs", "Agent / gateway / errors", actions = {
        TextButton(onClick = { reload() }) { Text("Reload") }
    }) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("agent", "gateway", "errors").forEach { f ->
                FilterChip(selected = file == f, onClick = { file = f }, label = { Text(f) })
            }
        }
        error?.let { Text(it) }
        Text(
            lines.joinToString(separator = "\n"),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}
