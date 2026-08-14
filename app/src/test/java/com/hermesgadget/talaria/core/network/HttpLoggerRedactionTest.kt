/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.network

import android.app.Application
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M4: the opt-in REST diagnostics logger must never leak the live session
 * cookie (or any credential header) into its sink.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HttpLoggerRedactionTest {
    private val factory = HermesClientFactory(
        connectionStore = com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore(
            org.robolectric.RuntimeEnvironment.getApplication(),
        ),
        settingsStore = com.hermesgadget.talaria.core.data.prefs.SettingsStore(
            org.robolectric.RuntimeEnvironment.getApplication(),
        ),
    )

    @Test
    fun `cookie values never reach the log sink`() {
        val sink = StringBuilder()
        val logger = factory.buildHttpLogger(
            HttpLoggingInterceptor.Logger { message -> sink.appendLine(message) },
        )
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(logger)
                .build()
            val request = Request.Builder()
                .url(server.url("/"))
                .header("Cookie", "hermes_session_at=SUPER-SECRET-COOKIE")
                .header("Authorization", "Bearer SUPER-SECRET-BEARER")
                .header(AuthInterceptor.SESSION_HEADER, "SUPER-SECRET-TOKEN")
                .build()
            client.newCall(request).execute().use { response ->
                assertTrue(response.isSuccessful)
            }
            val logged = sink.toString()
            assertFalse("cookie value leaked", logged.contains("SUPER-SECRET-COOKIE"))
            assertFalse("bearer leaked", logged.contains("SUPER-SECRET-BEARER"))
            assertFalse("session token leaked", logged.contains("SUPER-SECRET-TOKEN"))
        } finally {
            server.shutdown()
        }
    }
}
