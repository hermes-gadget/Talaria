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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffRendererTest {
    @Test
    fun lineDiffMarksChangedLinesAndKeepsContext() {
        val rows = renderLineDiff(
            before = "header\nold value\nfooter\n",
            after = "header\nnew value\nfooter\n",
        )

        assertEquals(
            listOf(
                DiffLine(DiffLineKind.CONTEXT, "header"),
                DiffLine(DiffLineKind.REMOVED, "old value"),
                DiffLine(DiffLineKind.ADDED, "new value"),
                DiffLine(DiffLineKind.CONTEXT, "footer"),
            ),
            rows,
        )
    }

    @Test
    fun lineDiffRendersNewFileAsAdditions() {
        val rows = renderLineDiff("", "one\ntwo\n")

        assertEquals(2, rows.size)
        assertTrue(rows.all { it.kind == DiffLineKind.ADDED })
        assertEquals(listOf("one", "two"), rows.map { it.text })
    }

    @Test
    fun unifiedPatchKeepsHeadersAndStripsDiffPrefixes() {
        val rows = renderUnifiedDiff(
            listOf(
                "diff --git a/notes.txt b/notes.txt",
                "index 1111111..2222222 100644",
                "--- a/notes.txt",
                "+++ b/notes.txt",
                "@@ -1,2 +1,2 @@",
                " keep",
                "-old",
                "+new",
            ).joinToString("\n"),
        )

        assertEquals(DiffLineKind.HEADER, rows[0].kind)
        assertEquals(DiffLineKind.HEADER, rows[2].kind)
        assertEquals(DiffLineKind.HEADER, rows[3].kind)
        assertEquals(DiffLine(DiffLineKind.CONTEXT, "keep"), rows[5])
        assertEquals(DiffLine(DiffLineKind.REMOVED, "old"), rows[6])
        assertEquals(DiffLine(DiffLineKind.ADDED, "new"), rows[7])
    }
}
