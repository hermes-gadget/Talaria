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

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.SessionMessage
import com.nousresearch.talaria.ui.components.ScreenScaffold
import androidx.compose.ui.Modifier

@Composable
fun SessionDetailScreen(sessionId: String) {
    var messages by remember { mutableStateOf<List<SessionMessage>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionId) {
        TalariaApp.instance.container.hermesRepository.loadMessages(sessionId)
            .onSuccess { messages = it }
            .onFailure { error = it.message }
    }
    ScreenScaffold("Session", sessionId) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn {
            items(messages.size) { idx ->
                val m = messages[idx]
                Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    androidx.compose.foundation.layout.Column(modifier = Modifier.padding(12.dp)) {
                        Text(m.role ?: "?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                        Text(m.content ?: "", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
