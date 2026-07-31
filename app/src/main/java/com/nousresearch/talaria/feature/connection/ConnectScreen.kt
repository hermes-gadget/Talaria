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


package com.nousresearch.talaria.feature.connection

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nousresearch.talaria.domain.model.AuthMode
import com.nousresearch.talaria.ui.components.ScreenScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    initialProfile: String? = null,
    vm: ConnectViewModel = viewModel(factory = ConnectViewModel.factory()),
) {
    val ui by vm.ui.collectAsState()
    val profiles by vm.profiles.collectAsState()
    var authExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(initialProfile) {
        vm.applyDeepLinkProfile(initialProfile)
    }

    ScreenScaffold(
        title = "Talaria",
        subtitle = "Connect to your self-hosted Hermes Agent",
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Privacy-respecting mobile client for Hermes. Credentials stay in the Android Keystore.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = ui.name,
                onValueChange = { v -> vm.update { it.copy(name = v) } },
                label = { Text("Profile name") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ui.baseUrl,
                onValueChange = { v -> vm.update { it.copy(baseUrl = v) } },
                label = { Text("Dashboard URL") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("Tailscale/LAN host, or http://10.0.2.2:9119 from the Android emulator (not 127.0.0.1)")
                },
            )
            ExposedDropdownMenuBox(expanded = authExpanded, onExpandedChange = { authExpanded = it }) {
                OutlinedTextField(
                    value = ui.authMode.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Auth mode") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(authExpanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = authExpanded, onDismissRequest = { authExpanded = false }) {
                    AuthMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.name) },
                            onClick = {
                                vm.update { it.copy(authMode = mode) }
                                authExpanded = false
                            },
                        )
                    }
                }
            }
            if (ui.authMode == AuthMode.SESSION_TOKEN || ui.authMode == AuthMode.NONE) {
                OutlinedTextField(
                    value = ui.sessionToken,
                    onValueChange = { v -> vm.update { it.copy(sessionToken = v) } },
                    label = { Text("Session token") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (ui.authMode == AuthMode.BASIC) {
                OutlinedTextField(
                    value = ui.username,
                    onValueChange = { v -> vm.update { it.copy(username = v) } },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ui.password,
                    onValueChange = { v -> vm.update { it.copy(password = v) } },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (ui.authMode == AuthMode.BEARER) {
                OutlinedTextField(
                    value = ui.bearerToken,
                    onValueChange = { v -> vm.update { it.copy(bearerToken = v) } },
                    label = { Text("Bearer token") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (ui.authMode == AuthMode.OIDC_BROWSER) {
                Text(
                    "Opens the dashboard login in a Custom Tab. After the browser redirects to talaria://, cookies are kept in the app jar — then Save & connect. If login doesn’t round-trip, paste a session token under SESSION_TOKEN instead.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        CustomTabsIntent.Builder().build()
                            .launchUrl(context, Uri.parse(vm.portalLoginUrl()))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open portal login") }
            }
            OutlinedTextField(
                value = ui.managementProfile,
                onValueChange = { v -> vm.update { it.copy(managementProfile = v) } },
                label = { Text("Management profile (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ui.pinSha256,
                onValueChange = { v -> vm.update { it.copy(pinSha256 = v) } },
                label = { Text("TLS pin sha256/… (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ui.statusLine?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
            Button(
                onClick = { vm.saveAndTest(onConnected) },
                enabled = !ui.testing && !ui.diagnosing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (ui.testing) "Connecting…" else "Save & connect") }
            OutlinedButton(
                onClick = vm::runConnectionDoctor,
                enabled = !ui.testing && !ui.diagnosing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (ui.diagnosing) "Diagnosing…" else "Connection doctor") }
            ui.doctorReport?.let { report ->
                Text("Diagnosis", style = MaterialTheme.typography.titleMedium)
                Text(report, style = MaterialTheme.typography.bodySmall)
            }
            if (profiles.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Saved connections", style = MaterialTheme.typography.titleLarge)
                profiles.forEach { p ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { vm.select(p.id); onConnected() }) { Text(p.name) }
                        TextButton(onClick = { vm.delete(p.id) }) { Text("Delete") }
                    }
                }
            }
            OutlinedButton(
                onClick = { vm.saveAndContinue(onConnected) },
                enabled = !ui.testing && !ui.diagnosing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue without testing")
            }
        }
    }
}
