/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */

package com.hermesgadget.talaria.feature.capture

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.feature.manage.files.ShareFileManager
import com.hermesgadget.talaria.util.MainDispatcherRule
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareCaptureViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun newShareIsRejectedWhileDeliveryIsSuspended() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dependencies = dependencies(context)
        try {
            val snapshot = ConnectionSnapshot.anonymous()
            val draft = draft(snapshot, text = "original")
            val delivery = GatedDelivery()
            val viewModel = ShareCaptureViewModel(
                snapshotOverride = snapshot,
                deliveryOverride = delivery,
                initialDraft = draft,
                activeSnapshotProvider = { snapshot },
                dependencies = dependencies,
            )

            viewModel.send()
            awaitReal { delivery.started.await() }

            viewModel.acceptIntent(
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "new share"),
            )

            assertEquals("original", viewModel.ui.value.text)
            assertTrue(viewModel.ui.value.error?.contains("not merged") == true)

            delivery.completion.complete(Result.success("session"))
            awaitReal { while (!viewModel.ui.value.completed) delay(1L) }
        } finally {
            dependencies.fileManager.cleanupStaleFiles()
        }
    }

    @Test
    fun failedDeliveryRetainsDraftAndAllowsRetryAfterFailure() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dependencies = dependencies(context)
        try {
            val snapshot = ConnectionSnapshot.anonymous()
            val draft = draft(snapshot, text = "original")
            val delivery = GatedDelivery()
            val viewModel = ShareCaptureViewModel(
                snapshotOverride = snapshot,
                deliveryOverride = delivery,
                initialDraft = draft,
                activeSnapshotProvider = { snapshot },
                dependencies = dependencies,
            )

            viewModel.send()
            awaitReal { delivery.started.await() }
            viewModel.acceptIntent(
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "new share"),
            )
            delivery.completion.complete(Result.failure(IllegalStateException("offline")))

            awaitReal {
                while (viewModel.ui.value.deliveryState != ShareDeliveryUiState.IDLE) delay(1L)
            }
            assertEquals("original", viewModel.ui.value.text)
            assertTrue(viewModel.ui.value.error?.contains("offline") == true)
        } finally {
            dependencies.fileManager.cleanupStaleFiles()
        }
    }

    private fun dependencies(context: Context): ShareCaptureDependencies =
        ShareCaptureDependencies(
            contentResolver = context.contentResolver,
            fileManager = ShareFileManager(context.cacheDir.resolve("share-capture-vm-tests")),
            store = ShareIntakeStore(context),
        )

    private suspend fun awaitReal(block: suspend () -> Unit) {
        withContext(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT_MILLIS) { block() }
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

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
