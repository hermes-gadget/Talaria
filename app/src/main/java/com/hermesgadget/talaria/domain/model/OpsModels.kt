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

package com.hermesgadget.talaria.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Common response shape used by the dashboard's operations actions. */
@Serializable
data class OpsActionResponse(
    val ok: Boolean = false,
    val archive: String? = null,
    val name: String? = null,
    val pid: Int? = null,
    val error: String? = null,
    val message: String? = null,
    @SerialName("uploaded_bytes") val uploadedBytes: Long? = null,
)

@Serializable
data class OpsImportRequest(
    val archive: String,
    val force: Boolean = false,
)

@Serializable
data class OpsBackupRequest(
    val output: String? = null,
)

@Serializable
data class OpsHookEntry(
    val event: String = "",
    val matcher: String? = null,
    val command: String? = null,
    val timeout: Int? = null,
    val allowed: Boolean? = null,
    @SerialName("approved_at") val approvedAt: String? = null,
    val executable: Boolean? = null,
)

@Serializable
data class OpsHooksResponse(
    val hooks: List<OpsHookEntry> = emptyList(),
    @SerialName("valid_events") val validEvents: List<String> = emptyList(),
)

@Serializable
data class OpsHookCreateRequest(
    val event: String,
    val command: String,
    val matcher: String? = null,
    val timeout: Int? = null,
    val approve: Boolean = true,
)

@Serializable
data class OpsHookDeleteRequest(
    val event: String,
    val command: String,
)

@Serializable
data class OpsDebugShareRequest(
    val redact: Boolean = true,
    val lines: Int = 200,
)

@Serializable
data class OpsDebugShareResponse(
    val ok: Boolean = false,
    val urls: Map<String, String> = emptyMap(),
    val failures: List<String> = emptyList(),
    val redacted: Boolean = true,
    @SerialName("auto_delete_seconds") val autoDeleteSeconds: Int = 0,
)

@Serializable
data class OpsRawConfigResponse(
    val yaml: String = "",
    val path: String? = null,
)

@Serializable
data class OpsRawConfigUpdate(
    @SerialName("yaml_text") val yamlText: String,
    val profile: String? = null,
)
