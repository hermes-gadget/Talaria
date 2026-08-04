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

package com.hermesgadget.talaria.feature.manage.carhosts

import androidx.car.app.R as CarAppR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.car.AndroidxKnownCarHosts
import com.hermesgadget.talaria.car.CarHostTrustPolicy
import com.hermesgadget.talaria.core.data.prefs.CarHostIdentity
import com.hermesgadget.talaria.core.data.prefs.CarHostTrustRecord
import com.hermesgadget.talaria.core.data.prefs.displayFingerprint
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import java.text.DateFormat
import java.util.Date

private data class CarHostRow(
    val identity: CarHostIdentity,
    val knownByAndroidx: Boolean,
    val record: CarHostTrustRecord?,
)

/** Handset-only, explicit management for certificate-bound car-host trust. */
@Composable
fun CarHostsScreen() {
    val context = LocalContext.current
    val store = TalariaApp.instance.container.carHostTrustStore
    val revision by store.revision.collectAsState()
    val knownHosts = remember(context) { AndroidxKnownCarHosts.identities(context) }
    val records = remember(revision) { store.list() }
    val recordsByIdentity = records.associateBy { it.identity }
    val rows = (knownHosts + records.map { it.identity })
        .map { identity ->
            CarHostRow(
                identity = identity,
                knownByAndroidx = identity in knownHosts,
                record = recordsByIdentity[identity],
            )
        }
        .sortedWith(
            compareByDescending<CarHostRow> { it.knownByAndroidx }
                .thenBy { it.identity.packageName }
                .thenBy { it.identity.certificateSha256 },
        )

    var packageName by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var pendingEnrollment by remember { mutableStateOf<CarHostIdentity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val enteredIdentity = remember(packageName, fingerprint) {
        CarHostIdentity.create(packageName, fingerprint)
    }

    pendingEnrollment?.let { identity ->
        AlertDialog(
            onDismissRequest = { pendingEnrollment = null },
            title = { Text("Enroll this car host?") },
            text = {
                Text(
                    "This grants ${identity.packageName} access to recent conversations. " +
                        "Its create/send approval will last 15 minutes. Verify the SHA-256 certificate carefully.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.enroll(identity)
                        message = "Enrolled ${identity.packageName}; actions confirmed for 15 minutes. " +
                            "Reconnect the car host if it was rejected at startup."
                        pendingEnrollment = null
                        packageName = ""
                        fingerprint = ""
                    },
                ) { Text("Enroll") }
            },
            dismissButton = {
                TextButton(onClick = { pendingEnrollment = null }) { Text("Cancel") }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear car-host records?") },
            text = { Text("This revokes every manually enrolled host and clears local host action history.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.clear()
                        message = "Cleared car-host records."
                        confirmClear = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }

    ScreenScaffold("Car hosts", "Certificate-bound access") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Enrollment is always explicit", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Automatic enrollment is OFF. An enrolled host can read recent conversation text. " +
                                "Create and send actions also require a handset confirmation from the last 15 minutes.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            message?.let { text ->
                item {
                    Text(text, color = MaterialTheme.colorScheme.secondary)
                }
            }

            item {
                Text("Enroll by exact identity", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Usually an attempted OEM connection appears below as Observed. For other sideloaded hosts, " +
                        "enter both the package and signing-certificate SHA-256 shown by the host tooling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it; message = null },
                    label = { Text("Package name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it; message = null },
                    label = { Text("Signing certificate SHA-256") },
                    supportingText = { Text("64 hexadecimal digits; colons are accepted") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { pendingEnrollment = enteredIdentity },
                    enabled = enteredIdentity != null,
                ) { Text("Review enrollment") }
                if ((packageName.isNotBlank() || fingerprint.isNotBlank()) && enteredIdentity == null) {
                    Text(
                        "Enter a valid package name and a full SHA-256 fingerprint. Package-only trust is not allowed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            item {
                HorizontalDivider()
                Text(
                    "Known and observed hosts",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            items(rows, key = { "${it.identity.packageName}:${it.identity.certificateSha256}" }) { row ->
                CarHostCard(
                    row = row,
                    onEnroll = { pendingEnrollment = row.identity },
                    onRevoke = {
                        store.revoke(row.identity.packageName)
                        message = "Revoked ${row.identity.packageName}."
                    },
                    onConfirmActions = {
                        if (store.approveActions(row.identity)) {
                            message = "Actions confirmed for ${row.identity.packageName} for 15 minutes."
                        }
                    },
                )
            }

            if (rows.isEmpty()) {
                item {
                    Text(
                        "No host has been observed yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                val version = remember(context) {
                    runCatching { context.getString(CarAppR.string.car_app_library_version) }
                        .getOrDefault("unknown")
                }
                Text(
                    "Built-in entries come from androidx.car.app:app $version hosts_allowlist_sample.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = records.isNotEmpty(),
                ) { Text("Clear observed and enrolled hosts") }
            }
        }
    }
}

@Composable
private fun CarHostCard(
    row: CarHostRow,
    onEnroll: () -> Unit,
    onRevoke: () -> Unit,
    onConfirmActions: () -> Unit,
) {
    val record = row.record
    val enrolled = record?.enrolledAt != null
    val now = System.currentTimeMillis()
    val approvalAge = record?.actionApprovedAt?.let { now - it }
    val actionsConfirmed = approvalAge != null &&
        approvalAge in 0..CarHostTrustPolicy.ACTION_APPROVAL_WINDOW_MILLIS

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(row.identity.packageName, style = MaterialTheme.typography.titleSmall)
            Text(
                row.identity.displayFingerprint(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                when {
                    row.knownByAndroidx -> "AndroidX verified host"
                    enrolled -> "Manually enrolled · ${if (actionsConfirmed) "actions confirmed" else "read only until confirmed"}"
                    record?.firstObservedAt != null -> "Observed · Not trusted"
                    else -> "Entered identity · Not trusted"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    row.knownByAndroidx -> MaterialTheme.colorScheme.primary
                    enrolled -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.error
                },
            )
            record?.firstObservedAt?.let { Text("First observed: ${formatTimestamp(it)}", style = MaterialTheme.typography.bodySmall) }
            record?.enrolledAt?.let { Text("Enrolled: ${formatTimestamp(it)}", style = MaterialTheme.typography.bodySmall) }
            record?.lastUsedAt?.let { Text("Last used: ${formatTimestamp(it)}", style = MaterialTheme.typography.bodySmall) }
            if (!row.knownByAndroidx) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (enrolled) {
                        Button(onClick = onConfirmActions) { Text("Confirm 15 min") }
                        TextButton(onClick = onRevoke) { Text("Revoke") }
                    } else {
                        Button(onClick = onEnroll) { Text("Enroll") }
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
