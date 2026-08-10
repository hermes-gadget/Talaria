/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.core.network.ResponseBodyLimitInterceptor
import com.hermesgadget.talaria.core.network.ResponseTooLargeException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.http.GET

private interface OversizedJsonApi {
    @GET("api/status")
    suspend fun status(): JsonObject
}

class ResponseBodyLimitTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `declared content length is rejected before body consumption`() {
        server.enqueue(
            MockResponse()
                .setBody("123456789")
                .setHeader("Content-Length", "9"),
        )

        val error = assertThrows(ResponseTooLargeException::class.java) {
            limitedClient(defaultLimitBytes = 8L).newCall(
                okhttp3.Request.Builder().url(server.url("/api/status")).build(),
            ).execute()
        }

        assertEquals(8L, error.limitBytes)
        assertEquals(9L, error.declaredBytes)
        assertTrue(error.message.orEmpty().contains("Response too large"))
    }

    @Test
    fun `chunked body is aborted after the configured cap`() {
        server.enqueue(MockResponse().setChunkedBody("123456789", 2))

        val response = limitedClient(defaultLimitBytes = 8L).newCall(
            okhttp3.Request.Builder().url(server.url("/api/status")).build(),
        ).execute()
        val error = assertThrows(ResponseTooLargeException::class.java) {
            response.use { it.body!!.string() }
        }

        assertEquals(8L, error.limitBytes)
        assertTrue((error.observedBytes ?: 0L) > error.limitBytes)
        assertTrue(error.message.orEmpty().contains("Response too large"))
    }

    @Test
    fun `retrofit converter receives the bounded body`() {
        server.enqueue(MockResponse().setChunkedBody("{\"padding\":\"123456789\"}", 2))
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(limitedClient(defaultLimitBytes = 8L))
            .addConverterFactory(JsonConfig.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OversizedJsonApi::class.java)

        val error = assertThrows(ResponseTooLargeException::class.java) {
            runBlocking { api.status() }
        }

        assertEquals(8L, error.limitBytes)
    }

    @Test
    fun `body exactly at the cap is still readable`() {
        server.enqueue(MockResponse().setChunkedBody("12345678", 2))

        val response = limitedClient(defaultLimitBytes = 8L).newCall(
            okhttp3.Request.Builder().url(server.url("/api/status")).build(),
        ).execute()

        assertEquals("12345678", response.use { it.body!!.string() })
    }

    @Test
    fun `media and graph endpoints use explicit larger ceilings`() {
        val interceptor = ResponseBodyLimitInterceptor()

        assertEquals(
            24L * 1024L * 1024L,
            interceptor.limitFor("/api/media"),
        )
        assertEquals(
            8L * 1024L * 1024L,
            interceptor.limitFor("/api/learning/graph"),
        )
        assertEquals(
            ResponseBodyLimitInterceptor.DEFAULT_MAX_RESPONSE_BYTES,
            interceptor.limitFor("/api/status"),
        )
    }

    private fun limitedClient(defaultLimitBytes: Long): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            ResponseBodyLimitInterceptor(
                defaultLimitBytes = defaultLimitBytes,
                endpointLimits = emptyList(),
            ),
        )
        .build()
}
