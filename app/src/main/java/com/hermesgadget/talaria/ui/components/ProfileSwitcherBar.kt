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
package com.hermesgadget.talaria.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.TalariaApp
import kotlinx.coroutines.launch

/**
 * Hermes management-profile switcher (web sidebar `?profile=` parity).
 *
 * A compact top-bar chip rather than a full-width global strip: neutral on the
 * default profile, highlighted (amber) when managing a non-default profile so
 * the scoped state stays obvious without spending a whole row on every screen.
 * Placed in the [ScreenScaffold] actions slot on the top-level tabs.
 */
@Composable
fun ProfileSwitcherChip() {
    val container = TalariaApp.instance.container
    val connectionStore = container.connectionStore
    val profiles by connectionStore.profiles.collectAsState()
    val activeId by connectionStore.activeId.collectAsState()
    val active = profiles.find { it.id == activeId } ?: profiles.firstOrNull()
    val managementProfile = active?.managementProfile.orEmpty()
    val scope = rememberCoroutineScope()

    if (profiles.isEmpty()) return

    var hermesNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }

    LaunchedEffect(active?.id, managementProfile) {
        // A different server's profiles must never leak into this server's list.
        hermesNames = emptyList()
        container.hermesRepository.getProfiles()
            .onSuccess { list -> hermesNames = list.map { it.name } }
            .onFailure { /* keep the cleared list — stale names are worse than none */ }
    }

    val options = remember(hermesNames) {
        (listOf("") + hermesNames).distinct()
    }
    val nonDefault = managementProfile.isNotBlank()
    val displayLabel = if (nonDefault) managementProfile else stringResource(R.string.common_default)

    AssistChip(
        onClick = { if (!switching) expanded = true },
        label = { Text(displayLabel, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = {
            Icon(Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        trailingIcon = {
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(R.string.profile_switch),
                modifier = Modifier.size(18.dp),
            )
        },
        border = if (nonDefault) null else AssistChipDefaults.assistChipBorder(enabled = true),
        colors = if (nonDefault) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                trailingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            AssistChipDefaults.assistChipColors()
        },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEach { name ->
            val label = if (name.isBlank()) stringResource(R.string.common_default) else name
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    expanded = false
                    scope.launch {
                        switching = true
                        // This chip scopes Talaria's API/WS requests only. The
                        // Profiles screen has the separate, explicit action that
                        // changes Hermes' sticky CLI default on the host.
                        connectionStore.setManagementProfile(name)
                        container.hermesRepository.clearCache()
                        container.eventClient.stop()
                        container.clientFactory.invalidate()
                        container.wsAuthHelper.invalidate()
                        switching = false
                    }
                },
            )
        }
    }
}
