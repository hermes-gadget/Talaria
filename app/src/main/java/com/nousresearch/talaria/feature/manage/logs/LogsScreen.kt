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

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun LogsScreen() {
    val context = LocalContext.current
    var file by remember { mutableStateOf("agent") }
    var level by remember { mutableStateOf<String?>(null) }
    var component by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var debouncedSearch by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(search) {
        delay(300)
        debouncedSearch = search
    }

    LaunchedEffect(file, level, component, debouncedSearch) {
        while (isActive) {
            TalariaApp.instance.container.hermesRepository
                .getLogs(
                    file = file,
                    lines = 200,
                    level = level,
                    component = component,
                    search = debouncedSearch.ifBlank { null },
                )
                .onSuccess {
                    lines = it
                    error = null
                }
                .onFailure { error = it.message }
            delay(3_000)
        }
    }

    ScreenScaffold("Logs", "Auto-tail · 3s", actions = {
        TextButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
                    putExtra(Intent.EXTRA_TITLE, "hermes-$file.log")
                }
                context.startActivity(Intent.createChooser(send, "Share logs"))
            },
            enabled = lines.isNotEmpty(),
        ) { Text("Share") }
    }) {
        Column {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf("agent", "gateway", "errors").forEach { f ->
                    FilterChip(selected = file == f, onClick = { file = f }, label = { Text(f) })
                }
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(
                    selected = level == null,
                    onClick = { level = null },
                    label = { Text("level:all") },
                )
                listOf("debug", "info", "warn", "error").forEach { lv ->
                    FilterChip(
                        selected = level == lv,
                        onClick = { level = lv },
                        label = { Text(lv) },
                    )
                }
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(
                    selected = component == null,
                    onClick = { component = null },
                    label = { Text("component:all") },
                )
                listOf("gateway", "agent", "tools", "cron").forEach { c ->
                    FilterChip(
                        selected = component == c,
                        onClick = { component = c },
                        label = { Text(c) },
                    )
                }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            error?.let { Text(it) }
            Text(
                lines.joinToString(separator = "\n"),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        }
    }
}
