/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */

package com.hermesgadget.talaria.feature.capture

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.feature.manage.files.ShareFileManager
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Share capture draft/delivery regression net (Wave 5 lows).
 *
 * Deterministic by construction: Main is installed sharing runTest's scheduler and the
 * ViewModel's IO work runs on StandardTestDispatcher(testScheduler), drained by a
 * real-paced awaitState (runCurrent + real delay — in-flight real Robolectric IO needs
 * real time). Main is deliberately NOT reset afterwards: a late resume from an in-flight
 * real-IO hop then dispatches into the dead scheduler instead of throwing the
 * unset-Main IllegalStateException that poisons the next runTest class
 * (cross-test UncaughtExceptionsBeforeTest flake).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareCaptureViewModelTest {

    @Test
    fun newShareIsRejectedWhileDeliveryIsSuspended() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dependencies = dependencies(context)
        val snapshot = ConnectionSnapshot.anonymous()
        val draft = draft(snapshot, text = "original")
        val delivery = GatedDelivery()
        val viewModel = ShareCaptureViewModel(
            snapshotOverride = snapshot,
            deliveryOverride = delivery,
            initialDraft = draft,
            activeSnapshotProvider = { snapshot },
            dependencies = dependencies,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.send()
        awaitState { delivery.started.isCompleted }

        viewModel.acceptIntent(
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "new share"),
        )

        assertEquals("original", viewModel.ui.value.text)
        assertTrue(viewModel.ui.value.error?.contains("not merged") == true)

        delivery.completion.complete(Result.success("session"))
        awaitState { viewModel.ui.value.completed }
    }

    @Test
    fun failedDeliveryRetainsDraftAndAllowsRetryAfterFailure() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dependencies = dependencies(context)
        val snapshot = ConnectionSnapshot.anonymous()
        val draft = draft(snapshot, text = "original")
        val delivery = GatedDelivery()
        val viewModel = ShareCaptureViewModel(
            snapshotOverride = snapshot,
            deliveryOverride = delivery,
            initialDraft = draft,
            activeSnapshotProvider = { snapshot },
            dependencies = dependencies,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.send()
        awaitState { delivery.started.isCompleted }
        viewModel.acceptIntent(
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "new share"),
        )
        delivery.completion.complete(Result.failure(IllegalStateException("offline")))

        awaitState { viewModel.ui.value.deliveryState == ShareDeliveryUiState.IDLE }
        assertEquals("original", viewModel.ui.value.text)
        assertTrue(viewModel.ui.value.error?.contains("offline") == true)
    }

    private fun dependencies(context: Context): ShareCaptureDependencies =
        ShareCaptureDependencies(
            contentResolver = context.contentResolver,
            fileManager = ShareFileManager(context.cacheDir.resolve("share-capture-vm-tests")),
            store = ShareIntakeStore(context),
        )

    private suspend fun TestScope.awaitState(condition: () -> Boolean) {
        // Real-time paced so in-flight real IO (Robolectric prefs, file staging) can
        // complete; runCurrent() drains scheduler-queued VM continuations without
        // advancing virtual time (advanceUntilIdle would fire the withTimeout timer).
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                while (!condition()) {
                    testScheduler.runCurrent()
                    delay(1L)
                }
            }
        }
    }

    private fun draft(snapshot: ConnectionSnapshot, text: String): ShareIntakeDraft =
        ShareIntakeDraft(
            scopeId = snapshot.scopeId,
            connectionId = snapshot.connectionId,
            managementProfile = snapshot.managementProfile,
            text = text,
            createdAt = 1L,
            updatedAt = 1L,
        )

    private class GatedDelivery : ShareDelivery {
        val started = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<Result<String>>()
        private val deliveredSnapshot = AtomicReference<ConnectionSnapshot?>(null)

        override suspend fun deliver(
            snapshot: ConnectionSnapshot,
            draft: ShareIntakeDraft,
        ): String {
            deliveredSnapshot.set(snapshot)
            started.complete(Unit)
            return completion.await().getOrThrow()
        }
    }
}
