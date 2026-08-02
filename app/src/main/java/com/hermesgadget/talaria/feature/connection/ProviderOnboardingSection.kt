/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.domain.model.CustomEndpoint
import com.hermesgadget.talaria.domain.model.ProviderOAuth

@Composable
fun ProviderOnboardingSection(
    ui: ConnectUiState,
    vm: ConnectViewModel,
    openBrowser: (String) -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Text("Providers", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Step 2 · Choose a provider, validate credentials, add a custom OpenAI-compatible endpoint, or finish provider OAuth.",
        style = MaterialTheme.typography.bodySmall,
    )

    when (val state = ui.providerSection) {
        ProviderSectionState.Idle -> {
            Text(
                "Provider settings are loaded after the connection profile is saved.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = vm::saveConnectionAndLoadProviders,
                enabled = ui.providerBusy == null && !ui.testing && !ui.diagnosing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save connection & load providers") }
        }
        ProviderSectionState.Loading -> {
            Text("Loading provider settings…", style = MaterialTheme.typography.bodySmall)
        }
        is ProviderSectionState.Failure -> {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(
                onClick = if (state.previous == null) vm::saveConnectionAndLoadProviders else vm::loadProviderOnboarding,
                enabled = ui.providerBusy == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.previous == null) "Save connection & retry" else "Retry provider load") }
            state.previous?.let { content ->
                ProviderContent(
                    ui = ui,
                    vm = vm,
                    content = content,
                    openBrowser = openBrowser,
                )
            }
        }
        is ProviderSectionState.Content -> ProviderContent(
            ui = ui,
            vm = vm,
            content = state.value,
            openBrowser = openBrowser,
        )
    }

    ui.providerNotice?.let { notice ->
        Text(notice, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
    }

    ui.providerConfirmation?.let { confirmation ->
        val title: String
        val message: String
        when (confirmation) {
            is ProviderConfirmation.SaveCredential -> {
                title = if (confirmation.replacing == null) "Save credential?" else "Replace credential?"
                message = if (confirmation.replacing == null) {
                    "Hermes will store this provider credential in its server-side pool. The full key is never shown here."
                } else {
                    "This adds the new key, then removes the confirmed ${confirmation.replacing.label.ifBlank { "credential" }} entry. The old key will not be recoverable."
                }
            }
            is ProviderConfirmation.RemoveCredential -> {
                title = "Remove credential?"
                message = "Remove ${confirmation.entry.label.ifBlank { "this credential" }} from ${confirmation.provider}? This cannot be undone."
            }
            is ProviderConfirmation.RemoveEndpoint -> {
                title = "Remove custom endpoint?"
                message = "Remove ${confirmation.endpoint.name.ifBlank { "this endpoint" }}? This cannot be undone."
            }
        }
        AlertDialog(
            onDismissRequest = vm::cancelProviderConfirmation,
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::confirmProviderAction) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = vm::cancelProviderConfirmation) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProviderContent(
    ui: ConnectUiState,
    vm: ConnectViewModel,
    content: ProviderOnboardingContent,
    openBrowser: (String) -> Unit,
) {
    content.notices.forEach { notice ->
        Text(notice, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
    }

    Text("Available providers", style = MaterialTheme.typography.titleLarge)
    if (content.providers.isEmpty()) {
        Text("No provider catalog entries were returned.", style = MaterialTheme.typography.bodySmall)
    } else {
        content.providers.forEach { provider ->
            val key = providerKey(provider)
            val active = provider.isCurrent || content.activeProvider.equals(key, ignoreCase = true)
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(providerLabel(provider), style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                append(key)
                                if (provider.models.isNotEmpty()) append(" · ${provider.models.size} models")
                                provider.authType?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (active) Text("Active", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    content.activeProvider?.let { active ->
        Text(
            "Active provider: $active${content.activeModel?.let { " · $it" }.orEmpty()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    Spacer(Modifier.height(8.dp))
    Text("Provider OAuth", style = MaterialTheme.typography.titleLarge)
    if (content.oauthProviders.isEmpty()) {
        Text("OAuth is not advertised by this server. Enter an API key below.", style = MaterialTheme.typography.bodySmall)
    } else {
        content.oauthProviders.forEach { oauth ->
            OAuthProviderRow(ui, vm, oauth, openBrowser)
        }
    }
    ui.providerOAuthSession?.let { session ->
        OAuthSessionCard(ui, vm, session, openBrowser)
    }

    Spacer(Modifier.height(8.dp))
    CredentialPoolEditor(ui, vm, content)

    Spacer(Modifier.height(8.dp))
    CustomEndpointEditor(ui, vm, content)
}

@Composable
private fun OAuthProviderRow(
    ui: ConnectUiState,
    vm: ConnectViewModel,
    oauth: ProviderOAuth,
    openBrowser: (String) -> Unit,
) {
    val loggedIn = oauth.status?.loggedIn == true
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(oauth.name.ifBlank { oauth.id }, style = MaterialTheme.typography.titleMedium)
            Text(
                if (loggedIn) "Connected${oauth.status?.sourceLabel?.let { " · $it" }.orEmpty()}"
                else "Not connected · ${oauth.flow.ifBlank { "API key fallback" }}",
                color = if (loggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!loggedIn) {
                if (oauth.flow.equals("external", ignoreCase = true)) {
                    Text(
                        "This provider is managed by its CLI. Use the credential pool below to enter an API key instead.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Button(
                        onClick = { vm.startProviderOAuth(oauth.id, openBrowser) },
                        enabled = ui.providerBusy == null,
                    ) { Text("Start OAuth") }
                }
            }
        }
    }
}

@Composable
private fun OAuthSessionCard(
    ui: ConnectUiState,
    vm: ConnectViewModel,
    session: ProviderOAuthSession,
    openBrowser: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("OAuth authorization · ${session.providerId}", style = MaterialTheme.typography.titleMedium)
            session.userCode?.let { Text("User code: $it", style = MaterialTheme.typography.bodyMedium) }
            session.status?.let { Text("Status: $it", style = MaterialTheme.typography.bodySmall) }
            session.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val url = session.verificationUriComplete
                ?: session.authUrl
                ?: session.verificationUri
            if (url != null) {
                Text("Authorization URL: $url", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { openBrowser(url) }) { Text("Open authorization URL") }
            }
            if (session.sessionId != null) {
                OutlinedTextField(
                    value = ui.providerDraft.oauthCode,
                    onValueChange = { value -> vm.updateProviderDraft { it.copy(oauthCode = value) } },
                    label = { Text("Paste authorization code (if requested)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = vm::submitProviderOAuth,
                        enabled = ui.providerBusy == null,
                    ) { Text("Submit code") }
                    OutlinedButton(
                        onClick = vm::pollProviderOAuth,
                        enabled = ui.providerBusy == null,
                    ) { Text("Check status") }
                }
            }
            TextButton(onClick = vm::clearProviderOAuthSession) { Text("Close OAuth flow") }
        }
    }
}

@Composable
private fun CredentialPoolEditor(
    ui: ConnectUiState,
    vm: ConnectViewModel,
    content: ProviderOnboardingContent,
) {
    Text("Credential pool", style = MaterialTheme.typography.titleLarge)
    Text(
        "Keys stay on Hermes. Talaria only displays redacted previews returned by the server.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (content.credentialPools.isEmpty()) {
        Text("No credential pool entries returned.", style = MaterialTheme.typography.bodySmall)
    } else {
        content.credentialPools.forEach { group ->
            Text(group.provider, style = MaterialTheme.typography.titleMedium)
            group.entries.forEach { entry ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.label.ifBlank { "Credential ${entry.index}" })
                        Text(
                            "${entry.tokenPreview ?: "redacted"} · ${entry.lastStatus ?: "not tested"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { vm.editCredential(group.provider, entry) }) { Text("Edit") }
                    TextButton(onClick = { vm.requestRemoveCredential(group.provider, entry) }) { Text("Remove") }
                }
            }
        }
    }
    OutlinedTextField(
        value = ui.providerDraft.credentialProvider,
        onValueChange = { value -> vm.updateProviderDraft { it.copy(credentialProvider = value, selectedProvider = value) } },
        label = { Text("Provider slug") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = ui.providerDraft.credentialEnvKey,
        onValueChange = { value -> vm.updateProviderDraft { it.copy(credentialEnvKey = value) } },
        label = { Text("Environment key (optional)") },
        supportingText = { Text("Used by provider validation; blank derives PROVIDER_API_KEY") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = ui.providerDraft.credentialLabel,
        onValueChange = { value -> vm.updateProviderDraft { it.copy(credentialLabel = value) } },
        label = { Text("Credential label (optional)") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = ui.providerDraft.credentialApiKey,
        onValueChange = { value -> vm.updateProviderDraft { it.copy(credentialApiKey = value) } },
        label = { Text("API key") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = vm::validateProviderCredential,
            enabled = ui.providerBusy == null,
        ) { Text("Validate key") }
        Button(
            onClick = vm::requestSaveCredential,
            enabled = ui.providerBusy == null,
        ) { Text(if (ui.providerDraft.editingCredential == null) "Add credential" else "Replace credential") }
        if (ui.providerDraft.editingCredential != null) {
            TextButton(onClick = vm::clearCredentialDraft) { Text("Cancel edit") }
        }
    }
    ui.providerValidation?.let { result -> ValidationLine(result) }
}

@Composable
private fun CustomEndpointEditor(
    ui: ConnectUiState,
    vm: ConnectViewModel,
    content: ProviderOnboardingContent,
) {
    Text("Custom endpoints", style = MaterialTheme.typography.titleLarge)
    Text(
        "Configure an OpenAI-compatible base URL and model. Validate it before saving.",
        style = MaterialTheme.typography.bodySmall,
    )
    content.customEndpoints.forEach { endpoint ->
        CustomEndpointRow(vm, content, endpoint)
    }
    OutlinedTextField(
        value = ui.providerDraft.endpointName,
        onValueChange = { value -> vm.updateProviderDraft { it.copy(endpointName = value) } },
        label = { Text("Endpoint name") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = ui.providerDraft.endpointBaseUrl,
        onValueChange = { value -> vm.updateProviderDraft { it.copy(endpointBaseUrl = value) } },
        label = { Text("Base URL") },
        supportingText = { Text("Example: https://api.example.com/v1") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = ui.providerDraft.endpointModel,
        onValueChange = { value -> vm.updateProviderDraft { it.copy(endpointModel = value) } },
        label = { Text("Model") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = ui.providerDraft.endpointApiKey,
        onValueChange = { value -> vm.updateProviderDraft { it.copy(endpointApiKey = value) } },
        label = { Text("Endpoint API key (optional)") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = ui.providerDraft.endpointContextLength,
        onValueChange = { value -> vm.updateProviderDraft { it.copy(endpointContextLength = value) } },
        label = { Text("Context length (optional)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(
            checked = ui.providerDraft.endpointDiscoverModels,
            onCheckedChange = { value -> vm.updateProviderDraft { it.copy(endpointDiscoverModels = value) } },
        )
        Text("Discover models", modifier = Modifier.padding(top = 12.dp))
        Checkbox(
            checked = ui.providerDraft.endpointMakeDefault,
            onCheckedChange = { value -> vm.updateProviderDraft { it.copy(endpointMakeDefault = value) } },
        )
        Text("Make default", modifier = Modifier.padding(top = 12.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = vm::validateCustomEndpoint,
            enabled = ui.providerBusy == null,
        ) { Text("Validate endpoint") }
        Button(
            onClick = vm::saveCustomEndpoint,
            enabled = ui.providerBusy == null,
        ) { Text(if (ui.providerDraft.endpointId.isBlank()) "Save endpoint" else "Update endpoint") }
        if (ui.providerDraft.endpointId.isNotBlank()) {
            TextButton(onClick = { vm.updateProviderDraft { ProviderDraft() } }) { Text("Clear") }
        }
    }
    ui.customEndpointValidation?.let { result -> ValidationLine(result) }
}

@Composable
private fun CustomEndpointRow(
    vm: ConnectViewModel,
    content: ProviderOnboardingContent,
    endpoint: CustomEndpoint,
) {
    val active = content.currentEndpointBaseUrl == endpoint.baseUrl &&
        content.currentEndpointModel == endpoint.model
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(endpoint.name.ifBlank { endpoint.id }, style = MaterialTheme.typography.titleMedium)
            Text("${endpoint.baseUrl} · ${endpoint.model}", style = MaterialTheme.typography.bodySmall)
            if (active) Text("Active", color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.editCustomEndpoint(endpoint) }) { Text("Edit") }
                if (!active && endpoint.id.isNotBlank()) {
                    TextButton(onClick = { vm.activateCustomEndpoint(endpoint.id) }) { Text("Activate") }
                }
                if (endpoint.id.isNotBlank()) {
                    TextButton(onClick = { vm.requestRemoveCustomEndpoint(endpoint) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun ValidationLine(result: ProviderValidationUi) {
    val color = when {
        result.ok -> MaterialTheme.colorScheme.primary
        result.reachable -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    Text(result.message, color = color, style = MaterialTheme.typography.bodySmall)
}

private fun providerLabel(provider: com.hermesgadget.talaria.domain.model.ProviderSummary): String =
    provider.name.ifBlank { provider.displayName ?: providerKey(provider) }
