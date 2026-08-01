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

import kotlinx.serialization.Serializable

/** One entry in a `/api/fs/list` directory listing. */
@Serializable
data class FsEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean = false,
)

@Serializable
data class FsListResponse(
    val entries: List<FsEntry> = emptyList(),
    /** Hermes reports filesystem failures in a successful JSON response. */
    val error: String? = null,
)

/** Default working directory + git branch from `/api/fs/default-cwd`. */
@Serializable
data class FsCwd(
    val cwd: String = "",
    val branch: String = "",
)

/** A text file's contents + metadata from `/api/fs/read-text`. */
@Serializable
data class FsTextFile(
    val path: String = "",
    val text: String = "",
    val binary: Boolean = false,
    val byteSize: Long = 0,
    val language: String? = null,
    val mimeType: String? = null,
    val truncated: Boolean = false,
)

/** A file's data-URL (media preview) from `/api/fs/read-data-url`. */
@Serializable
data class FsDataUrl(
    val path: String = "",
    val dataUrl: String = "",
    val mimeType: String? = null,
    val byteSize: Long = 0,
)
