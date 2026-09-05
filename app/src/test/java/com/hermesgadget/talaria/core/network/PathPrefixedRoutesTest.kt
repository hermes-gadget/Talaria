/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.network

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.data.prefs.SettingsStore
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B16 regression: a deployment served under a path prefix
 * (`https://host/hermes/`) must keep its profile injection and endpoint
 * response budgets. The prefix used to make every
 * `startsWith("/api/")`-style check miss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PathPrefixedRoutesTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun snapshot(baseUrl: String, profile: String = "work") = ConnectionSnapshot.from(
        ConnectionProfile(
            id = "b16",
            name = "LAN",
            baseUrl = baseUrl,
            authMode = AuthMode.SESSION_TOKEN,
            managementProfile = profile,
            allowCleartext = true,
            cleartextConsentRecorded = true,
            cleartextConsentOrigin = ConnectionOrigin.normalize(baseUrl),
        ),
        ConnectionSecrets(sessionToken = "tok"),
    )

    private fun profiledClient(snapshot: ConnectionSnapshot): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(ProfileQueryInterceptor(snapshot))
            .build()

    @Test
    fun `profile query is injected under a path prefix`() {
        val base = server.url("/hermes/").toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        profiledClient(snapshot(base))
            .newCall(Request.Builder().url(server.url("/hermes/api/sessions")).build())
            .execute()
            .use { assertEquals(200, it.code) }
        val recorded = server.takeRequest()
        assertEquals("/hermes/api/sessions", recorded.requestUrl?.encodedPath)
        assertEquals("work", recorded.requestUrl?.queryParameter("profile"))
    }

    @Test
    fun `profile query is injected at the root too (unchanged behavior)`() {
        val base = server.url("/").toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        profiledClient(snapshot(base))
            .newCall(Request.Builder().url(server.url("/api/sessions")).build())
            .execute()
            .use { assertEquals(200, it.code) }
        val recorded = server.takeRequest()
        assertEquals("work", recorded.requestUrl?.queryParameter("profile"))
    }

    @Test
    fun `unscoped endpoints stay unscoped under a prefix`() {
        val base = server.url("/hermes/").toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        profiledClient(snapshot(base))
            .newCall(Request.Builder().url(server.url("/hermes/api/profiles")).build())
            .execute()
            .use { assertEquals(200, it.code) }
        val recorded = server.takeRequest()
        assertNull("profiles list must not get a profile query", recorded.requestUrl?.queryParameter("profile"))
    }

    @Test
    fun `response body limit matches endpoint keys under a prefix`() {
        // A 3 MiB body exceeds the 2 MiB default but fits the /api/sessions
        // endpoint's 4 MiB budget. Under the /hermes/ prefix the interceptor
        // must still resolve the endpoint budget — stripping is done by
        // intercept(), so this is observed on the wire, not via limitFor().
        val interceptor = ResponseBodyLimitInterceptor(
            basePath = RoutePath.basePath("https://host/hermes/"),
        )
        assertEquals(4L * 1024L * 1024L, interceptor.limitFor("/api/sessions"))
        assertEquals(2L * 1024L * 1024L, interceptor.limitFor("/api/unknown-endpoint"))

        server.enqueue(
            MockResponse().setResponseCode(200).setBody("x".repeat(3 * 1024 * 1024)),
        )
        val prefixed = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        // Must NOT throw: 3 MiB fits the endpoint budget once the prefix is stripped.
        prefixed.newCall(Request.Builder().url(server.url("/hermes/api/sessions")).build())
            .execute()
            .use { response -> assertEquals(200, response.code) }
    }

    @Test
    fun `factory-built clients prefix their limit path (wire check)`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.httpLoggingEnabled } returns false
        val base = server.url("/hermes/").toString()
        val snap = snapshot(base, profile = "")
        val store = mockk<SecureConnectionStore>()
        every { store.snapshotFor(snap.connectionId) } returns snap
        val factory = HermesClientFactory(store, settings)
        val snapshot = snap
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"sessions":[]}"""))
        factory.okHttp(snapshot)
            .newCall(Request.Builder().url(server.url("/hermes/api/sessions")).build())
            .execute()
            .use { response -> assertEquals(200, response.code) }
        val recorded = server.takeRequest()
        assertEquals("/hermes/api/sessions", recorded.requestUrl?.encodedPath)
        assertEquals("default", recorded.requestUrl?.queryParameter("profile"))
    }

    @Test
    fun `RoutePath base and route math`() {
        assertEquals("/", RoutePath.basePath("https://host"))
        assertEquals("/", RoutePath.basePath("https://host/"))
        assertEquals("/hermes/", RoutePath.basePath("https://host/hermes/"))
        assertEquals("/hermes/sub/", RoutePath.basePath("https://host/hermes/sub/"))
        assertEquals("/api/status", RoutePath.routePath("/api/status", "/"))
        assertEquals("/api/status", RoutePath.routePath("/hermes/api/status", "/hermes/"))
        assertEquals("/api/status", RoutePath.routePath("/hermes/sub/api/status", "/hermes/sub/"))
        // No matching prefix: unchanged (direct-origin probes still match root rules).
        assertEquals("/api/status", RoutePath.routePath("/api/status", "/hermes/"))
    }
}
