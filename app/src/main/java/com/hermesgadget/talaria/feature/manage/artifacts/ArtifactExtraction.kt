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

package com.hermesgadget.talaria.feature.manage.artifacts

import com.hermesgadget.talaria.domain.model.SessionMessage
import com.hermesgadget.talaria.domain.model.SessionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The compact set of artifact kinds surfaced by the mobile browser. */
enum class ArtifactKind {
    IMAGE,
    TEXT,
    ARCHIVE,
}

/** A file path found in an assistant/tool transcript. */
data class ArtifactRecord(
    val id: String,
    val path: String,
    val kind: ArtifactKind,
    val label: String,
    val sessionId: String,
    val sessionTitle: String,
    val timestamp: String? = null,
)

/** Filters the pure extraction result without coupling it to Compose state. */
fun filterArtifacts(records: List<ArtifactRecord>, filter: ArtifactKind?): List<ArtifactRecord> =
    if (filter == null) records else records.filter { it.kind == filter }

/**
 * Extracts remote filesystem artifacts from one session's assistant/tool messages.
 *
 * Hermes has no artifacts endpoint. The desktop client therefore treats paths in
 * transcript text and tool payloads as the source of truth; this is the same
 * approach adapted to the typed Android session models.
 */
fun extractArtifacts(session: SessionSummary, messages: List<SessionMessage>): List<ArtifactRecord> {
    val found = LinkedHashMap<String, ArtifactRecord>()
    val sessionTitle = session.title?.trim().takeUnless { it.isNullOrEmpty() }
        ?: session.preview?.trim().takeUnless { it.isNullOrEmpty() }
        ?: session.id

    fun add(raw: String) {
        val path = normalizePath(raw) ?: return
        val kind = artifactKindForPath(path) ?: return
        val key = "${session.id}:$path"
        if (key in found) return
        found[key] = ArtifactRecord(
            id = key,
            path = path,
            kind = kind,
            label = artifactLabel(path),
            sessionId = session.id,
            sessionTitle = sessionTitle,
            timestamp = null,
        )
    }

    messages.forEach { message ->
        if (message.role !in setOf("assistant", "tool")) return@forEach
        val before = found.keys.toSet()

        message.content?.takeIf { it.isNotBlank() }?.let { text ->
            collectTextCandidates(text, ::add)
            if (message.role == "tool") {
                parseJson(text)?.let { collectJsonCandidates(it, "tool_result", ::add) }
            }
        }

        message.tool_calls?.let { collectJsonCandidates(it, "tool_call", ::add) }

        // Keep the originating message timestamp on the record. The map entry is
        // only created once, so repeated tool output cannot move it around.
        found.keys
            .filterNot(before::contains)
            .toList()
            .forEach { id ->
                found[id]?.let { record -> found[id] = record.copy(timestamp = message.timestamp) }
            }
    }

    return found.values.toList()
}

/** Returns the mobile filter bucket for a supported artifact path, or null. */
fun artifactKindForPath(path: String): ArtifactKind? {
    if (path.contains("://") && !path.startsWith("file://", ignoreCase = true)) return null
    val filename = path
        .substringBefore('?')
        .substringBefore('#')
        .trimEnd('/', '\\')
        .substringAfterLast('/')
        .substringAfterLast('\\')
    val extension = filename.substringAfterLast('.', "").lowercase()
    if (extension.isBlank() || filename == ".${extension}") return null

    return when (extension) {
        in IMAGE_EXTENSIONS -> ArtifactKind.IMAGE
        in TEXT_EXTENSIONS -> ArtifactKind.TEXT
        in ARCHIVE_EXTENSIONS -> ArtifactKind.ARCHIVE
        else -> null
    }
}

private val IMAGE_EXTENSIONS = setOf(
    "avif", "bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "svg", "webp",
)

private val TEXT_EXTENSIONS = setOf(
    "bash", "c", "cfg", "conf", "cpp", "css", "csv", "go", "h", "htm", "html",
    "ini", "java", "js", "json", "jsx", "kt", "kts", "log", "md", "markdown", "py",
    "rb", "rs", "sh", "sql", "swift", "toml", "ts", "tsx", "txt", "tsv", "xml", "yaml",
    "yml", "zsh",
)

private val ARCHIVE_EXTENSIONS = setOf(
    "7z", "apk", "bz2", "gz", "jar", "pdf", "rar", "tar", "tgz", "war", "xz", "zip",
)

private val extensionPattern = (IMAGE_EXTENSIONS + TEXT_EXTENSIONS + ARCHIVE_EXTENSIONS)
    .sortedByDescending { it.length }
    .joinToString("|")

private val PATH_TOKEN_RE = Regex(
    """(?<![A-Za-z0-9_])((?:file://)?(?:/|~/|\./|\.\./)[^\s<>\"'`()\[\]{}]+|[A-Za-z0-9_.~-]+(?:/[A-Za-z0-9_.~-]+)*\.(?:$extensionPattern))(?![A-Za-z0-9_])""",
    RegexOption.IGNORE_CASE,
)

private val MARKDOWN_IMAGE_RE = Regex("""!\[[^]]*\]\(([^)\s]+)\)""")
private val MARKDOWN_LINK_RE = Regex("""(?<!!)\[[^]]+\]\(([^)\s]+)\)""")
private val KEY_HINT_RE = Regex("(?:path|file|url|image|artifact|output|download|result|target)", RegexOption.IGNORE_CASE)

private fun collectTextCandidates(text: String, add: (String) -> Unit) {
    MARKDOWN_IMAGE_RE.findAll(text).forEach { add(it.groupValues[1]) }
    MARKDOWN_LINK_RE.findAll(text).forEach { add(it.groupValues[1]) }
    PATH_TOKEN_RE.findAll(text).forEach { add(it.groupValues[1]) }
}

/** Max stringified-JSON unwrap depth. Real tool payloads rarely nest beyond one or two levels. */
private const val MAX_STRINGIFIED_JSON_DEPTH = 16

private fun collectJsonCandidates(element: JsonElement, keyPath: String, add: (String) -> Unit) {
    collectJsonCandidates(element, keyPath, add, 0)
}

private fun collectJsonCandidates(element: JsonElement, keyPath: String, add: (String) -> Unit, depth: Int) {
    when (element) {
        is JsonPrimitive -> {
            val value = element.contentOrNull ?: return
            val hasPathShape = value.startsWith('/') || value.startsWith("~/") ||
                value.startsWith("./") || value.startsWith("../") || value.startsWith("file://")
            val hasKnownExtension = artifactKindForPath(value) != null
            if (hasPathShape || (hasKnownExtension && KEY_HINT_RE.containsMatchIn(keyPath))) {
                add(value)
            }

            // Tool arguments frequently put a JSON object in a string field.
            // Only unwrap values that actually look like a JSON object/array:
            // parseToJsonElement also accepts bare unquoted strings as JSON
            // literals (e.g. "report.txt"), which would re-parse to themselves
            // and recurse without bound.
            if (depth < MAX_STRINGIFIED_JSON_DEPTH) {
                val trimmed = value.trimStart()
                if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
                    parseJson(value)?.let { nested ->
                        collectJsonCandidates(nested, keyPath, add, depth + 1)
                    }
                }
            }
        }

        is JsonArray -> element.forEachIndexed { index, child ->
            collectJsonCandidates(child, "$keyPath.$index", add)
        }

        is JsonObject -> element.forEach { (key, child) ->
            collectJsonCandidates(child, "$keyPath.$key", add)
        }
    }
}

private fun parseJson(value: String): JsonElement? = runCatching {
    Json.parseToJsonElement(value)
}.getOrNull()

private fun normalizePath(raw: String): String? {
    var value = raw.trim()
        .trimStart('`', '(', '[', '<', '"', '\'')
        .trimEnd('`', ')', ']', '>', '"', '\'', ',', ';', ':', '.')
    if (value.startsWith("file://", ignoreCase = true)) {
        value = value.substringAfter("file://", missingDelimiterValue = value)
    }
    return value.takeIf { it.isNotBlank() && artifactKindForPath(it) != null }
}

private fun artifactLabel(path: String): String = path
    .substringBefore('?')
    .substringBefore('#')
    .trimEnd('/', '\\')
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .ifBlank { path }
