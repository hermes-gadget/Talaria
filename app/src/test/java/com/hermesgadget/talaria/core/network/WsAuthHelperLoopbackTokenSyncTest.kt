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

package com.hermesgadget.talaria.core.network

import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.data.prefs.SettingsStore
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows

/**
 * REST auth uses the STORED token while WS auth uses the token freshly
 * discovered from the SPA shell. If they diverge (dashboard restart with a new
 * process token, user-entered stale token), every REST call 401s while the PTY
 * still connects — the app looks Live but chats never update. The WS-auth path
 * must therefore sync the discovered token back into the store so REST
 * converges. This test pins that sync.
 */
class WsAuthHelperLoopbackTokenSyncTest {

    private lateinit var server: MockWebServer
    private lateinit var store: SecureConnectionStore
    private lateinit var helper: WsAuthHelper

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = mockk(relaxed = true)
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.httpLoggingEnabled } returns false
        helper = WsAuthHelper(
            clientFactory = HermesClientFactory(store, settings),
            connectionStore = store,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun snapshot(storedToken: String?): ConnectionSnapshot {
        val base = server.url("/").toString().trimEnd('/')
        val origin = ConnectionOrigin.normalize(base)
        val profile = ConnectionProfile(
            id = "test-conn",
            name = "Test",
            baseUrl = base,
            authMode = AuthMode.SESSION_TOKEN,
            managementProfile = "",
            // The real save flow records consent for a verified cleartext
            // destination before any request leaves; without it the
            // CleartextPolicyInterceptor fails closed and the SPA shell is
            // never fetched.
            allowCleartext = true,
            cleartextConsentRecorded = true,
            cleartextConsentOrigin = origin,
        )
        val secrets = ConnectionSecrets(sessionToken = storedToken)
        val snapshot = ConnectionSnapshot.from(profile, secrets)
        every { store.snapshotFor("test-conn") } returns snapshot
        return snapshot
    }

    @Test
    fun `discovered loopback token is synced into the store when it differs`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"auth_required": false}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """<html><script>window.__HERMES_SESSION_TOKEN__="fresh-token";</script></html>""",
            ),
        )
        val snapshot = snapshot(storedToken = "stale-token")

        val auth = helper.authQueryParam(snapshot)

        assertEquals("token=fresh-token", auth)
        verify { store.updateSessionToken("test-conn", "fresh-token") }
    }

    @Test
    fun `B20 rotation returns the refreshed snapshot with the auth query`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"auth_required": false}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """<html><script>window.__HERMES_SESSION_TOKEN__="fresh-token";</script></html>""",
            ),
        )
        val snapshot = snapshot(storedToken = "stale-token")
        // The store hands back a NEW snapshot after the token write — exactly
        // what a real SecureConnectionStore does (the CAS bumped its revision).
        var written = false
        val refreshed = snapshot(storedToken = "fresh-token")
        // Pre-write calls all observe the original snapshot: (1) requireCurrent,
        // (2) REST auth interceptor on getStatus, (3)+(4) SnapshotOriginInterceptor
        // request+response hop checks. Everything after the token write (the
        // B20 re-read) observes the refreshed snapshot.
        // Every read BEFORE the token write observes the original snapshot;
        // every read AFTER it observes the refreshed one. Rather than pin the
        // exact call count (interceptor internals may add hop checks), key the
        // switch on the token write completing.
        every { store.updateSessionToken("test-conn", "fresh-token") } answers {
            written = true
        }
        every { store.snapshotFor("test-conn") } answers {
            if (written) refreshed else snapshot
        }

        val result = helper.authQueryWithSnapshot(snapshot)

        assertEquals("token=fresh-token", result.query)
        // The handshake must bind to the refreshed snapshot, not the stale one.
        assertEquals("fresh-token", result.snapshot.secrets.sessionToken)
        verify { store.updateSessionToken("test-conn", "fresh-token") }
    }

    @Test
    fun `matching stored token is not rewritten`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"auth_required": false}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """<html><script>window.__HERMES_SESSION_TOKEN__="same-token";</script></html>""",
            ),
        )
        val snapshot = snapshot(storedToken = "same-token")

        val auth = helper.authQueryParam(snapshot)

        assertEquals("token=same-token", auth)
        verify(exactly = 0) { store.updateSessionToken(any(), any()) }
    }

    @Test
    fun `no stored token yet also syncs the discovered token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"auth_required": false}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """<html><script>window.__HERMES_SESSION_TOKEN__="brand-new";</script></html>""",
            ),
        )
        val snapshot = snapshot(storedToken = null)

        val auth = helper.authQueryParam(snapshot)

        assertEquals("token=brand-new", auth)
        verify { store.updateSessionToken("test-conn", "brand-new") }
    }

    @Test
    fun `unavailable SPA shell falls back to the stored token without syncing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"auth_required": false}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        val snapshot = snapshot(storedToken = "kept-token")

        val auth = helper.authQueryParam(snapshot)

        assertEquals("token=kept-token", auth)
        verify(exactly = 0) { store.updateSessionToken(any(), any()) }
    }

    @Test
    fun `gated dashboards use tickets and never touch the loopback sync`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"auth_required": true}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"ticket": "one-time-ticket"}"""),
        )
        val snapshot = snapshot(storedToken = "stale-token")

        val auth = helper.authQueryParam(snapshot)

        assertEquals("ticket=one-time-ticket", auth)
        verify(exactly = 0) { store.updateSessionToken(any(), any()) }
    }

    @Test
    fun `required ticket failure is propagated and the next mint can succeed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"auth_required": true}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("temporary outage"))
        val snapshot = snapshot(storedToken = "stale-token")

        assertThrows(WsTicketException::class.java) {
            runBlocking { helper.authQueryParam(snapshot) }
        }

        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"ticket": "retry-ticket"}"""),
        )
        assertEquals("ticket=retry-ticket", helper.authQueryParam(snapshot))
        verify(exactly = 0) { store.updateSessionToken(any(), any()) }
    }
}
