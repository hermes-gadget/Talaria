/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.VoiceCapabilities
import com.hermesgadget.talaria.domain.model.scopeId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/** Public API coverage for capability probes and per-profile scope identity. */
class ServerSttScopeBehaviorTest {
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
    fun tearDown() = server.shutdown()

    @Test
    fun `openapi capability result reflects the server response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"paths":{"/api/audio/transcribe":{},"/api/audio/speak":{}}}""",
            ),
        )

        val paths = api.getOpenApi()["paths"]!!.jsonObject.keys
        val capabilities = VoiceCapabilities.fromOpenApiPaths(paths)

        assertTrue(capabilities.serverStt)
        assertTrue(capabilities.serverTts)
        assertTrue(capabilities.isComplete)
        assertEquals("/openapi.json", server.takeRequest().path)
    }

    @Test
    fun `missing server STT is represented without borrowing another capability`() {
        val sttOnly = VoiceCapabilities.fromOpenApiPaths(setOf("/api/audio/transcribe"))
        val ttsOnly = VoiceCapabilities.fromOpenApiPaths(setOf("/api/audio/speak"))

        assertTrue(sttOnly.serverStt)
        assertFalse(sttOnly.serverTts)
        assertFalse(ttsOnly.serverStt)
        assertTrue(ttsOnly.serverTts)
    }

    @Test
    fun `connection and management-profile changes produce separate scopes`() {
        val base = ConnectionProfile(
            id = "connection-1",
            name = "Dashboard",
            baseUrl = "https://example.test",
            authMode = AuthMode.NONE,
        )

        assertNotEquals(base.scopeId(), base.copy(managementProfile = "research").scopeId())
        assertNotEquals(base.scopeId(), base.copy(id = "connection-2").scopeId())
    }
}
