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
package com.nousresearch.talaria.feature.manage.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier

@Composable
fun SessionsScreen(onOpen: (String) -> Unit, onResume: (String) -> Unit) {
    val repo = TalariaApp.instance.container.hermesRepository
    val cached by repo.observeSessions().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { repo.refreshSessions() }
    ScreenScaffold("Sessions", "Cached locally · pull from Hermes", actions = {
        TextButton(onClick = { scope.launch { repo.refreshSessions() } }) { Text("Refresh") }
    }) {
        LazyColumn {
            items(cached, key = { it.id }) { s ->
                Surface(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(s.id) },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(s.title ?: s.preview ?: s.id, style = MaterialTheme.typography.titleLarge)
                        Text("${s.source ?: "?"} · ${s.model ?: "?"} · ${s.messageCount ?: 0} msgs")
                        Row {
                            TextButton(onClick = { onResume(s.id) }) { Text("Resume") }
                            TextButton(onClick = { onOpen(s.id) }) { Text("Open") }
                        }
                    }
                }
            }
        }
    }
}
