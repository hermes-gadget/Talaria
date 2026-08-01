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
package com.hermesgadget.talaria.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.domain.model.ToolCallUi

/** A compact, desktop-parity summary of files changed during the current turn. */
@Composable
fun ChangedFilesCard(
    tools: List<ToolCallUi>,
    modifier: Modifier = Modifier,
) {
    val files = remember(tools) { deriveChangedFiles(tools) }
    if (files.isEmpty()) return

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "${files.size} ${if (files.size == 1) "file" else "files"} changed",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            files.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (file.path != file.name) {
                            Text(
                                text = file.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (file.added > 0 || file.removed > 0) {
                        Text(
                            text = buildString {
                                if (file.added > 0) append("+${file.added}")
                                if (file.added > 0 && file.removed > 0) append("  ")
                                if (file.removed > 0) append("−${file.removed}")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

internal data class ChangedFileUi(
    val path: String,
    val name: String,
    val added: Int,
    val removed: Int,
)

/**
 * Fold completed file-edit tools into one row per path, preserving first-touch
 * order. The event model exposes compact argument/summary text rather than a
 * full tool-result object, so path and diff extraction intentionally accepts
 * both JSON-like arguments and unified-diff snippets.
 */
internal fun deriveChangedFiles(tools: List<ToolCallUi>): List<ChangedFileUi> {
    val byPath = linkedMapOf<String, ChangedFileUi>()
    // ChatViewModel prepends the newest tool; reverse it back to turn order.
    for (tool in tools.asReversed()) {
        if (!isFileEditTool(tool.name) || !tool.status.equals("DONE", ignoreCase = true)) continue
        val details = listOfNotNull(tool.argsPreview, tool.message).joinToString("\n")
        val path = extractFilePath(details) ?: continue
        val stats = countDiffStats(details)
        val existing = byPath[path]
        byPath[path] = if (existing == null) {
            ChangedFileUi(path, fileName(path), stats.first, stats.second)
        } else {
            existing.copy(
                added = existing.added + stats.first,
                removed = existing.removed + stats.second,
            )
        }
    }
    return byPath.values.toList()
}

private val FILE_PATH_KEY = Regex(
    """(?i)["']?(?:path|file|filepath|filename|resolved_path)["']?\s*[:=]\s*["']([^"']+)["']""",
)
private val DIFF_PATH = Regex("""(?m)^[+-]{3}\s+(?:[ab]/)?([^\s]+)""")

private fun isFileEditTool(name: String): Boolean {
    val normalized = name.trim().lowercase().replace('-', '_')
    if (normalized in setOf("edit_file", "patch", "write_file", "apply_patch", "file_edit")) return true
    return normalized.contains("file") && (
        normalized.contains("edit") || normalized.contains("write") || normalized.contains("patch") ||
            normalized.contains("create") || normalized.contains("replace")
        )
}

private fun extractFilePath(details: String): String? {
    FILE_PATH_KEY.find(details)?.groupValues?.getOrNull(1)?.let { return cleanPath(it) }
    DIFF_PATH.find(details)?.groupValues?.getOrNull(1)?.let { return cleanPath(it) }

    // Some Hermes versions summarize an edit as "updated src/foo.kt (+2 -1)"
    // instead of returning JSON args. Accept path-shaped words as a fallback.
    return details.split(Regex("\\s+"))
        .asSequence()
        .map(::cleanPath)
        .firstOrNull { looksLikePath(it) }
}

private fun cleanPath(value: String): String = value
    .trim()
    .trim('"', '\'', '`', '[', ']', '(', ')', '{', '}', ',', ';', ':')
    .removePrefix("a/")
    .removePrefix("b/")

private fun looksLikePath(value: String): Boolean {
    if (value.isBlank() || value.contains("://") || value.startsWith("+") || value.startsWith("-")) {
        return false
    }
    return value.startsWith('/') || value.startsWith("./") || value.startsWith("../") ||
        value.contains('/') || value.substringAfterLast('/').contains('.')
}

private fun fileName(path: String): String = path.replace('\\', '/').substringAfterLast('/').ifBlank { path }

private fun countDiffStats(details: String): Pair<Int, Int> {
    var added = 0
    var removed = 0
    details.lineSequence().forEach { line ->
        when {
            line.startsWith("+++") || line.startsWith("---") -> Unit
            line.startsWith('+') -> added++
            line.startsWith('-') -> removed++
        }
    }
    return added to removed
}
