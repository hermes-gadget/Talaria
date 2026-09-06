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

package com.hermesgadget.talaria.feature.manage.review

enum class DiffLineKind {
    HEADER,
    CONTEXT,
    REMOVED,
    ADDED,
}

data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
)

/**
 * Parses a unified patch returned by the Hermes git API into simple rows for
 * Compose. The patch is rendered on-device; no syntax or diff library is used.
 */
fun renderUnifiedDiff(unifiedDiff: String): List<DiffLine> {
    if (unifiedDiff.isBlank()) return emptyList()
    return unifiedDiff
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .removeSuffix("\n")
        .split('\n')
        .map { line ->
            when {
                line.startsWith("+") && !line.startsWith("+++") ->
                    DiffLine(DiffLineKind.ADDED, line.drop(1))

                line.startsWith("-") && !line.startsWith("---") ->
                    DiffLine(DiffLineKind.REMOVED, line.drop(1))

                line.startsWith(" ") -> DiffLine(DiffLineKind.CONTEXT, line.drop(1))
                else -> DiffLine(DiffLineKind.HEADER, line)
            }
        }
}

private fun splitLines(value: String): List<String> {
    if (value.isEmpty()) return emptyList()
    return value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .removeSuffix("\n")
        .split('\n')
}
