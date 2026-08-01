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
package com.hermesgadget.talaria.network

import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.domain.model.CuratorState
import com.hermesgadget.talaria.domain.model.MemoryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the typed models added for the Memory/Curator screens against the real payload shapes. */
class TypedModelDecodeTest {

    @Test
    fun decodesMemoryStateWithProviders() {
        val json = """
            {"active":"","providers":[
              {"name":"byterover","description":"ByteRover","available":false,"configured":true,"status":"unavailable",
               "setup":{"pip_dependencies":[],"required_env":[],"dependencies_installed":false}},
              {"name":"holographic","description":"Holographic","available":true,"configured":true,"status":"available"}
            ]}
        """.trimIndent()
        val state = JsonConfig.json.decodeFromString(MemoryState.serializer(), json)
        assertEquals(2, state.providers.size)
        assertEquals("byterover", state.providers[0].name)
        assertFalse(state.providers[0].available)
        assertTrue(state.providers[1].available)
    }

    @Test
    fun decodesCuratorState() {
        val json = """
            {"enabled":true,"paused":false,"interval_hours":168,"last_run_at":"2026-07-31T14:34:49.4+00:00",
             "min_idle_hours":2.0,"stale_after_days":30,"archive_after_days":90}
        """.trimIndent()
        val state = JsonConfig.json.decodeFromString(CuratorState.serializer(), json)
        assertTrue(state.enabled)
        assertFalse(state.paused)
        assertEquals(168.0, state.interval_hours!!, 0.001)
        assertEquals(30, state.stale_after_days)
        assertEquals(90, state.archive_after_days)
    }

    /** Unknown fields (extra keys) must not break decoding — JsonConfig ignores them. */
    @Test
    fun toleratesUnknownFields() {
        val json = """{"enabled":true,"paused":false,"brand_new_field":"x"}"""
        val state = JsonConfig.json.decodeFromString(CuratorState.serializer(), json)
        assertTrue(state.enabled)
    }
}
