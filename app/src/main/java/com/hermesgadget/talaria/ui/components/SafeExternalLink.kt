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

package com.hermesgadget.talaria.ui.components

import android.net.Uri
import androidx.core.net.toUri
import java.util.Locale
import com.hermesgadget.talaria.core.util.suspendResult

private val SAFE_EXTERNAL_WEB_SCHEMES = setOf("http", "https")

/**
 * Parse a dashboard-provided link without giving it control over Android's
 * custom/deep-link intent handlers.
 *
 * Web links are intentionally required to be hierarchical and host-bearing;
 * opaque values such as intent:// and malformed values are rejected.
 */
internal fun safeExternalWebUri(raw: String): Uri? {
    val value = raw.trim()
    if (value.isEmpty()) return null

    val uri = runCatching { value.toUri() }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme !in SAFE_EXTERNAL_WEB_SCHEMES || uri.isOpaque || uri.host.isNullOrBlank()) {
        return null
    }
    return uri
}

/** Open a validated web link and contain resolver failures at the UI edge. */
internal fun openSafeExternalWebUri(
    raw: String,
    open: (Uri) -> Unit,
): Boolean {
    val uri = safeExternalWebUri(raw) ?: return false
    return runCatching {
        open(uri)
        true
    }.getOrDefault(false)
}
