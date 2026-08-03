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
package com.hermesgadget.talaria.feature.manage.webhooks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.domain.model.WebhookRoute
import com.hermesgadget.talaria.ui.components.ErrorBox
import com.hermesgadget.talaria.ui.components.LoadingBox
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Copies [text] to the system clipboard with a toast confirmation. */
private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

private const val SENSITIVE_CLIPBOARD_CLEAR_DELAY_MS = 15_000L

/** Copies a secret with clipboard sensitivity metadata and a best-effort expiry. */
private fun copySensitiveToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    Handler(Looper.getMainLooper()).postDelayed({
        val current = clipboard.primaryClip
        val currentText = current?.getItemAt(0)?.text?.toString()
        if (current?.description?.label?.toString() == label && currentText == text) {
            clipboard.clearPrimaryClip()
        }
    }, SENSITIVE_CLIPBOARD_CLEAR_DELAY_MS)
}

@Composable
fun WebhooksScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    val context = LocalContext.current
    var list by remember { mutableStateOf<List<WebhookRoute>?>(null) }
    var platformEnabled by remember { mutableStateOf<Boolean?>(null) }
    var baseUrl by remember { mutableStateOf<String?>(null) }
    var confirmEnable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var successMsg by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var createdRoute by remember { mutableStateOf<WebhookRoute?>(null) }
    var secretRevealed by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(createdRoute?.secret, secretRevealed) {
        if (secretRevealed) {
            delay(SENSITIVE_CLIPBOARD_CLEAR_DELAY_MS)
            secretRevealed = false
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            createdRoute = null
            secretRevealed = false
        }
    }

    fun reload() = scope.launch {
        repo.getWebhooks()
            .onSuccess {
                list = it.subscriptions
                platformEnabled = it.enabled
                baseUrl = it.base_url
                error = null
            }
            .onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { reload() }

    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text("Enable webhook platform?") },
            text = {
                Text("This starts the webhook listener on the Hermes host and may restart the gateway.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnable = false
                    scope.launch {
                        repo.enableWebhooks()
                            .onSuccess {
                                successMsg = "Enabled — the host may restart the gateway"
                                reload()
                            }
                            .onFailure { error = it.message }
                    }
                }) { Text("Enable") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnable = false }) { Text("Cancel") }
            },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete webhook?") },
            text = { Text("Permanently delete '$target'?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        repo.deleteWebhook(target)
                            .onSuccess { reload() }
                            .onFailure { error = it.message }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    ScreenScaffold("Webhooks", "Dynamic subscriptions", actions = {
        TextButton(onClick = { reload() }) { Text("Refresh") }
    }) {
        when {
            list == null && error == null -> LoadingBox()
            error != null && list == null -> ErrorBox(error!!, onRetry = { reload() })
            else -> {
                // Platform-level enable, mirroring the web Webhooks page.
                if (platformEnabled == false) {
                    Surface(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Webhook platform is disabled on the host.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            baseUrl?.let {
                                Text(
                                    "Base URL: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                            OutlinedButton(onClick = { confirmEnable = true }) { Text("Enable platform") }
                        }
                    }
                }
                successMsg?.let {
                    Text(it, color = MaterialTheme.colorScheme.secondary)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    scope.launch {
                        repo.createWebhook(name.trim(), prompt.trim())
                            .onSuccess { created ->
                                name = ""
                                prompt = ""
                                createdRoute = created
                                secretRevealed = false
                                reload()
                            }
                            .onFailure { error = it.message }
                    }
                }) { Text("Create") }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                // One-time secret / final url echoed by the dashboard on create.
                createdRoute?.let { created ->
                    Surface(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Webhook created", style = MaterialTheme.typography.titleMedium)
                            created.url?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { copyToClipboard(context, "Webhook URL", it) }) {
                                    Text("Copy URL")
                                }
                            }
                            created.secret?.let {
                                Text(
                                    "Secret: " + if (secretRevealed) {
                                        it
                                    } else {
                                        "•".repeat(it.length.coerceAtMost(32))
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Row {
                                    TextButton(onClick = { secretRevealed = !secretRevealed }) {
                                        Text(if (secretRevealed) "Hide secret" else "Reveal for 15 seconds")
                                    }
                                    TextButton(
                                        onClick = {
                                            copySensitiveToClipboard(context, "Webhook secret", it)
                                        },
                                    ) {
                                        Text("Copy secret")
                                    }
                                }
                            }
                            if (created.url == null && created.secret == null) {
                                Text(
                                    "The dashboard did not echo a url/secret — check the list below.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = {
                                createdRoute = null
                                secretRevealed = false
                            }) { Text("Dismiss") }
                        }
                    }
                }
                LazyColumn {
                    items(list.orEmpty(), key = { it.name }) { w ->
                        Surface(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(w.name, style = MaterialTheme.typography.titleLarge)
                                        Text(w.description ?: "")
                                        Text(w.url ?: "", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "events=${w.events.joinToString()}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Switch(
                                        checked = w.enabled == true,
                                        onCheckedChange = { enabled ->
                                            scope.launch {
                                                repo.setWebhookEnabled(w.name, enabled)
                                                    .onSuccess { reload() }
                                                    .onFailure { error = it.message }
                                            }
                                        },
                                    )
                                }
                                Row {
                                    w.url?.takeIf { it.isNotBlank() }?.let {
                                        TextButton(onClick = { copyToClipboard(context, "Webhook URL", it) }) {
                                            Text("Copy URL")
                                        }
                                    }
                                    TextButton(onClick = { deleteTarget = w.name }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
