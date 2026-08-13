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

package com.hermesgadget.talaria.feature.manage.system

import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.domain.model.OpsHooksResponse
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import com.hermesgadget.talaria.core.util.suspendResult

/** Pure validation helpers kept separate so the SAF flow is easy to unit test. */
internal object OpsImportFileValidation {
    fun validate(fileName: String, file: File): String? {
        val extension = extensionOf(fileName)
        if (!file.isFile) return "The selected file is not available"
        if (file.length() <= 0L) return "The selected file is empty"

        return when (extension) {
            "json" -> validateJson(file.readText())
            "zip" -> if (hasZipSignature(file)) null else "The selected file is not a valid ZIP archive"
            else -> "Choose a JSON export or a Hermes backup ZIP"
        }
    }

    /** Byte-oriented form used by unit tests and small caller-side validations. */
    fun validate(fileName: String, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return "The selected file is empty"
        return when (extensionOf(fileName)) {
            "json" -> validateJson(bytes.toString(Charsets.UTF_8))
            "zip" -> if (hasZipSignature(bytes)) null else "The selected file is not a valid ZIP archive"
            else -> "Choose a JSON export or a Hermes backup ZIP"
        }
    }

    private fun validateJson(raw: String): String? = runCatching {
        val element = JsonConfig.json.parseToJsonElement(raw.removePrefix("\uFEFF"))
        require(element is JsonObject || element is JsonArray) {
            "JSON export must contain an object or array"
        }
    }.fold(
        onSuccess = { null },
        onFailure = { "The selected file is not valid JSON: ${it.message ?: "parse error"}" },
    )

    private fun hasZipSignature(file: File): Boolean = file.inputStream().buffered().use { input ->
        hasZipSignature(byteArrayOf(input.read().toByte(), input.read().toByte(), input.read().toByte(), input.read().toByte()))
    }

    private fun hasZipSignature(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == ZIP_P.toByte() &&
            bytes[1] == ZIP_K.toByte() &&
            ((bytes[2] == ZIP_LOCAL_FILE.toByte() && bytes[3] == ZIP_LOCAL_FILE_END.toByte()) ||
                (bytes[2] == ZIP_EMPTY.toByte() && bytes[3] == ZIP_EMPTY_END.toByte()) ||
                (bytes[2] == ZIP_SPANNED.toByte() && bytes[3] == ZIP_SPANNED_END.toByte()))

    private fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()

    private const val ZIP_P = 0x50
    private const val ZIP_K = 0x4B
    private const val ZIP_LOCAL_FILE = 0x03
    private const val ZIP_LOCAL_FILE_END = 0x04
    private const val ZIP_EMPTY = 0x05
    private const val ZIP_EMPTY_END = 0x06
    private const val ZIP_SPANNED = 0x07
    private const val ZIP_SPANNED_END = 0x08
}

internal fun parseOpsHooksJson(raw: String): OpsHooksResponse =
    JsonConfig.json.decodeFromString(raw)
