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


package com.hermesgadget.talaria.feature.connection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStoreState
import com.hermesgadget.talaria.core.data.prefs.SecureStoreDiagnostics
import com.hermesgadget.talaria.core.network.ConnectionOrigin
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.core.net.toUri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    initialProfile: String? = null,
    vm: ConnectViewModel = viewModel(factory = ConnectViewModel.factory()),
) {
    val ui by vm.ui.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val secureStoreState by vm.secureStoreState.collectAsState()
    var authExpanded by remember { mutableStateOf(false) }
    var oidcProviderExpanded by remember { mutableStateOf(false) }
    var deleteProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var confirmSecureReset by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Hoisted out of the onCopyDiagnostics lambda: LocalContext reads at
    // callback time don't track Configuration changes (lint LocalContextGetResourceValueCall).
    val secureStoreDiagnosticsLabel = stringResource(R.string.secure_store_diagnostics_label)

    if (confirmSecureReset) {
        AlertDialog(
            onDismissRequest = { confirmSecureReset = false },
            title = { Text(stringResource(R.string.secure_store_reset_title)) },
            text = { Text(stringResource(R.string.secure_store_reset_explanation)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSecureReset = false
                    vm.resetEncryptedConnections()
                }) { Text(stringResource(R.string.secure_store_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSecureReset = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    deleteProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteProfile = null },
            title = { Text("Delete saved connection?") },
            text = { Text("Delete '${profile.name}' and its encrypted credentials from this device?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteProfile = null
                    vm.delete(profile.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteProfile = null }) { Text("Cancel") } },
        )
    }

    ui.cleartextConsentRequest?.let { request ->
        AlertDialog(
            onDismissRequest = vm::declineCleartextConsent,
            title = { Text("Allow unencrypted connection?") },
            text = {
                Text(
                    "This dashboard uses plain http:// at ${request.origin}, a private/local address. " +
                        "Your Hermes session credentials would be sent without encryption over your network. " +
                        "Only allow this for a trusted Hermes host on your own LAN or Tailscale mesh.",
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmCleartextConsent) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = vm::declineCleartextConsent) { Text("Don't allow") }
            },
        )
    }

    LaunchedEffect(initialProfile) {
        vm.applyDeepLinkProfile(initialProfile)
    }
    LaunchedEffect(secureStoreState is SecureConnectionStoreState.Available) {
        if (secureStoreState is SecureConnectionStoreState.Available) vm.loadProviderOnboarding()
    }

    val draftUrl = remember(ui.baseUrl) { ui.baseUrl.trim().trimEnd('/').toHttpUrlOrNull() }

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
            if (secureStoreState !is SecureConnectionStoreState.Available) {
                SecureStoreRecoveryPanel(
                    state = secureStoreState,
                    onRetry = vm::retrySecureStore,
                    onCopyDiagnostics = { diagnostics ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                secureStoreDiagnosticsLabel,
                                diagnostics.copyText(),
                            ),
                        )
                    },
                    onReset = { confirmSecureReset = true },
                )
                return@ScreenScaffold
            }
            if (draftUrl?.scheme == "http") {
                Text(
                    "HTTP — local network traffic is unencrypted",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (ui.cleartextConsentApproved &&
                    ui.cleartextConsentOrigin == draftUrl?.let(ConnectionOrigin::normalize)
                ) {
                    TextButton(onClick = { vm.revokeCleartextConsent() }) {
                        Text("Revoke HTTP consent")
                    }
                }
            }
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
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            if (ui.authMode == AuthMode.BASIC) {
                OutlinedTextField(
                    value = ui.passwordProvider,
                    onValueChange = { v -> vm.update { it.copy(passwordProvider = v) } },
                    label = { Text("Password provider") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Leave blank when Hermes advertises exactly one password provider") },
                )
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
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            if (ui.authMode == AuthMode.BEARER) {
                OutlinedTextField(
                    value = ui.bearerToken,
                    onValueChange = { v -> vm.update { it.copy(bearerToken = v) } },
                    label = { Text("Bearer token") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            if (ui.authMode == AuthMode.OIDC_BROWSER) {
                Text(
                    "Uses Hermes native PKCE sign-in: the browser authenticates with your provider, then returns a one-time code to Talaria. Access and refresh tokens are encrypted with Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (ui.oidcProviders.size > 1) {
                    ExposedDropdownMenuBox(
                        expanded = oidcProviderExpanded,
                        onExpandedChange = { oidcProviderExpanded = it },
                    ) {
                        val selected = ui.oidcProviders.firstOrNull { it.name == ui.oidcProvider }
                        OutlinedTextField(
                            value = selected?.displayName ?: "Choose provider",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("OAuth provider") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(oidcProviderExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = oidcProviderExpanded,
                            onDismissRequest = { oidcProviderExpanded = false },
                        ) {
                            ui.oidcProviders.forEach { provider ->
                                DropdownMenuItem(
                                    text = { Text(provider.displayName) },
                                    onClick = {
                                        vm.update { it.copy(oidcProvider = provider.name) }
                                        oidcProviderExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        vm.startOidcLogin(
                            openBrowser = { url ->
                                CustomTabsIntent.Builder().build()
                                    .launchUrl(context, url.toUri())
                            },
                            onSuccess = onConnected,
                        )
                    },
                    enabled = !ui.oidcSigningIn,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (ui.oidcSigningIn) "Waiting for browser…" else "Sign in with browser") }
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
            ProviderOnboardingSection(
                ui = ui,
                vm = vm,
                openBrowser = { url ->
                    CustomTabsIntent.Builder().build()
                        .launchUrl(context, url.toUri())
                },
            )
            if (profiles.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Saved connections", style = MaterialTheme.typography.titleLarge)
                profiles.forEach { p ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            if (p.baseUrl.trim().startsWith("http://", ignoreCase = true)) {
                                Text(
                                    "HTTP — local network traffic is unencrypted",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        TextButton(onClick = { vm.edit(p) }) { Text("Edit") }
                        TextButton(onClick = { vm.select(p.id); onConnected() }) { Text("Use") }
                        if (p.cleartextConsentRecorded == true) {
                            TextButton(onClick = { vm.revokeCleartextConsent(p.id) }) { Text("Revoke") }
                        }
                        TextButton(onClick = { deleteProfile = p }) { Text("Delete") }
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

@Composable
private fun SecureStoreRecoveryPanel(
    state: SecureConnectionStoreState,
    onRetry: () -> Unit,
    onCopyDiagnostics: (SecureStoreDiagnostics) -> Unit,
    onReset: () -> Unit,
) {
    val diagnostics = when (state) {
        is SecureConnectionStoreState.Available -> return
        is SecureConnectionStoreState.RecoverableCorruption -> state.diagnostics
        is SecureConnectionStoreState.PermanentKeystoreLoss -> state.diagnostics
    }
    Text(
        stringResource(
            if (state is SecureConnectionStoreState.PermanentKeystoreLoss) {
                R.string.secure_store_permanent_title
            } else {
                R.string.secure_store_corrupt_title
            },
        ),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.error,
    )
    Text(
        stringResource(
            if (state is SecureConnectionStoreState.PermanentKeystoreLoss) {
                R.string.secure_store_permanent_message
            } else {
                R.string.secure_store_corrupt_message
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(diagnostics.copyText(), style = MaterialTheme.typography.bodySmall)
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.secure_store_retry))
    }
    OutlinedButton(onClick = { onCopyDiagnostics(diagnostics) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.secure_store_copy_diagnostics))
    }
    Text(stringResource(R.string.secure_store_reset_documentation), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.secure_store_reset_action))
    }
}
