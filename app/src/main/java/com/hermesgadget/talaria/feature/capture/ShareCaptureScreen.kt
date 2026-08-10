/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermesgadget.talaria.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareCaptureScreen(
    viewModel: ShareCaptureViewModel,
    onFinished: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.completed) {
        if (ui.completed) onFinished()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.capture_title)) },
                navigationIcon = {
                    IconButton(onClick = onFinished) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.capture_close),
                        )
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.capture_share_into),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                ui.profileName?.let { profile ->
                    Text(
                        text = stringResource(R.string.capture_profile, profile),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(ui.targets, key = ShareTargetOption::key) { target ->
                FilterChip(
                    selected = target.key == ui.selectedTargetKey,
                    onClick = { viewModel.selectTarget(target) },
                    enabled = target.enabled && ui.deliveryState != ShareDeliveryUiState.SENDING,
                    label = { Text(target.label) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (ui.loadingTargets) {
                item {
                    CircularProgressIndicator()
                }
            }
            if (ui.text.isNotBlank()) {
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.capture_shared_text),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(ui.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (ui.suggestions.isNotEmpty()) {
                item {
                    Column {
                        Text(
                            text = stringResource(R.string.capture_url_suggestions),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ui.suggestions.forEach { suggestion ->
                                AssistChip(
                                    onClick = { viewModel.useSuggestion(suggestion) },
                                    label = {
                                        Text(
                                            when (suggestion) {
                                                "summarize" -> stringResource(R.string.capture_summarize)
                                                "compare" -> stringResource(R.string.capture_compare)
                                                else -> stringResource(R.string.capture_extract)
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (ui.items.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.capture_attachments),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(ui.items, key = ShareCaptureItemUi::id) { item ->
                    Card {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.displayName, fontWeight = FontWeight.Medium)
                                Text(
                                    text = "${item.mimeType} · ${formatBytes(item.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (item.kind != ShareItemKind.IMAGE || !item.inlineImageSupported) {
                                    Text(
                                        text = stringResource(R.string.capture_managed_file_notice),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            IconButton(
                                onClick = { viewModel.removeItem(item.id) },
                                enabled = ui.deliveryState != ShareDeliveryUiState.SENDING,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.capture_remove),
                                )
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = ui.instruction,
                    onValueChange = viewModel::updateInstruction,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.capture_instruction_label)) },
                    placeholder = { Text(stringResource(R.string.capture_instruction_hint)) },
                    minLines = 2,
                    enabled = ui.deliveryState != ShareDeliveryUiState.SENDING,
                )
            }
            ui.error?.let { error ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.discard() },
                        modifier = Modifier.weight(1f),
                        enabled = ui.deliveryState != ShareDeliveryUiState.SENDING,
                    ) {
                        Text(stringResource(R.string.capture_discard))
                    }
                    Button(
                        onClick = viewModel::send,
                        modifier = Modifier.weight(1f),
                        enabled = !ui.importing &&
                            ui.deliveryState == ShareDeliveryUiState.IDLE &&
                            (ui.text.isNotBlank() || ui.items.isNotEmpty()),
                    ) {
                        Text(
                            if (ui.deliveryState == ShareDeliveryUiState.SENDING) {
                                stringResource(R.string.capture_sending)
                            } else {
                                stringResource(R.string.capture_send)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
