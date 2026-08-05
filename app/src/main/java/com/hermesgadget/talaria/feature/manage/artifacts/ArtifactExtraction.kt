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
import java.util.ArrayDeque
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

internal const val MAX_ARTIFACT_MESSAGES = 512
internal const val MAX_ARTIFACT_INPUT_BYTES = 512L * 1024L
internal const val MAX_ARTIFACT_TRANSCRIPT_BYTES = 4L * 1024L * 1024L
internal const val MAX_ARTIFACT_CANDIDATES = 256
internal const val MAX_ARTIFACT_JSON_NODES = 4_096
internal const val MAX_ARTIFACT_STRUCTURAL_DEPTH = 16
internal const val MAX_ARTIFACT_NESTED_DECODES = 64
internal const val MAX_ARTIFACT_PATH_CHARS = 4_096

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
    val budget = ExtractionBudget()
    val sessionTitle = session.title?.trim().takeUnless { it.isNullOrEmpty() }
        ?: session.preview?.trim().takeUnless { it.isNullOrEmpty() }
        ?: session.id
    var currentNewIds: MutableList<String>? = null

    fun add(raw: String) {
        if (!budget.takeCandidate()) return
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
        currentNewIds?.add(key)
    }

    messages.asSequence().take(MAX_ARTIFACT_MESSAGES).forEach { message ->
        if (budget.exhausted) return@forEach
        if (message.role !in setOf("assistant", "tool")) return@forEach
        val newIds = mutableListOf<String>()
        currentNewIds = newIds

        message.content?.takeIf { it.isNotBlank() }?.let { text ->
            val boundedText = budget.takeText(text)
            collectTextCandidates(boundedText, ::add)
            if (message.role == "tool") {
                parseJson(boundedText)?.let { collectJsonCandidates(it, "tool_result", ::add, budget) }
            }
        }

        message.tool_calls?.let { collectJsonCandidates(it, "tool_call", ::add, budget) }

        // Keep the originating message timestamp on the record. The map entry is
        // only created once, so repeated tool output cannot move it around.
        // A bounded candidate callback records only the ids made by this message;
        // copying the whole key set for every transcript item would retain and
        // repeatedly traverse unnecessary transcript-sized collections.
        // Candidates are added synchronously above, so the callback has already
        // populated this list. Reset the owner before the next transcript item.
        currentNewIds = null
        newIds.forEach { id ->
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

private data class JsonVisit(
    val element: JsonElement,
    val keyPath: String,
    val depth: Int,
)

/**
 * Walk JSON with an explicit stack. The depth belongs to every queued child;
 * object/array branches must never reset it or a stringified object/array chain
 * can evade the guard and recurse forever.
 */
private fun collectJsonCandidates(
    element: JsonElement,
    keyPath: String,
    add: (String) -> Unit,
    budget: ExtractionBudget,
) {
    val pending = ArrayDeque<JsonVisit>()
    pending.addLast(JsonVisit(element, keyPath.take(MAX_ARTIFACT_PATH_CHARS), depth = 0))
    while (pending.isNotEmpty() && !budget.exhausted) {
        val visit = pending.removeLast()
        if (!budget.takeJsonNode()) break
        when (val current = visit.element) {
            is JsonPrimitive -> {
                val value = current.contentOrNull ?: continue
                if (!budget.consumeJsonValue(value)) break
                val hasPathShape = value.startsWith('/') || value.startsWith("~/") ||
                    value.startsWith("./") || value.startsWith("../") || value.startsWith("file://")
                val hasKnownExtension = artifactKindForPath(value) != null
                if (hasPathShape || (hasKnownExtension && KEY_HINT_RE.containsMatchIn(visit.keyPath))) {
                    add(value)
                }

                // Json.parseToJsonElement accepts bare unquoted strings as JSON
                // literals. Prefix-check before every nested decode so a plain
                // path such as "report.txt" is never parsed back into itself.
                if (visit.depth < MAX_ARTIFACT_STRUCTURAL_DEPTH &&
                    budget.nestedDecodes < MAX_ARTIFACT_NESTED_DECODES
                ) {
                    val trimmed = value.trimStart()
                    if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
                        budget.nestedDecodes++
                        if (budget.remainingJsonSlots(pending.size) > 0) {
                            parseJson(value)?.let { nested ->
                                pending.addLast(
                                    JsonVisit(
                                        element = nested,
                                        keyPath = visit.keyPath,
                                        depth = visit.depth + 1,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            is JsonArray -> {
                if (visit.depth >= MAX_ARTIFACT_STRUCTURAL_DEPTH) continue
                val childCount = minOf(
                    current.size,
                    budget.remainingJsonSlots(pending.size),
                ).coerceAtLeast(0)
                for (index in (childCount - 1 downTo 0)) {
                    pending.addLast(
                        JsonVisit(
                            element = current[index],
                            keyPath = "${visit.keyPath}.$index".take(MAX_ARTIFACT_PATH_CHARS),
                            depth = visit.depth + 1,
                        ),
                    )
                }
            }

            is JsonObject -> {
                if (visit.depth >= MAX_ARTIFACT_STRUCTURAL_DEPTH) continue
                // Copy only a bounded prefix before reversing to preserve the
                // historical insertion order without queuing an untrusted map.
                val entries = current.entries.take(budget.remainingJsonSlots(pending.size))
                for ((key, child) in entries.reversed()) {
                    pending.addLast(
                        JsonVisit(
                            element = child,
                            keyPath = "${visit.keyPath}.$key".take(MAX_ARTIFACT_PATH_CHARS),
                            depth = visit.depth + 1,
                        ),
                    )
                }
            }
        }
    }
}

private fun parseJson(value: String): JsonElement? {
    val trimmed = value.trimStart()
    if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return null
    return runCatching { Json.parseToJsonElement(value) }.getOrNull()
}

private class ExtractionBudget {
    var candidates = 0
        private set
    var jsonNodes = 0
        private set
    var nestedDecodes = 0
    var inputBytes = 0L
        private set
    var exhausted = false
        private set

    fun takeCandidate(): Boolean {
        if (exhausted || candidates >= MAX_ARTIFACT_CANDIDATES) {
            exhausted = true
            return false
        }
        candidates++
        return true
    }

    fun takeJsonNode(): Boolean {
        if (exhausted || jsonNodes >= MAX_ARTIFACT_JSON_NODES) {
            exhausted = true
            return false
        }
        jsonNodes++
        return true
    }

    fun consumeJsonValue(value: String): Boolean {
        val bytes = value.toUtf8Length()
        if (bytes > MAX_ARTIFACT_INPUT_BYTES || inputBytes > MAX_ARTIFACT_TRANSCRIPT_BYTES - bytes) {
            exhausted = true
            return false
        }
        inputBytes += bytes
        return true
    }

    fun remainingJsonSlots(pendingSize: Int): Int =
        (MAX_ARTIFACT_JSON_NODES - jsonNodes - pendingSize).coerceAtLeast(0)

    fun takeText(value: String): String {
        if (exhausted || inputBytes >= MAX_ARTIFACT_TRANSCRIPT_BYTES) {
            exhausted = true
            return ""
        }
        val remaining = minOf(
            MAX_ARTIFACT_INPUT_BYTES,
            MAX_ARTIFACT_TRANSCRIPT_BYTES - inputBytes,
        )
        val bounded = value.takeUtf8Bytes(remaining)
        inputBytes += bounded.toUtf8Length()
        return bounded
    }
}

private fun normalizePath(raw: String): String? {
    var value = raw.trim()
        .trimStart('`', '(', '[', '<', '"', '\'')
        .trimEnd('`', ')', ']', '>', '"', '\'', ',', ';', ':', '.')
    if (value.length > MAX_ARTIFACT_PATH_CHARS) return null
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

private fun String.takeUtf8Bytes(maxBytes: Long): String {
    if (maxBytes <= 0L) return ""
    var used = 0L
    var index = 0
    while (index < length) {
        val character = this[index]
        val charBytes = when {
            character.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> 4
            character.code <= 0x7f -> 1
            character.code <= 0x7ff -> 2
            else -> 3
        }
        if (used + charBytes > maxBytes) break
        used += charBytes
        index += if (charBytes == 4) 2 else 1
    }
    return substring(0, index)
}

private fun String.toUtf8Length(): Long {
    var bytes = 0L
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            character.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                bytes += 4
                index += 2
            }
            character.code <= 0x7f -> {
                bytes++
                index++
            }
            character.code <= 0x7ff -> {
                bytes += 2
                index++
            }
            else -> {
                bytes += 3
                index++
            }
        }
    }
    return bytes
}
