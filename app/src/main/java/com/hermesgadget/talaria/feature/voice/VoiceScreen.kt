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

package com.hermesgadget.talaria.feature.voice

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.domain.model.VoiceCapability
import com.hermesgadget.talaria.domain.model.VoiceHistoryItem
import com.hermesgadget.talaria.ui.components.CollapsibleSection
import com.hermesgadget.talaria.ui.components.ScreenScaffold

private enum class PermissionAction {
    SERVER_RECORDING,
    ON_DEVICE_DICTATION,
}

@Composable
fun VoiceScreen(
    vm: VoiceViewModel = viewModel(factory = VoiceViewModel.factory()),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var pendingPermission by remember { mutableStateOf<PermissionAction?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        vm.updateMicrophonePermission(granted)
        val action = pendingPermission
        pendingPermission = null
        if (granted) {
            when (action) {
                PermissionAction.SERVER_RECORDING -> vm.startRecording()
                PermissionAction.ON_DEVICE_DICTATION -> vm.startOnDeviceDictation()
                null -> Unit
            }
        }
    }

    fun requestServerRecording() {
        if (ui.microphonePermissionGranted) {
            vm.startRecording()
        } else {
            pendingPermission = PermissionAction.SERVER_RECORDING
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun requestOnDeviceDictation() {
        if (ui.microphonePermissionGranted) {
            vm.startOnDeviceDictation()
        } else {
            pendingPermission = PermissionAction.ON_DEVICE_DICTATION
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        vm.refreshCapabilities()
        vm.refreshElevenLabsVoices()
    }

    val subtitle = when {
        ui.checkingCapabilities -> "checking…"
        ui.phase == VoicePhase.UNAVAILABLE || !ui.capabilities.isComplete -> "unavailable"
        ui.phase == VoicePhase.RECORDING -> "recording"
        ui.phase == VoicePhase.TRANSCRIBING -> "transcribing"
        ui.phase == VoicePhase.PLAYING -> "playing"
        else -> "Hermes-hosted STT/TTS"
    }

    ScreenScaffold(
        title = "Server voice",
        subtitle = subtitle,
        showProfileSwitcher = true,
        actions = {
            TextButton(
                onClick = {
                    vm.refreshCapabilities()
                    vm.refreshElevenLabsVoices()
                },
                enabled = !ui.checkingCapabilities && ui.phase !in setOf(
                    VoicePhase.RECORDING,
                    VoicePhase.TRANSCRIBING,
                    VoicePhase.PLAYING,
                ),
            ) {
                Text("Check again")
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!ui.capabilityChecked) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (ui.phase == VoicePhase.UNAVAILABLE || !ui.capabilities.isComplete) {
                UnavailableVoiceContent(
                    ui = ui,
                    onUseOnDevice = if (ui.fallbackListening) vm::stopOnDeviceDictation
                    else ::requestOnDeviceDictation,
                )
            } else {
                if (ui.checkingCapabilities) LinearProgressIndicator(Modifier.fillMaxWidth())
                ReadyVoiceContent(
                    ui = ui,
                    onRecord = if (ui.phase == VoicePhase.RECORDING) {
                        vm::stopRecordingAndTranscribe
                    } else {
                        ::requestServerRecording
                    },
                    onSpeak = vm::speakText,
                    onStopPlayback = vm::stopPlayback,
                    onTextChanged = vm::updateText,
                    onCopy = { clipboard.setText(AnnotatedString(ui.text)) },
                    onHistorySelected = vm::selectHistory,
                )
            }

            CollapsibleSection(
                title = stringResource(R.string.minor_voice_elevenlabs),
                collapsible = true,
            ) {
                ElevenLabsVoicesContent(ui = ui, onRefresh = vm::refreshElevenLabsVoices)
            }

            ui.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ElevenLabsVoicesContent(
    ui: VoiceUiState,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.minor_voice_elevenlabs_description),
            style = MaterialTheme.typography.bodySmall,
        )
        ui.elevenLabsError?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        when {
            ui.elevenLabsLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            ui.elevenLabsAvailable == false -> Text(
                stringResource(R.string.minor_voice_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.elevenLabsVoices.isEmpty() -> Text(
                stringResource(R.string.minor_voice_no_voices),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> ui.elevenLabsVoices.forEach { voice ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(voice.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.minor_voice_id, voice.voiceId),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        TextButton(onClick = onRefresh, enabled = !ui.elevenLabsLoading) {
            Text(stringResource(R.string.minor_voice_refresh_voices))
        }
    }
}

@Composable
private fun UnavailableVoiceContent(
    ui: VoiceUiState,
    onUseOnDevice: () -> Unit,
) {
    val missing = ui.capabilities.missing.ifEmpty { VoiceCapability.values().toList() }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Server voice unavailable", style = MaterialTheme.typography.titleMedium)
            Text(
                "This Hermes connection does not advertise the following voice capabilities:",
                style = MaterialTheme.typography.bodyMedium,
            )
            missing.forEach { capability ->
                Text(
                    "• ${capability.label}: ${capability.route}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                if (ui.androidSpeechAvailable) {
                    "Android SpeechRecognizer is available. Use on-device dictation as the local fallback; it does not send audio to Hermes."
                } else {
                    "Android SpeechRecognizer is unavailable on this device, so no local dictation fallback is available."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onUseOnDevice,
                enabled = ui.androidSpeechAvailable,
            ) {
                Icon(
                    if (ui.fallbackListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = null,
                )
                Text(if (ui.fallbackListening) "Stop dictation" else "Use on-device dictation")
            }
        }
    }
}

@Composable
private fun ReadyVoiceContent(
    ui: VoiceUiState,
    onRecord: () -> Unit,
    onSpeak: () -> Unit,
    onStopPlayback: () -> Unit,
    onTextChanged: (String) -> Unit,
    onCopy: () -> Unit,
    onHistorySelected: (VoiceHistoryItem) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Hermes voice capabilities", style = MaterialTheme.typography.titleMedium)
            Text("Server STT and server TTS are available.", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (ui.androidSpeechAvailable) {
                    "Android SpeechRecognizer is available; this surface uses a recorded audio upload so Hermes can provide the fallback STT."
                } else {
                    "Android SpeechRecognizer is unavailable; recorded audio is uploaded to Hermes for server STT."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Button(
        onClick = onRecord,
        enabled = ui.phase == VoicePhase.IDLE || ui.phase == VoicePhase.RECORDING,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            if (ui.phase == VoicePhase.RECORDING) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = null,
        )
        Text(if (ui.phase == VoicePhase.RECORDING) "Stop and transcribe" else "Record for server STT")
    }

    if (ui.phase == VoicePhase.TRANSCRIBING) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Uploading audio and waiting for Hermes transcription…")
    }

    OutlinedTextField(
        value = ui.text,
        onValueChange = onTextChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Transcript or text to speak") },
        minLines = 4,
        enabled = ui.phase == VoicePhase.IDLE,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onCopy,
            enabled = ui.text.isNotBlank(),
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
            Text("Copy")
        }
        Button(
            onClick = onSpeak,
            enabled = ui.text.isNotBlank() && ui.phase == VoicePhase.IDLE,
        ) {
            Icon(Icons.Filled.VolumeUp, contentDescription = null)
            Text("Speak with Hermes")
        }
        if (ui.phase == VoicePhase.PLAYING) {
            TextButton(onClick = onStopPlayback) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Text("Stop")
            }
        }
    }
    if (ui.phase == VoicePhase.PLAYING) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Playing server audio…", style = MaterialTheme.typography.bodySmall)
    }
    if (ui.partialText.isNotBlank()) {
        Text(
            "Listening: ${ui.partialText}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (ui.history.isNotEmpty()) {
        Text("Recent voice history", style = MaterialTheme.typography.titleSmall)
        ui.history.forEach { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onHistorySelected(item) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(item.kind.label, style = MaterialTheme.typography.labelMedium)
                    Text(item.text, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
