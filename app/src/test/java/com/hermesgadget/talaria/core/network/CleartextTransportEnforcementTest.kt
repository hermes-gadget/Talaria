/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.core.network

import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class CleartextTransportEnforcementTest {
    @Test
    fun interceptorBlocksAProfileWithOnlyTheLegacyCompatibilityBit() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        try {
            val base = server.url("/")
            val blocked = snapshot(base.toString(), recorded = false, allowCleartext = true)
            val client = OkHttpClient.Builder()
                .addInterceptor(CleartextPolicyInterceptor(blocked))
                .build()
            assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(base).build()).execute()
            }
            assertEquals(0, server.requestCount)

            val approved = snapshot(base.toString(), recorded = true, allowCleartext = false)
            val approvedClient = OkHttpClient.Builder()
                .addInterceptor(CleartextPolicyInterceptor(approved))
                .build()
            approvedClient.newCall(Request.Builder().url(base).build()).execute().use { response ->
                assertEquals(200, response.code)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun redirectToAnotherOriginIsRejectedBeforeTheSecondRequest() {
        val first = MockWebServer()
        val second = MockWebServer()
        first.start()
        second.start()
        try {
            first.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .setHeader("Location", second.url("/credential")),
            )
            val base = first.url("/")
            val snapshot = snapshot(base.toString(), recorded = true, allowCleartext = true)
            val client = OkHttpClient.Builder()
                .addInterceptor(CleartextPolicyInterceptor(snapshot))
                .addNetworkInterceptor(SnapshotOriginInterceptor(snapshot))
                .addNetworkInterceptor(CleartextPolicyInterceptor(snapshot))
                .build()
            assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(base).build()).execute()
            }
            assertEquals(0, second.requestCount)
        } finally {
            first.shutdown()
            second.shutdown()
        }
    }

    private fun snapshot(baseUrl: String, recorded: Boolean, allowCleartext: Boolean): ConnectionSnapshot {
        val origin = ConnectionOrigin.normalize(baseUrl)
        return ConnectionSnapshot(
            profile = ConnectionProfile(
                id = "test",
                name = "Test",
                baseUrl = baseUrl,
                authMode = AuthMode.NONE,
                allowCleartext = allowCleartext,
                cleartextConsentRecorded = recorded,
                cleartextConsentOrigin = origin.takeIf { recorded },
            ),
            secrets = ConnectionSecrets(),
        )
    }
}
