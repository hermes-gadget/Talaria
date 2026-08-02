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
 * Small, dependency-free line diff for text that is already available locally.
 * The LCS table keeps the output stable for the short files shown on a phone.
 */
fun renderLineDiff(before: String, after: String): List<DiffLine> {
    val oldLines = splitLines(before)
    val newLines = splitLines(after)
    val lcs = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }

    for (oldIndex in oldLines.indices.reversed()) {
        for (newIndex in newLines.indices.reversed()) {
            lcs[oldIndex][newIndex] = if (oldLines[oldIndex] == newLines[newIndex]) {
                lcs[oldIndex + 1][newIndex + 1] + 1
            } else {
                maxOf(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1])
            }
        }
    }

    val result = ArrayList<DiffLine>(oldLines.size + newLines.size)
    var oldIndex = 0
    var newIndex = 0
    while (oldIndex < oldLines.size || newIndex < newLines.size) {
        when {
            oldIndex == oldLines.size -> {
                result += DiffLine(DiffLineKind.ADDED, newLines[newIndex++])
            }

            newIndex == newLines.size -> {
                result += DiffLine(DiffLineKind.REMOVED, oldLines[oldIndex++])
            }

            oldLines[oldIndex] == newLines[newIndex] -> {
                result += DiffLine(DiffLineKind.CONTEXT, oldLines[oldIndex])
                oldIndex++
                newIndex++
            }

            lcs[oldIndex + 1][newIndex] >= lcs[oldIndex][newIndex + 1] -> {
                result += DiffLine(DiffLineKind.REMOVED, oldLines[oldIndex++])
            }

            else -> {
                result += DiffLine(DiffLineKind.ADDED, newLines[newIndex++])
            }
        }
    }
    return result
}

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
