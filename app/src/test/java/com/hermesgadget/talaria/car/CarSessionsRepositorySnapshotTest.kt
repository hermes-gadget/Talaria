package com.hermesgadget.talaria.car

import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.core.network.HermesClientFactory
import com.hermesgadget.talaria.di.AppContainer
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import com.hermesgadget.talaria.domain.model.SessionMessagesResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class CarSessionsRepositorySnapshotTest {
    @Test
    fun readsStayOnTheCapturedSnapshotAfterTheActiveConnectionSwitches() = runBlocking {
        val snapshotA = snapshot("connection-a", "https://a.example", "profile-a")
        val snapshotB = snapshot("connection-b", "https://b.example", "profile-b")
        val apiA = mockk<HermesApi>()
        val apiB = mockk<HermesApi>()
        val factory = mockk<HermesClientFactory>()
        val container = mockk<AppContainer>()
        val app = mockk<TalariaApp>()
        var active = snapshotA

        every { app.container } returns container
        every { container.clientFactory } returns factory
        every { factory.api(snapshotA) } returns apiA
        every { factory.api(snapshotB) } returns apiB
        coEvery {
            apiA.getSessionsForProfile(
                profile = "profile-a",
                limit = 100,
                offset = 0,
                order = "recent",
            )
        } coAnswers {
            active = snapshotB
            buildJsonArray { add(buildJsonObject { put("id", "session-a") }) }
        }
        coEvery { apiA.getSessionMessages("session-a", profile = "profile-a") } coAnswers {
            active = snapshotB
            SessionMessagesResponse()
        }

        mockkObject(TalariaApp.Companion)
        every { TalariaApp.instance } returns app
        try {
            assertEquals(1, CarSessionsRepository.activeSessions(snapshotA).getOrThrow().size)
            assertEquals(0, CarSessionsRepository.messages(snapshotA, "session-a").getOrThrow().size)
            assertEquals(snapshotB, active)
            coVerify(exactly = 1) {
                apiA.getSessionsForProfile(
                    profile = "profile-a",
                    limit = 100,
                    offset = 0,
                    order = "recent",
                )
            }
            coVerify(exactly = 1) { apiA.getSessionMessages("session-a", profile = "profile-a") }
            verify(exactly = 0) { factory.api(snapshotB) }
        } finally {
            unmockkObject(TalariaApp.Companion)
        }
    }

    private fun snapshot(id: String, baseUrl: String, managementProfile: String): ConnectionSnapshot =
        ConnectionSnapshot(
            profile = ConnectionProfile(
                id = id,
                name = id,
                baseUrl = baseUrl,
                authMode = AuthMode.NONE,
                managementProfile = managementProfile,
            ),
            secrets = ConnectionSecrets(),
        )
}
