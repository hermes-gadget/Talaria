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

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.nousresearch.talaria.core.network.HermesApi
import com.nousresearch.talaria.core.network.JsonConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class HermesApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HermesApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(JsonConfig.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(HermesApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getStatusParsesCoreFields() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"version":"0.17.0","auth_required":true,"auth_providers":["basic"],"gateway":{"running":true,"pid":42},"active_sessions":2,"sessions":[]}""",
            ),
        )
        val status = api.getStatus()
        assertEquals("0.17.0", status.version)
        assertTrue(status.auth_required == true)
        assertEquals(listOf("basic"), status.auth_providers)
        assertEquals(42, status.gateway?.pid)
        assertEquals("/api/status", server.takeRequest().path)
    }

    @Test
    fun putConfigPostsEnvelope() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        api.putConfig(JsonObject(emptyMap()))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue(req.path!!.startsWith("/api/config"))
    }
}
