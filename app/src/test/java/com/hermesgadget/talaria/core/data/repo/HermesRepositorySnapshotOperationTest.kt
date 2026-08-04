package com.hermesgadget.talaria.core.data.repo

import com.hermesgadget.talaria.core.data.db.TalariaDatabase
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.core.network.HermesClientFactory
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import com.hermesgadget.talaria.domain.model.FsTextFile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HermesRepositorySnapshotOperationTest {
    @Test
    fun fsWriteUsesTheCapturedApiForReadWriteAndRereadAfterConnectionSwitch() = runBlocking {
        val snapshotA = snapshot("connection-a", "https://a.example", "profile-a")
        val snapshotB = snapshot("connection-b", "https://b.example", "profile-b")
        val apiA = mockk<HermesApi>()
        val apiB = mockk<HermesApi>()
        val factory = mockk<HermesClientFactory>()
        val database = mockk<TalariaDatabase>()
        val store = mockk<SecureConnectionStore>()
        var active = snapshotA
        var readCount = 0

        every { factory.snapshot() } answers { active }
        every { factory.api(snapshotA) } returns apiA
        every { factory.api(snapshotB) } returns apiB
        coEvery { apiA.fsReadText("notes.md", profile = "profile-a") } coAnswers {
            readCount += 1
            if (readCount == 1) {
                active = snapshotB
                FsTextFile(path = "notes.md", text = "old")
            } else {
                FsTextFile(path = "notes.md", text = "new")
            }
        }
        coEvery {
            apiA.fsWriteText(any(), profile = "profile-a")
        } returns buildJsonObject { }

        val result = HermesRepository(factory, database, store).fsWriteText(
            path = "notes.md",
            content = "new",
            expectedOriginal = "old",
        )

        assertEquals("new", result.getOrThrow().text)
        verify(exactly = 1) { factory.api(snapshotA) }
        verify(exactly = 0) { factory.api(snapshotB) }
        coVerify(exactly = 2) { apiA.fsReadText("notes.md", profile = "profile-a") }
        coVerify(exactly = 1) { apiA.fsWriteText(any(), profile = "profile-a") }
    }

    @Test
    fun learningMutationUsesTheCapturedApiForMutationAndReread() = runBlocking {
        val snapshotA = snapshot("connection-a", "https://a.example", "profile-a")
        val snapshotB = snapshot("connection-b", "https://b.example", "profile-b")
        val apiA = mockk<HermesApi>()
        val apiB = mockk<HermesApi>()
        val factory = mockk<HermesClientFactory>()
        val database = mockk<TalariaDatabase>()
        val store = mockk<SecureConnectionStore>()
        var active = snapshotA
        val graph = com.hermesgadget.talaria.domain.model.LearningGraph()

        every { factory.snapshot() } answers { active }
        every { factory.api(snapshotA) } returns apiA
        every { factory.api(snapshotB) } returns apiB
        coEvery {
            apiA.updateLearningNode(any(), profile = "profile-a")
        } coAnswers {
            active = snapshotB
            buildJsonObject { }
        }
        coEvery { apiA.getLearningGraph(profile = "profile-a") } returns graph

        val result = HermesRepository(factory, database, store).updateLearningNode("node-a", "new")

        assertSame(graph, result.getOrThrow())
        verify(exactly = 1) { factory.api(snapshotA) }
        verify(exactly = 0) { factory.api(snapshotB) }
        coVerify(exactly = 1) { apiA.updateLearningNode(any(), profile = "profile-a") }
        coVerify(exactly = 1) { apiA.getLearningGraph(profile = "profile-a") }
    }

    @Test
    fun actionPollingUsesTheCapturedApiAfterTheConnectionSwitches() = runBlocking {
        val snapshotA = snapshot("connection-a", "https://a.example", "profile-a")
        val snapshotB = snapshot("connection-b", "https://b.example", "profile-b")
        val apiA = mockk<HermesApi>()
        val apiB = mockk<HermesApi>()
        val factory = mockk<HermesClientFactory>()
        val database = mockk<TalariaDatabase>()
        val store = mockk<SecureConnectionStore>()
        var active = snapshotA

        every { factory.snapshot() } answers { active }
        every { factory.api(snapshotA) } returns apiA
        every { factory.api(snapshotB) } returns apiB
        coEvery { apiA.gatewayRestart() } returns buildJsonObject { put("name", "restart-a") }
        coEvery { apiA.getActionStatus("restart-a", lines = 200) } coAnswers {
            active = snapshotB
            com.hermesgadget.talaria.domain.model.ActionStatus(
                name = "restart-a",
                running = false,
                exit_code = 0,
            )
        }

        val result = HermesRepository(factory, database, store).gateway("restart")

        assertEquals(0, result.getOrThrow().exit_code)
        verify(exactly = 1) { factory.api(snapshotA) }
        verify(exactly = 0) { factory.api(snapshotB) }
        coVerify(exactly = 1) { apiA.gatewayRestart() }
        coVerify(exactly = 1) { apiA.getActionStatus("restart-a", lines = 200) }
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
