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

import com.nousresearch.talaria.core.network.HermesWebSocketUrlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HermesWebSocketUrlBuilderTest {
    @Test
    fun encodesCredentialsAndProfileValues() {
        val url = HermesWebSocketUrlBuilder.build(
            baseUrl = "https://example.test/hermes",
            endpoint = "api/ws",
            authQuery = "token=a+b/c==",
            query = listOf("profile" to "research & tools"),
        )!!

        assertEquals("/hermes/api/ws", url.encodedPath)
        assertEquals("a+b/c==", url.queryParameter("token"))
        assertEquals("research & tools", url.queryParameter("profile"))
    }

    @Test
    fun rejectsInvalidAndUnknownAuthQueries() {
        assertNull(HermesWebSocketUrlBuilder.build("not a URL", "api/ws"))
        val url = HermesWebSocketUrlBuilder.build("http://localhost:9119", "api/ws", "other=value")!!
        assertNull(url.queryParameter("other"))
    }
}
