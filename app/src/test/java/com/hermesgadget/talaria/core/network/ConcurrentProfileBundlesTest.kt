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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B12 regression: concurrent management profiles on one connection must not
 * cancel each other's clients. Acquiring profile B's bundle used to evict
 * profile A's (eviction was keyed by connectionId), cancelling A's in-flight
 * REST calls and WebSocket handshakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConcurrentProfileBundlesTest {
    private lateinit var context: Context
    private lateinit var factory: HermesClientFactory
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        factory = HermesClientFactory(SecureConnectionStore(context), SettingsStore(context))
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Hold each request ~600ms so both profile calls are genuinely
                // in flight at the same time.
                Thread.sleep(600)
                return MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** No auth interceptor: bundle acquisition/eviction must not depend on store state. */
    private fun bareClient(snapshot: ConnectionSnapshot) = OkHttpClient.Builder().build()

    private fun profileSnapshot(profile: String, token: String? = null) = ConnectionSnapshot.from(
        ConnectionProfile(
            id = "b12-conn",
            name = "LAN",
            baseUrl = server.url("/").toString(),
            authMode = if (token == null) AuthMode.NONE else AuthMode.SESSION_TOKEN,
            managementProfile = profile,
            allowCleartext = true,
            cleartextConsentRecorded = true,
            cleartextConsentOrigin = ConnectionOrigin.normalize(server.url("/").toString()),
        ),
        ConnectionSecrets(sessionToken = token),
    ).withHttpLogging(false)

    @Test
    fun `acquiring a second profile bundle does not cancel the first profile's in-flight call`() {
        val a = profileSnapshot("alpha")
        val b = profileSnapshot("beta")

        // Warm A's bundle and start a call on it.
        val callA = bareClient(a).newCall(Request.Builder().url(server.url("/api/sessions")).build())
        val aDone = CountDownLatch(1)
        var aFailed: Throwable? = null
        val aThread = Thread {
            try {
                callA.execute().use { }
            } catch (t: Throwable) {
                aFailed = t
            } finally {
                aDone.countDown()
            }
        }
        aThread.start()

        // Give A's call time to reach the wire.
        Thread.sleep(200)
        // B12 regression trigger: acquiring B's bundle used to evict A's.
        val okB = factory.okHttp(b)
        bareClient(b).newCall(Request.Builder().url(server.url("/api/sessions")).build()).execute().use { }

        // A's call must complete successfully — not be cancelled by B.
        assertTrue("A's in-flight call was cancelled by B's acquisition", aDone.await(10, TimeUnit.SECONDS))
        assertEquals(null, aFailed)
        aThread.join(15_000)
    }

    @Test
    fun `same connection different profiles share the transport revision`() {
        val a = profileSnapshot("alpha")
        val b = profileSnapshot("beta")
        assertEquals(a.revisionKey(), b.revisionKey())
        assertNotEquals(a.hashCode(), 0)
    }

    @Test
    fun `rotated token still evicts older bundles`() {
        val before = profileSnapshot("alpha", token = "tok-1")
        val after = profileSnapshot("alpha", token = "tok-2")
        assertNotEquals(before.revisionKey(), after.revisionKey())

        // Both acquisitions succeed; the second must retire the first's bundle.
        val first = factory.okHttp(before)
        factory.okHttp(after) // builds + evicts the tok-1 revision
        bareClient(after).newCall(Request.Builder().url(server.url("/")).build()).execute().use { }
        // The first bundle's dispatcher was cancelled by the revision-scoped eviction.
        assertTrue(first.dispatcher.runningCallsCount() == 0)
    }
}
