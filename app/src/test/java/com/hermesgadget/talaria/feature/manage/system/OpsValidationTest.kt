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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpsValidationTest {
    @Test
    fun hooksPayloadParsesConfiguredFieldsAndIgnoresUnknownFields() {
        val response = parseOpsHooksJson(
            """
            {
              "hooks": [
                {
                  "event": "on_session_start",
                  "command": "echo started",
                  "matcher": "^cli$",
                  "timeout": 12,
                  "allowed": true,
                  "approved_at": "2026-08-01T12:00:00Z",
                  "executable": false,
                  "unknown": "ignored"
                }
              ],
              "valid_events": ["on_session_start", "on_session_end"]
            }
            """.trimIndent(),
        )

        assertEquals(1, response.hooks.size)
        assertEquals("on_session_start", response.hooks.single().event)
        assertEquals("echo started", response.hooks.single().command)
        assertEquals("^cli$", response.hooks.single().matcher)
        assertEquals(12, response.hooks.single().timeout)
        assertTrue(response.hooks.single().allowed == true)
        assertFalse(response.hooks.single().executable == true)
        assertEquals(listOf("on_session_start", "on_session_end"), response.validEvents)
    }

    @Test
    fun importValidationAcceptsValidJson() {
        assertNull(
            OpsImportFileValidation.validate(
                "hermes-export.json",
                "{\"sessions\":[]}".toByteArray(),
            ),
        )
    }

    @Test
    fun importValidationRejectsMalformedJson() {
        val error = OpsImportFileValidation.validate("hermes-export.json", "not-json".toByteArray())

        assertTrue(error != null)
    }

    @Test
    fun importValidationChecksZipSignature() {
        assertNull(
            OpsImportFileValidation.validate(
                "hermes-backup.zip",
                byteArrayOf(0x50, 0x4B, 0x03, 0x04),
            ),
        )
        assertTrue(
            OpsImportFileValidation.validate(
                "hermes-backup.zip",
                byteArrayOf(0x7B, 0x22, 0x7D),
            )?.contains("ZIP") == true,
        )
    }

    @Test
    fun importValidationRejectsEmptyAndUnsupportedFiles() {
        assertEquals("The selected file is empty", OpsImportFileValidation.validate("empty.json", byteArrayOf()))
        assertTrue(
            OpsImportFileValidation.validate("backup.txt", byteArrayOf(1, 2, 3))
                ?.contains("JSON export") == true,
        )
    }
}
