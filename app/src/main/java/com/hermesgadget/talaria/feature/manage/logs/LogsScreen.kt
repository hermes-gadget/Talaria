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
package com.hermesgadget.talaria.feature.manage.logs

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.ui.components.PollEffect
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import com.hermesgadget.talaria.ui.theme.LocalSpacing
import kotlinx.coroutines.delay

@Composable
fun LogsScreen() {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    var file by remember { mutableStateOf("agent") }
    var level by remember { mutableStateOf<String?>(null) }
    var component by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var debouncedSearch by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(search) {
        delay(300)
        debouncedSearch = search
    }

    // Tail only while RESUMED so we stop polling logs when backgrounded.
    PollEffect(intervalMs = 3_000, file, level, component, debouncedSearch) {
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
            // Single compact filter row: file segmented chips + level/component dropdowns.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                listOf("agent", "gateway", "errors").forEach { f ->
                    FilterChip(selected = file == f, onClick = { file = f }, label = { Text(f) })
                }
                DropdownFilterChip(
                    label = "level",
                    value = level,
                    options = listOf("debug", "info", "warn", "error"),
                    onSelect = { level = it },
                )
                DropdownFilterChip(
                    label = "component",
                    value = component,
                    options = listOf("gateway", "agent", "tools", "cron"),
                    onSelect = { component = it },
                )
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.xs),
                singleLine = true,
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = spacing.xs),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = spacing.xs),
            ) {
                items(lines) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** Compact chip that opens a dropdown; shows `label` when unset, `label:value` when set. */
@Composable
private fun DropdownFilterChip(
    label: String,
    value: String?,
    options: List<String>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = value != null,
            onClick = { expanded = true },
            label = { Text(if (value == null) "$label:all" else "$label:$value") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("all") },
                onClick = { onSelect(null); expanded = false },
            )
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = { onSelect(opt); expanded = false },
                )
            }
        }
    }
}
