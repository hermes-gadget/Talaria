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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B19 regression: an OIDC token rotation commits from inside the refreshing
 * request's own interceptor chain. The old callback (evictConnection) ran
 * cancelAll() on that very dispatcher, cancelling the caller's original
 * request with "connection changed". Rotation must retire old-revision
 * bundles for FUTURE acquisition without cancelling in-flight work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TokenRotationRetirementTest {
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

    private fun snapshot(token: String) = ConnectionSnapshot.from(
        ConnectionProfile(
            id = "b19",
            name = "LAN",
            baseUrl = server.url("/").toString(),
            authMode = AuthMode.SESSION_TOKEN,
            allowCleartext = true,
            cleartextConsentRecorded = true,
            cleartextConsentOrigin = ConnectionOrigin.normalize(server.url("/").toString()),
        ),
        ConnectionSecrets(sessionToken = token),
    ).withHttpLogging(false)

    @Test
    fun `rotation retires the old bundle without cancelling its in-flight call`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.httpLoggingEnabled } returns false

        val snap = snapshot("tok-1")
        val store = mockk<SecureConnectionStore>()
        every { store.snapshotFor(snap.connectionId) } returns snap
        val factory = HermesClientFactory(store, settings)
        // Acquire A's bundle.
        val clientA = factory.okHttp(snap)
        // The slow endpoint holds the call while the rotation fires.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(500)
                return MockResponse().setResponseCode(200).setBody("{}")
            }
        }
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        Thread {
            try {
                clientA.newCall(Request.Builder().url(server.url("/api/status")).build())
                    .execute()
                    .use { it.body!!.string() }
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }.start()

        Thread.sleep(150)
        // Rotation fires mid-flight: old bundle must leave the map, but its
        // dispatcher must stay alive for the in-flight call.
        factory.onTokensRotatedForTest("b19")

        assertTrue("in-flight call must complete after rotation", done.await(10, TimeUnit.SECONDS))
        assertEquals("the call must not be cancelled by its own rotation", null, failure)

        // Future acquisition builds a FRESH bundle (old one retired).
        val clientB = factory.okHttp(snapshot("tok-2"))
        assertTrue("new bundle must be a different client", clientB !== clientA)
    }
}
