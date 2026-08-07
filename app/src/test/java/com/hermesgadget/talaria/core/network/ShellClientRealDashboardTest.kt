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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Integration test: the real shellClient() against the REAL dashboard on
 * the host (127.0.0.1:9119). Proves the full chain — consent gate,
 * Host header, HTTP GET, regex extraction — works against a live
 * dashboard, independent of emulator networking.
 *
 * CI-safe: skips (assumption failure) when no dashboard is listening on
 * the host loopback, so runners without the local dashboard stay green.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShellClientRealDashboardTest {

    @Test
    fun `shell client fetches token from real dashboard`() = runBlocking {
        assumeTrue(
            "local Hermes dashboard not reachable on 127.0.0.1:9119 — skipping",
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", 9119), 500)
                    socket.isConnected
                }
            } catch (e: Exception) {
                false
            },
        )
        val base = "http://127.0.0.1:9119"
        val origin = ConnectionOrigin.normalize(base)!!
        val profile = ConnectionProfile(
            id = "real-dashboard-test",
            name = "Test",
            baseUrl = base,
            authMode = AuthMode.SESSION_TOKEN,
            managementProfile = "",
            allowCleartext = true,
            cleartextConsentRecorded = true,
            cleartextConsentOrigin = origin,
        )
        val snapshot = ConnectionSnapshot.from(profile, ConnectionSecrets(sessionToken = null))

        // Build a real factory with stub stores (shellClient only needs the
        // snapshot; the store is never consulted).
        val ctx: android.content.Context =
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        val store = SecureConnectionStore(ctx)
        val settings = SettingsStore(ctx)
        val factory = HermesClientFactory(store, settings)

        val client = factory.shellClient(snapshot)
        val request = okhttp3.Request.Builder()
            .url("$base/")
            .header("Accept", "text/html")
            .get()
            .build()

        client.newCall(request).execute().use { resp ->
            println("HTTP ${resp.code}")
            assertTrue("dashboard should answer 200, got ${resp.code}", resp.isSuccessful)
            val body = resp.body?.string().orEmpty()
            println("body length: ${body.length}")
            val token: String? = Regex("""__HERMES_SESSION_TOKEN__\s*=\s*["']([^"']+)["']""")
                .find(body)?.groupValues?.getOrNull(1)
            println("token: ${token?.take(4)}…")
            assertNotNull("SPA shell must contain a session token", token)
            assertTrue(token!!.isNotBlank())
            assertEquals("dashboard serves the local token", "local", token)
        }
    }
}
