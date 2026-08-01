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

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.core.network.WsAuthHelper
import com.hermesgadget.talaria.domain.model.AnalyticsUsage
import com.hermesgadget.talaria.domain.model.ConfigSchemaResponse
import com.hermesgadget.talaria.domain.model.CronJob
import com.hermesgadget.talaria.domain.model.McpServersResponse
import com.hermesgadget.talaria.domain.model.McpCatalogResponse
import com.hermesgadget.talaria.domain.model.MessagingPlatformsResponse
import com.hermesgadget.talaria.domain.model.PasswordLoginRequest
import com.hermesgadget.talaria.domain.model.SessionsPage
import com.hermesgadget.talaria.domain.model.SkillInfo
import com.hermesgadget.talaria.domain.model.StatusResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class HermesApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HermesApi
    private val json = JsonConfig.json

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(HermesApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name"))
            .bufferedReader().use { it.readText() }

    @Test
    fun getStatusParsesCoreFields() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("status.json")))
        val status = api.getStatus()
        assertEquals("0.17.0", status.version)
        assertTrue(status.auth_required == true)
        assertEquals(listOf("basic"), status.auth_providers)
        assertEquals(42, status.gateway?.pid)
        assertEquals(1, status.sessions.size)
        assertEquals("/api/status", server.takeRequest().path)
    }

    @Test
    fun fixturesDecodeTypedModels() {
        assertNotNull(json.decodeFromString<StatusResponse>(fixture("status.json")).version)
        assertEquals(1, json.decodeFromString<SessionsPage>(fixture("sessions.json")).sessions.size)
        assertEquals("c1", json.decodeFromString<List<CronJob>>(fixture("cron.json")).first().id)
        assertEquals("web", json.decodeFromString<List<SkillInfo>>(fixture("skills.json")).first().name)
        assertTrue(json.decodeFromString<McpServersResponse>(fixture("mcp.json")).servers.isNotEmpty())
        assertTrue(
            json.decodeFromString<MessagingPlatformsResponse>(fixture("channels.json")).platforms.isNotEmpty(),
        )
        assertEquals(30, json.decodeFromString<AnalyticsUsage>(fixture("analytics.json")).days)
        assertTrue(
            json.decodeFromString<ConfigSchemaResponse>(fixture("config_schema.json")).category_order.isNotEmpty(),
        )
    }

    @Test
    fun putConfigPostsEnvelope() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        api.putConfig(JsonObject(emptyMap()))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue(req.path!!.startsWith("/api/config"))
    }

    @Test
    fun passwordLoginUsesCurrentJsonContract() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"next":"/"}"""))
        api.passwordLogin(PasswordLoginRequest("local-users", "alice", "secret"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/password-login", request.path)
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("local-users", body["provider"]?.jsonPrimitive?.content)
        assertEquals("alice", body["username"]?.jsonPrimitive?.content)
        assertEquals("secret", body["password"]?.jsonPrimitive?.content)
    }

    @Test
    fun mcpCatalogDecodesAndInstallUsesCurrentContract() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"entries":[{"name":"github","description":"GitHub tools","source":"nous","transport":"stdio","auth_type":"env","required_env":[{"name":"GITHUB_TOKEN","prompt":"Token","required":true}],"command":"uvx","args":["mcp-github"],"needs_install":false,"installed":false,"enabled":false}],"diagnostics":[]}""",
        ))
        val catalog: McpCatalogResponse = api.getMcpCatalog()
        assertEquals("GITHUB_TOKEN", catalog.entries.single().required_env.single().name)
        assertEquals("/api/mcp/catalog", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"ok":true,"name":"github","background":false}"""))
        api.installMcpCatalogEntry(buildJsonObject {
            put("name", "github")
            put("enable", true)
        })
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mcp/catalog/install", request.path)
        assertEquals("github", json.parseToJsonElement(request.body.readUtf8()).jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun wsCloseCodesExplainAuthAndHost() {
        assertTrue(WsAuthHelper.explainCloseCode(4401)!!.contains("4401"))
        assertTrue(WsAuthHelper.explainCloseCode(4403)!!.contains("4403"))
        assertEquals(null, WsAuthHelper.explainCloseCode(1000))
    }
}
