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

import com.hermesgadget.talaria.domain.model.ToolCallUi
import org.junit.Assert.assertEquals
import org.junit.Test

class ChangedFilesCardTest {

    @Test
    fun foldsCompletedFileEditsAndDiffCountsByPath() {
        val files = deriveChangedFiles(
            listOf(
                ToolCallUi(
                    id = "newer",
                    name = "edit_file",
                    status = "DONE",
                    argsPreview = "{\"path\":\"src/App.kt\"}",
                    message = "+++ b/src/App.kt\n@@\n+new line",
                ),
                ToolCallUi(
                    id = "older",
                    name = "patch",
                    status = "DONE",
                    argsPreview = "{\"path\":\"src/App.kt\"}",
                    message = "--- a/src/App.kt\n@@\n-old line",
                ),
                ToolCallUi(
                    id = "running",
                    name = "write_file",
                    status = "RUNNING",
                    argsPreview = "{\"path\":\"src/NotYet.kt\"}",
                ),
            ),
        )

        assertEquals(1, files.size)
        assertEquals("src/App.kt", files.single().path)
        assertEquals("App.kt", files.single().name)
        assertEquals(1, files.single().added)
        assertEquals(1, files.single().removed)
    }
}
