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

/**
 * v0.6 backlog DTOs. Response shapes verified against the live Hermes
 * v0.19.1 dashboard API (hermes_cli/web_server.py + web_routers/tools.py,
 * 2026-08-02). Surfaces whose payloads are dynamic use JsonElement at the
 * call site instead of typed models.
 */

// --- Managed files (ROADMAP item 1) ---

@Serializable
data class ManagedFileEntry(
    val name: String = "",
    val path: String = "",
    @SerialName("is_directory") val isDirectory: Boolean = false,
    val size: Long? = null,
    val mtime: Double = 0.0,
    @SerialName("mime_type") val mimeType: String? = null,
)

@Serializable
data class ManagedFilesListResponse(
    val path: String = "",
    val parent: String? = null,
    val entries: List<ManagedFileEntry> = emptyList(),
    val root: String? = null,
    @SerialName("locked_root") val lockedRoot: String? = null,
    @SerialName("can_change_path") val canChangePath: Boolean = true,
)

@Serializable
data class ManagedFileReadResponse(
    val name: String = "",
    val path: String = "",
    val size: Long = 0,
    @SerialName("mime_type") val mimeType: String = "",
    @SerialName("data_url") val dataUrl: String = "",
)

@Serializable
data class MediaDataUrlResponse(
    @SerialName("data_url") val dataUrl: String = "",
)

// --- Terminal backends (ROADMAP item 6) ---

@Serializable
data class TerminalBackendRow(
    val name: String = "",
    val label: String = "",
    val description: String = "",
    val active: Boolean = false,
    val status: String = "",
    val detail: String = "",
)

@Serializable
data class TerminalBackendsResponse(
    val active: String = "",
    val backends: List<TerminalBackendRow> = emptyList(),
)

// --- Egress status (ROADMAP item 11) ---

@Serializable
data class EgressStatusResponse(
    val text: String = "",
)
