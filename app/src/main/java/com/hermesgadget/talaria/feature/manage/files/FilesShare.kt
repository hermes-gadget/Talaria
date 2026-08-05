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
package com.hermesgadget.talaria.feature.manage.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.hermesgadget.talaria.R
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FileSharePayload(
    val path: String,
    val mimeType: String,
    val bytes: ByteArray? = null,
    val file: File? = null,
)

/** Builds the cache-backed FileProvider ACTION_SEND flow used by managed files. */
internal suspend fun buildFileShareIntent(
    context: Context,
    payload: FileSharePayload,
): Intent = withContext(Dispatchers.IO) {
    val shareFile = payload.file ?: payload.bytes?.let { bytes ->
        ShareFileManager(context.applicationContext.cacheDir).createShareFile(
            prefix = "hermes-file-",
            suffix = "-${safeShareFilename(payload.path)}",
            bytes = bytes,
        )
    } ?: error("Share payload has no file")
    require(shareFile.isFile) { "Share file is no longer available" }
    val uri: Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.files",
        shareFile,
    )
    Intent(Intent.ACTION_SEND).apply {
        type = payload.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(
            Intent.EXTRA_SUBJECT,
            context.getString(R.string.files_share_subject, payload.path.substringAfterLast('/')),
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun safeShareFilename(path: String): String {
    val stem = path.substringAfterLast('/')
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('.', '_', '-')
        .take(96)
        .ifBlank { "file" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(path.toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { "%02x".format(it) }
    return "$stem-$digest"
}
