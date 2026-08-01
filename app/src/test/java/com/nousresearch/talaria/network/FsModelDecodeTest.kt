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
package com.nousresearch.talaria.network

import com.nousresearch.talaria.core.network.JsonConfig
import com.nousresearch.talaria.domain.model.FsCwd
import com.nousresearch.talaria.domain.model.FsListResponse
import com.nousresearch.talaria.domain.model.FsTextFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes the real v0.19.0 `api/fs` response shapes captured from a live dashboard. */
class FsModelDecodeTest {
    private val json = JsonConfig.json

    @Test
    fun `decodes default-cwd`() {
        val cwd = json.decodeFromString(FsCwd.serializer(), """{"cwd":"/home/ben","branch":""}""")
        assertEquals("/home/ben", cwd.cwd)
        assertEquals("", cwd.branch)
    }

    @Test
    fun `decodes list with dirs and files`() {
        val res = json.decodeFromString(
            FsListResponse.serializer(),
            """{"entries":[
                {"name":".config","path":"/home/ben/.config","isDirectory":true},
                {"name":"notes.md","path":"/home/ben/notes.md","isDirectory":false}
            ]}""",
        )
        assertEquals(2, res.entries.size)
        assertTrue(res.entries[0].isDirectory)
        assertFalse(res.entries[1].isDirectory)
        assertEquals("notes.md", res.entries[1].name)
    }

    @Test
    fun `decodes read-text with metadata`() {
        val file = json.decodeFromString(
            FsTextFile.serializer(),
            """{"binary":false,"byteSize":40894,"language":"text","mimeType":"application/octet-stream","path":"/x/y.txt","text":"hello","truncated":true}""",
        )
        assertEquals("/x/y.txt", file.path)
        assertEquals("hello", file.text)
        assertEquals(40894L, file.byteSize)
        assertFalse(file.binary)
        assertTrue(file.truncated)
    }

    @Test
    fun `decodes successful filesystem error envelope`() {
        val res = json.decodeFromString(
            FsListResponse.serializer(),
            """{"entries":[],"error":"EACCES"}""",
        )
        assertEquals("EACCES", res.error)
    }
}
