package com.hermesgadget.talaria.worker

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.HermesClientFactory
import com.hermesgadget.talaria.di.AppContainer
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingApproveWorkerSnapshotTest {
    @Test
    fun approvalUsesThePendingRequestSnapshotAfterTheForegroundSwitches() = runBlocking {
        val snapshotA = snapshot("connection-a", "https://a.example", "profile-a")
        val factory = mockk<HermesClientFactory>()
        val repository = mockk<HermesRepository>()
        val container = mockk<AppContainer>()
        val app = mockk<TalariaApp>()
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)

        every { params.inputData } returns workDataOf(
            PairingApproveWorker.KEY_PLATFORM to "telegram",
            PairingApproveWorker.KEY_CODE to "code-a",
            PairingApproveWorker.KEY_CONNECTION_ID to "connection-a",
            PairingApproveWorker.KEY_MANAGEMENT_PROFILE to "profile-a",
        )
        mockkObject(TalariaApp.Companion)
        every { app.container } returns container
        every { container.clientFactory } returns factory
        every { container.hermesRepository } returns repository
        every { TalariaApp.instance } returns app
        every { factory.snapshotFor("connection-a", "profile-a") } returns snapshotA
        every { factory.snapshot() } returns snapshot("connection-b", "https://b.example", "profile-b")
        coEvery { repository.approvePairing("telegram", "code-a", snapshotA) } returns
            Result.success(buildJsonObject { })
        coEvery {
            repository.recordActivity(
                "pairing",
                "Pairing approved",
                "telegram approved from notification",
                snapshotA,
            )
        } just runs

        try {
            assertEquals(androidx.work.ListenableWorker.Result.success(), PairingApproveWorker(context, params).doWork())
            coVerify(exactly = 1) { repository.approvePairing("telegram", "code-a", snapshotA) }
            coVerify(exactly = 1) {
                repository.recordActivity(
                    "pairing",
                    "Pairing approved",
                    "telegram approved from notification",
                    snapshotA,
                )
            }
            verify(exactly = 0) { factory.snapshot() }
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
