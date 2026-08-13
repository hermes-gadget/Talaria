/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.network

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.data.prefs.SettingsStore
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The SPA-shell client is secret-bearing (its body is scraped for
 * `__HERMES_SESSION_TOKEN__`), so it must never follow redirects, must reject
 * cross-origin requests, and must bound the response body.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShellClientSecurityTest {
    private fun factory(): HermesClientFactory {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        return HermesClientFactory(SecureConnectionStore(ctx), SettingsStore(ctx))
    }

    private fun snapshot(baseUrl: String) = ConnectionSnapshot.from(
        ConnectionProfile(
            id = "shell-test",
            name = "shell",
            baseUrl = baseUrl,
            authMode = AuthMode.NONE,
            managementProfile = "",
            allowCleartext = true,
            cleartextConsentRecorded = true,
            cleartextConsentOrigin = ConnectionOrigin.normalize(baseUrl),
        ),
        ConnectionSecrets(),
    )

    /** Snapshot whose consent matches the test server but whose base URL is a different origin. */
    private fun snapshotConsentedFor(serverOrigin: String, baseUrl: String) = ConnectionSnapshot.from(
        ConnectionProfile(
            id = "shell-test",
            name = "shell",
            baseUrl = baseUrl,
            authMode = AuthMode.NONE,
            managementProfile = "",
            allowCleartext = true,
            cleartextConsentRecorded = true,
            cleartextConsentOrigin = ConnectionOrigin.normalize(serverOrigin),
        ),
        ConnectionSecrets(),
    )

    @Test
    fun `redirect response is returned, never followed`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://evil.example/token")
                .setBody("__HERMES_SESSION_TOKEN__ = \"stolen-token\""),
        )
        server.start()
        try {
            val snapshot = snapshot(server.url("/").toString())
            val request = Request.Builder().url(server.url("/")).get().build()
            factory().shellClient(snapshot).newCall(request).execute().use { response ->
                // followRedirects(false): the 302 comes back to the caller, and
                // fetchLoopbackSessionToken treats non-2xx as "no token".
                assertEquals(302, response.code)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `cross-origin request is rejected before it leaves`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        try {
            // Cleartext consent is recorded for the test server, but the
            // snapshot's base URL is a different origin: the origin guard —
            // not the cleartext gate — must reject the request.
            val snapshot = snapshotConsentedFor(server.url("/").toString(), "http://trusted.example")
            val request = Request.Builder().url(server.url("/")).get().build()
            assertThrows(IOException::class.java) {
                factory().shellClient(snapshot).newCall(request).execute()
            }
            Unit
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `oversized shell body is aborted on read`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("x".repeat(3 * 1024 * 1024)),
        )
        server.start()
        try {
            val snapshot = snapshot(server.url("/").toString())
            val request = Request.Builder().url(server.url("/")).get().build()
            // The declared 3 MiB exceeds the shell client's 2 MiB budget; the
            // interceptor rejects it before any body is materialized.
            assertThrows(IOException::class.java) {
                factory().shellClient(snapshot).newCall(request).execute()
            }
            Unit
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `pin is accepted when building the shell client`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val store = SecureConnectionStore(ctx)
        val settings = SettingsStore(ctx)
        val factory = HermesClientFactory(store, settings)
        val base = "https://pinned.example"
        val profile = ConnectionProfile(
            id = "shell-pin-test",
            name = "pinned",
            baseUrl = base,
            authMode = AuthMode.NONE,
            managementProfile = "",
            pinSha256 = "sha256/" + "A".repeat(43) + "=",
        )
        factory.shellClient(ConnectionSnapshot.from(profile, ConnectionSecrets()))
    }
}
