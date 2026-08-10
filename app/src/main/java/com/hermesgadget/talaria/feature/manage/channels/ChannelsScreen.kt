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
package com.hermesgadget.talaria.feature.manage.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.domain.model.MessagingPlatform
import com.hermesgadget.talaria.ui.components.CollapsibleSection
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.openSafeExternalWebUri
import com.hermesgadget.talaria.ui.components.safeExternalWebUri
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Composable
fun ChannelsScreen(
    vm: ChannelsViewModel = viewModel(factory = ChannelsViewModel.factory()),
) {
    val repo = TalariaApp.instance.container.hermesRepository
    val onboarding by vm.onboarding.collectAsStateWithLifecycle()
    var list by remember { mutableStateOf<List<MessagingPlatform>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val envDrafts = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()

    fun reload() = scope.launch {
        repo.getChannels()
            .onSuccess {
                list = it
                error = null
            }
            .onFailure { error = it.message }
    }

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(onboarding.telegram.phase, onboarding.whatsapp.phase) {
        if (onboarding.telegram.phase == MessagingOnboardingPhase.Applied ||
            onboarding.whatsapp.phase == MessagingOnboardingPhase.Applied
        ) {
            reload()
        }
    }

    ScreenScaffold("Channels", "Messaging platforms", actions = {
        TextButton(onClick = { reload() }) { Text("Refresh") }
    }) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                CollapsibleSection(
                    title = stringResource(R.string.messaging_onboarding_title),
                    collapsible = true,
                ) {
                    Text(
                        stringResource(R.string.messaging_onboarding_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TelegramOnboardingCard(
                        state = onboarding.telegram,
                        onStart = vm::startTelegram,
                        onApply = vm::applyTelegram,
                        onCancel = vm::cancelTelegram,
                    )
                    WhatsAppOnboardingCard(
                        state = onboarding.whatsapp,
                        onStart = vm::startWhatsApp,
                        onApply = vm::applyWhatsApp,
                        onCancel = vm::cancelWhatsApp,
                    )
                }
            }

            item {
                when {
                    list == null && error == null -> {
                        CollapsibleSection(
                            title = stringResource(R.string.messaging_channels_title),
                            collapsible = true,
                        ) { LoadingBox() }
                    }
                    error != null && list == null -> {
                        CollapsibleSection(
                            title = stringResource(R.string.messaging_channels_title),
                            collapsible = true,
                        ) { ErrorBox(error!!, onRetry = { reload() }) }
                    }
                    else -> {
                        CollapsibleSection(
                            title = stringResource(R.string.messaging_channels_title),
                            collapsible = true,
                        ) {
                            testResult?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                list.orEmpty().forEach { p ->
                                    Surface(
                                        Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        shape = MaterialTheme.shapes.medium,
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        p.name,
                                                        style = MaterialTheme.typography.titleLarge,
                                                    )
                                                    Text(p.description ?: "")
                                                    Text(
                                                        "state=${p.state} configured=${p.configured}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                    p.error_message?.let {
                                                        Text(it, color = MaterialTheme.colorScheme.error)
                                                    }
                                                }
                                                Switch(
                                                    checked = p.enabled == true,
                                                    onCheckedChange = { enabled ->
                                                        scope.launch {
                                                            repo.updateChannel(p.id, enabled, null)
                                                                .onSuccess { reload() }
                                                                .onFailure { error = it.message }
                                                        }
                                                    },
                                                )
                                            }
                                            val fields = p.env_vars.ifEmpty {
                                                p.env_keys.map {
                                                    com.hermesgadget.talaria.domain.model.MessagingEnvVarInfo(
                                                        key = it,
                                                    )
                                                }
                                            }
                                            fields.forEach { field ->
                                                val draftKey = "${p.id}:${field.key}"
                                                OutlinedTextField(
                                                    value = envDrafts[draftKey].orEmpty(),
                                                    onValueChange = { envDrafts[draftKey] = it },
                                                    label = {
                                                        Text(
                                                            (field.prompt ?: field.key) +
                                                                if (field.required) " *" else "",
                                                        )
                                                    },
                                                    supportingText = {
                                                        Text(
                                                            field.redacted_value?.let { "Currently $it" }
                                                                ?: field.description.orEmpty(),
                                                        )
                                                    },
                                                    visualTransformation = if (field.is_password) {
                                                        PasswordVisualTransformation()
                                                    } else {
                                                        androidx.compose.ui.text.input.VisualTransformation.None
                                                    },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    singleLine = true,
                                                )
                                                if (field.is_set) {
                                                    TextButton(onClick = {
                                                        scope.launch {
                                                            repo.updateChannel(
                                                                p.id,
                                                                null,
                                                                null,
                                                                clearEnv = listOf(field.key),
                                                            ).onSuccess {
                                                                envDrafts.remove(draftKey)
                                                                testResult = "Cleared ${field.key}"
                                                                reload()
                                                            }.onFailure { error = it.message }
                                                        }
                                                    }) { Text("Clear ${field.key}") }
                                                }
                                            }
                                            Row {
                                                TextButton(
                                                    onClick = {
                                                        scope.launch {
                                                            val values = fields.mapNotNull { field ->
                                                                val value = envDrafts[
                                                                    "${p.id}:${field.key}"
                                                                ]?.trim().orEmpty()
                                                                value.takeIf { it.isNotEmpty() }
                                                                    ?.let { field.key to it }
                                                            }
                                                            val env = buildJsonObject {
                                                                values.forEach { (key, value) -> put(key, value) }
                                                            }
                                                            repo.updateChannel(p.id, null, env)
                                                                .onSuccess {
                                                                    values.forEach { (key, _) ->
                                                                        envDrafts.remove("${p.id}:$key")
                                                                    }
                                                                    testResult = "Updated ${p.id}"
                                                                    reload()
                                                                }
                                                                .onFailure { error = it.message }
                                                        }
                                                    },
                                                    enabled = fields.any {
                                                        envDrafts["${p.id}:${it.key}"].orEmpty()
                                                            .isNotBlank()
                                                    },
                                                ) { Text("Save credentials") }
                                                TextButton(onClick = {
                                                    scope.launch {
                                                        repo.testChannel(p.id)
                                                            .onSuccess { result ->
                                                                val obj = result.jsonObject
                                                                val message = obj["message"]
                                                                    ?.jsonPrimitive?.contentOrNull
                                                                    ?: obj["state"]?.jsonPrimitive?.contentOrNull
                                                                    ?: if (obj["ok"]?.jsonPrimitive?.content == "true") {
                                                                        "OK"
                                                                    } else {
                                                                        result.toString()
                                                                    }
                                                                testResult = "${p.name}: $message"
                                                            }
                                                            .onFailure {
                                                                testResult = "${p.name}: ${it.message}"
                                                            }
                                                    }
                                                }) { Text("Test") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelegramOnboardingCard(
    state: TelegramOnboardingState,
    onStart: (String) -> Unit,
    onApply: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var botName by rememberSaveable { mutableStateOf("") }
    var allowedUsers by rememberSaveable { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    val hasSession = state.pairingId != null
    val busy = state.phase in setOf(
        MessagingOnboardingPhase.Starting,
        MessagingOnboardingPhase.Applying,
        MessagingOnboardingPhase.Cancelling,
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.messaging_pair_telegram),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.messaging_telegram_instructions),
                style = MaterialTheme.typography.bodySmall,
            )

            if (!hasSession) {
                if (state.phase == MessagingOnboardingPhase.Applied) {
                    Text(
                        stringResource(R.string.messaging_telegram_applied),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedTextField(
                    value = botName,
                    onValueChange = { botName = it },
                    label = { Text(stringResource(R.string.messaging_telegram_bot_name)) },
                    supportingText = {
                        Text(stringResource(R.string.messaging_telegram_bot_name_hint))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(
                    onClick = { onStart(botName) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.phase == MessagingOnboardingPhase.Starting) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.messaging_start_pairing))
                    }
                }
            } else {
                OnboardingStatus(
                    phase = state.phase,
                    status = state.status,
                    waitingText = stringResource(R.string.messaging_telegram_waiting),
                )
                state.pairingId?.let {
                    Text(stringResource(R.string.messaging_pairing_id, it))
                }
                state.expiresAt?.let {
                    Text(
                        stringResource(R.string.messaging_expires_at, it),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.deepLink?.takeIf { safeExternalWebUri(it) != null }?.let {
                    PayloadBlock(
                        label = stringResource(R.string.messaging_deep_link),
                        value = it,
                        onOpen = {
                            openSafeExternalWebUri(it) { uri -> uriHandler.openUri(uri.toString()) }
                        },
                    )
                }
                state.qrPayload?.let {
                    PayloadBlock(
                        label = stringResource(R.string.messaging_qr_payload),
                        value = it,
                        supportingText = stringResource(R.string.messaging_qr_payload_hint),
                    )
                }
                state.suggestedUsername?.let {
                    Text(stringResource(R.string.messaging_suggested_username, it))
                }
                state.botUsername?.let {
                    Text(stringResource(R.string.messaging_bot_username, it))
                }
                state.ownerUserId?.let {
                    Text(stringResource(R.string.messaging_owner_user_id, it))
                }
                if (state.readyForApply) {
                    OutlinedTextField(
                        value = allowedUsers,
                        onValueChange = { allowedUsers = it },
                        label = {
                            Text(stringResource(R.string.messaging_telegram_allowed_users))
                        },
                        supportingText = {
                            Text(stringResource(R.string.messaging_telegram_allowed_users_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Button(
                        onClick = { onApply(allowedUsers) },
                        enabled = !busy && allowedUsers.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.phase == MessagingOnboardingPhase.Applying) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.messaging_apply_enable))
                        }
                    }
                }
                OnboardingError(
                    visible = state.phase == MessagingOnboardingPhase.Error ||
                        !state.error.isNullOrBlank() || state.failureStatus != null,
                    error = state.error,
                    failureStatus = state.failureStatus,
                )
                TextButton(
                    onClick = onCancel,
                    enabled = state.phase != MessagingOnboardingPhase.Cancelling,
                ) {
                    Text(
                        if (state.phase == MessagingOnboardingPhase.Cancelling) {
                            stringResource(R.string.messaging_cancelling)
                        } else {
                            stringResource(R.string.messaging_cancel)
                        },
                    )
                }
            }
            if (!hasSession) {
                OnboardingError(
                    visible = state.phase == MessagingOnboardingPhase.Error ||
                        !state.error.isNullOrBlank() || state.failureStatus != null,
                    error = state.error,
                    failureStatus = state.failureStatus,
                )
            }
        }
    }
}

@Composable
private fun WhatsAppOnboardingCard(
    state: WhatsAppOnboardingState,
    onStart: (String, String) -> Unit,
    onApply: (String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(state.mode) }
    var allowedUsers by rememberSaveable { mutableStateOf(state.allowedUsers) }
    val hasSession = state.pairingId != null
    val busy = state.phase in setOf(
        MessagingOnboardingPhase.Starting,
        MessagingOnboardingPhase.Applying,
        MessagingOnboardingPhase.Cancelling,
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.messaging_pair_whatsapp),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.messaging_whatsapp_instructions),
                style = MaterialTheme.typography.bodySmall,
            )

            if (!hasSession) {
                if (state.phase == MessagingOnboardingPhase.Applied) {
                    Text(
                        stringResource(R.string.messaging_whatsapp_applied),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    stringResource(R.string.messaging_whatsapp_mode),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = mode == "bot",
                        onClick = { mode = "bot" },
                    )
                    Text(stringResource(R.string.messaging_whatsapp_mode_bot))
                    RadioButton(
                        selected = mode == "self-chat",
                        onClick = { mode = "self-chat" },
                    )
                    Text(stringResource(R.string.messaging_whatsapp_mode_self_chat))
                }
                OutlinedTextField(
                    value = allowedUsers,
                    onValueChange = { allowedUsers = it },
                    label = {
                        Text(stringResource(R.string.messaging_whatsapp_allowed_users))
                    },
                    supportingText = {
                        Text(stringResource(R.string.messaging_whatsapp_allowed_users_hint))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(
                    onClick = { onStart(mode, allowedUsers) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.phase == MessagingOnboardingPhase.Starting) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.messaging_start_pairing))
                    }
                }
            } else {
                OnboardingStatus(
                    phase = state.phase,
                    status = state.status,
                    waitingText = stringResource(R.string.messaging_whatsapp_waiting),
                )
                state.pairingId?.let {
                    Text(stringResource(R.string.messaging_pairing_id, it))
                }
                state.expiresAt?.let {
                    Text(
                        stringResource(R.string.messaging_expires_at, it),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.qrPayload?.let {
                    PayloadBlock(
                        label = stringResource(R.string.messaging_qr_payload),
                        value = it,
                        supportingText = stringResource(R.string.messaging_qr_payload_hint),
                    )
                }
                state.accountName?.let {
                    Text(stringResource(R.string.messaging_whatsapp_account_name, it))
                }
                state.accountPhone?.let {
                    Text(stringResource(R.string.messaging_whatsapp_account_phone, it))
                }
                if (state.readyForApply) {
                    Text(
                        stringResource(R.string.messaging_whatsapp_mode),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = mode == "bot",
                            onClick = { mode = "bot" },
                        )
                        Text(stringResource(R.string.messaging_whatsapp_mode_bot))
                        RadioButton(
                            selected = mode == "self-chat",
                            onClick = { mode = "self-chat" },
                        )
                        Text(stringResource(R.string.messaging_whatsapp_mode_self_chat))
                    }
                    OutlinedTextField(
                        value = allowedUsers,
                        onValueChange = { allowedUsers = it },
                        label = {
                            Text(stringResource(R.string.messaging_whatsapp_allowed_users))
                        },
                        supportingText = {
                            Text(stringResource(R.string.messaging_whatsapp_allowed_users_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Button(
                        onClick = { onApply(mode, allowedUsers) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.phase == MessagingOnboardingPhase.Applying) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.messaging_apply_enable))
                        }
                    }
                }
                OnboardingError(
                    visible = state.phase == MessagingOnboardingPhase.Error ||
                        !state.error.isNullOrBlank() || state.failureStatus != null,
                    error = state.error,
                    failureStatus = state.failureStatus,
                )
                TextButton(
                    onClick = onCancel,
                    enabled = state.phase != MessagingOnboardingPhase.Cancelling,
                ) {
                    Text(
                        if (state.phase == MessagingOnboardingPhase.Cancelling) {
                            stringResource(R.string.messaging_cancelling)
                        } else {
                            stringResource(R.string.messaging_cancel)
                        },
                    )
                }
            }
            if (!hasSession) {
                OnboardingError(
                    visible = state.phase == MessagingOnboardingPhase.Error ||
                        !state.error.isNullOrBlank() || state.failureStatus != null,
                    error = state.error,
                    failureStatus = state.failureStatus,
                )
            }
        }
    }
}

@Composable
private fun OnboardingStatus(
    phase: MessagingOnboardingPhase,
    status: String?,
    waitingText: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (phase == MessagingOnboardingPhase.Starting ||
            phase == MessagingOnboardingPhase.Cancelling ||
            phase == MessagingOnboardingPhase.Applying
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
        }
        Text(
            when (phase) {
                MessagingOnboardingPhase.Starting ->
                    stringResource(R.string.messaging_starting)
                MessagingOnboardingPhase.Waiting -> waitingText
                MessagingOnboardingPhase.Ready -> stringResource(R.string.messaging_ready)
                MessagingOnboardingPhase.Applying -> stringResource(R.string.messaging_applying)
                MessagingOnboardingPhase.Applied -> stringResource(R.string.messaging_applied)
                MessagingOnboardingPhase.Cancelling -> stringResource(R.string.messaging_cancelling)
                MessagingOnboardingPhase.Error ->
                    stringResource(R.string.messaging_onboarding_failed)
                MessagingOnboardingPhase.Idle -> waitingText
            },
            color = if (phase == MessagingOnboardingPhase.Error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        status?.let {
            Text(
                stringResource(R.string.messaging_status, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PayloadBlock(
    label: String,
    value: String,
    supportingText: String? = null,
    onOpen: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        supportingText?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.small,
        ) {
            SelectionContainer {
                Text(
                    value,
                    modifier = Modifier.padding(8.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        onOpen?.let {
            TextButton(onClick = it) { Text(stringResource(R.string.messaging_open_link)) }
        }
    }
}

@Composable
private fun OnboardingError(
    visible: Boolean,
    error: String?,
    failureStatus: String?,
) {
    if (!visible) return
    Text(
        stringResource(R.string.messaging_onboarding_failed),
        color = MaterialTheme.colorScheme.error,
    )
    if (!error.isNullOrBlank()) {
        Text(error, color = MaterialTheme.colorScheme.error)
    } else if (!failureStatus.isNullOrBlank()) {
        Text(
            stringResource(R.string.messaging_unexpected_status, failureStatus),
            color = MaterialTheme.colorScheme.error,
        )
    } else {
        Text(
            stringResource(R.string.messaging_request_failed),
            color = MaterialTheme.colorScheme.error,
        )
    }
}
