package com.hermesgadget.talaria.core.network

import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class SnapshotCredentialHelpersTest {
    @Test
    fun passwordBootstrapDoesNotForwardCredentialsAcross307Or308() {
        listOf(307, 308).forEach { code ->
            assertPasswordRedirectIsContained(code)
        }
    }

    @Test
    fun oidcRefreshDoesNotForwardRefreshTokenAcross307Or308() {
        listOf(307, 308).forEach { code ->
            val first = MockWebServer()
            val second = MockWebServer()
            first.start()
            second.start()
            try {
                first.enqueue(
                    MockResponse()
                        .setResponseCode(code)
                        .setHeader("Location", second.url("/stolen")),
                )
                val saved = snapshot(
                    first.url("/").toString(),
                    authMode = AuthMode.OIDC_BROWSER,
                    bearerToken = "expired-access",
                    refreshToken = "refresh-secret",
                    expiresAt = 0,
                )
                val store = mockk<SecureConnectionStore>()
                every { store.snapshotFor(saved.connectionId) } returns saved

                assertNull(SnapshotOidcTokenRefresher(store, nowSeconds = { 1_000 }).accessToken(saved))
                assertTrue(
                    first.takeRequest(5, TimeUnit.SECONDS)?.body?.readUtf8()?.contains("refresh-secret") == true,
                )
                assertEquals(0, second.requestCount)
            } finally {
                first.shutdown()
                second.shutdown()
            }
        }
    }

    @Test
    fun oidcRefreshReturnsNoTokenWhenSnapshotCasRejectsTheResponse() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"access_token":"new-access","refresh_token":"new-refresh","expires_at":5000,"provider":"oidc"}""",
                ),
        )
        server.start()
        try {
            val saved = snapshot(
                server.url("/").toString(),
                authMode = AuthMode.OIDC_BROWSER,
                bearerToken = "expired-access",
                refreshToken = "refresh-secret",
                expiresAt = 0,
            )
            val store = mockk<SecureConnectionStore>()
            every { store.snapshotFor(saved.connectionId) } returns saved
            every {
                store.updateOidcTokensIfSnapshot(any(), any(), any(), any(), any())
            } returns false

            assertNull(SnapshotOidcTokenRefresher(store, nowSeconds = { 1_000 }).accessToken(saved))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun passwordBootstrapStopsBeforeTransmissionWhenSnapshotChangesAtFinalCheck() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        try {
            val saved = snapshot(
                server.url("/").toString(),
                authMode = AuthMode.BASIC,
                password = "password-secret",
            )
            val changed = saved.copy(profile = saved.profile.copy(id = "connection-b"))
            var checks = 0
            val manager = SnapshotPasswordSessionManager(
                saved,
                PersistentCookieJar(),
                currentSnapshot = {
                    checks += 1
                    if (checks >= 3) changed else saved
                },
            )

            assertThrows(IOException::class.java) { manager.ensureSession(server.url("/")) }
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun oidcRefreshStopsBeforeTransmissionWhenSnapshotChangesAtFinalCheck() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        try {
            val saved = snapshot(
                server.url("/").toString(),
                authMode = AuthMode.OIDC_BROWSER,
                bearerToken = "expired-access",
                refreshToken = "refresh-secret",
                expiresAt = 0,
            )
            val changed = saved.copy(profile = saved.profile.copy(id = "connection-b"))
            val store = mockk<SecureConnectionStore>()
            var checks = 0
            every { store.snapshotFor(saved.connectionId) } answers {
                checks += 1
                if (checks >= 2) changed else saved
            }

            assertThrows(IOException::class.java) {
                SnapshotOidcTokenRefresher(store, nowSeconds = { 1_000 }).accessToken(saved)
            }
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun originGuardFailsClosedWhenTheSavedSnapshotDisappears() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        try {
            val saved = snapshot(
                server.url("/").toString(),
                authMode = AuthMode.BEARER,
                bearerToken = "access-token",
            )
            val client = OkHttpClient.Builder()
                .addInterceptor(SnapshotOriginInterceptor(saved) { null })
                .build()

            assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(server.url("/api/status")).build()).execute()
            }
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun authInterceptorChecksTheExactSnapshotImmediatelyBeforeEveryAuthModeTransmission() {
        AuthMode.entries.forEach { mode ->
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(200))
            server.start()
            try {
                val saved = snapshot(
                    server.url("/").toString(),
                    authMode = mode,
                    password = if (mode == AuthMode.BASIC) "password-secret" else null,
                    bearerToken = if (mode == AuthMode.BEARER || mode == AuthMode.OIDC_BROWSER) {
                        "access-token"
                    } else {
                        null
                    },
                    refreshToken = if (mode == AuthMode.OIDC_BROWSER) "refresh-secret" else null,
                    expiresAt = if (mode == AuthMode.OIDC_BROWSER) 9_999_999 else null,
                )
                val changed = saved.copy(profile = saved.profile.copy(id = "connection-b"))
                val store = mockk<SecureConnectionStore>()
                var checks = 0
                every { store.snapshotFor(saved.connectionId) } answers {
                    checks += 1
                    if (checks >= 2) changed else saved
                }
                val client = OkHttpClient.Builder()
                    .addInterceptor(
                        AuthInterceptor(
                            snapshot = saved,
                            connectionStore = store,
                            oidcTokenRefresher = { "refreshed-access" },
                            passwordSessionManager = { _, _ -> },
                        ),
                    )
                    .build()

                assertThrows(IOException::class.java) {
                    client.newCall(Request.Builder().url(server.url("/api/status")).build()).execute()
                }
                assertEquals(mode.name, 0, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    private fun assertPasswordRedirectIsContained(code: Int) {
        val first = MockWebServer()
        val second = MockWebServer()
        first.start()
        second.start()
        try {
            first.enqueue(
                MockResponse()
                    .setResponseCode(code)
                    .setHeader("Location", second.url("/stolen")),
            )
            val saved = snapshot(
                first.url("/").toString(),
                authMode = AuthMode.BASIC,
                password = "password-secret",
            )
            assertThrows(IOException::class.java) {
                SnapshotPasswordSessionManager(saved, PersistentCookieJar())
                    .ensureSession(first.url("/"))
            }
            assertTrue(
                first.takeRequest(5, TimeUnit.SECONDS)?.body?.readUtf8()?.contains("password-secret") == true,
            )
            assertEquals(0, second.requestCount)
        } finally {
            first.shutdown()
            second.shutdown()
        }
    }

    private fun snapshot(
        baseUrl: String,
        authMode: AuthMode,
        password: String? = null,
        bearerToken: String? = null,
        refreshToken: String? = null,
        expiresAt: Long? = null,
    ): ConnectionSnapshot {
        val origin = ConnectionOrigin.normalize(baseUrl)
        return ConnectionSnapshot(
            profile = ConnectionProfile(
                id = "connection-a",
                name = "A",
                baseUrl = baseUrl,
                authMode = authMode,
                username = "alice".takeIf { password != null },
                authProvider = if (password != null) "password" else "oidc",
                hasPassword = password != null,
                hasBearerToken = bearerToken != null,
                cleartextConsentRecorded = true,
                cleartextConsentOrigin = origin,
            ),
            secrets = ConnectionSecrets(
                password = password,
                bearerToken = bearerToken,
                oidcRefreshToken = refreshToken,
                oidcExpiresAt = expiresAt,
                oidcProvider = "oidc".takeIf { refreshToken != null },
            ),
        )
    }
}
