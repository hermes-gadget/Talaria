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
package com.nousresearch.talaria.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import kotlinx.coroutines.launch

private val AmberBanner = Color(0xFFFFF3E0)
private val AmberOnBanner = Color(0xFF5D4037)

/** Global Hermes management-profile switcher (web sidebar `?profile=` parity). */
@Composable
fun ProfileSwitcherBar() {
    val container = TalariaApp.instance.container
    val connectionStore = container.connectionStore
    val profiles by connectionStore.profiles.collectAsState()
    val activeId by connectionStore.activeId.collectAsState()
    val active = profiles.find { it.id == activeId } ?: profiles.firstOrNull()
    val managementProfile = active?.managementProfile.orEmpty()
    val scope = rememberCoroutineScope()

    var hermesNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(active?.id, managementProfile) {
        container.hermesRepository.getProfiles()
            .onSuccess { list -> hermesNames = list.map { it.name } }
            .onFailure { /* keep prior list */ }
    }

    val options = remember(hermesNames) {
        (listOf("") + hermesNames).distinct()
    }
    val displayLabel = if (managementProfile.isBlank()) "default" else managementProfile

    Column(modifier = Modifier.fillMaxWidth()) {
        if (managementProfile.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AmberBanner)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Managing profile: $managementProfile",
                    color = AmberOnBanner,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Hermes profile: $displayLabel")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { name ->
                    val label = if (name.isBlank()) "default" else name
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            scope.launch {
                                connectionStore.setManagementProfile(name)
                                if (name.isNotBlank()) {
                                    container.hermesRepository.setActiveProfileName(name)
                                        .onFailure { message = it.message }
                                }
                                container.clientFactory.invalidate()
                                container.wsAuthHelper.invalidate()
                            }
                        },
                    )
                }
            }
        }
        message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
