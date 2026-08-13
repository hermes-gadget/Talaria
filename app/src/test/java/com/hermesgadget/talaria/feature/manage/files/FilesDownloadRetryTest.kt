/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */

package com.hermesgadget.talaria.feature.manage.files

import android.content.ContentResolver
import android.net.Uri
import com.hermesgadget.talaria.core.network.ConnectionScope
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.domain.model.ManagedFileEntry
import com.hermesgadget.talaria.domain.model.ManagedFilesListResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Download/save/retry regression net (Wave 5 lows).
 *
 * Deterministic by construction: Main is installed sharing runTest's scheduler and the
 * ViewModel's IO work runs on StandardTestDispatcher(testScheduler), drained by a
 * real-paced awaitState (runCurrent + real delay — in-flight real file IO needs real
 * time). Main is deliberately NOT reset afterwards: a late resume from an in-flight
 * real-IO hop then dispatches into the dead scheduler instead of throwing the
 * unset-Main IllegalStateException that poisons the next runTest class
 * (cross-test UncaughtExceptionsBeforeTest flake).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilesDownloadRetryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun failedSaveRetainsThePayloadForRetry() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val api = mockk<HermesApi>()
        coEvery { api.downloadManagedFile("/report.txt", any()) } returns
            "downloaded content".toResponseBody()
        val manager = ShareFileManager(tempFolder.root)
        val scopeFlow = MutableStateFlow<ConnectionScope?>(
            ConnectionScope(ConnectionSnapshot.anonymous(), generation = 1L),
        )
        coEvery { api.listManagedFiles(any(), any()) } returns ManagedFilesListResponse()
        val viewModel = FilesViewModel(
            api = api,
            cacheDirectory = tempFolder.root,
            shareFileManager = manager,
            scopeFlow = scopeFlow,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val entry = ManagedFileEntry(name = "report.txt", path = "/report.txt", size = 18L)
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        every { resolver.openOutputStream(uri) } throws IOException("destination unavailable")

        viewModel.download(entry)
        awaitState(viewModel) { it.downloadState is FileDownloadState.Ready }

        viewModel.saveDownload(uri, resolver)
        awaitState(viewModel) { it.downloadState is FileDownloadState.Failed }

        val failed = viewModel.ui.value.downloadState as FileDownloadState.Failed
        val failedFile = checkNotNull(failed.file)
        assertTrue(failedFile.exists())

        val destination = File(tempFolder.root, "saved-report.txt")
        every { resolver.openOutputStream(uri) } returns FileOutputStream(destination)
        viewModel.retrySaveDownload(uri, resolver)
        awaitState(viewModel) { it.downloadState is FileDownloadState.Complete }

        assertEquals("downloaded content", destination.readText(StandardCharsets.UTF_8))
        assertFalse(failedFile.exists())
    }

    private suspend fun TestScope.awaitState(
        viewModel: FilesViewModel,
        predicate: (FilesUiState) -> Boolean,
    ) {
        // Real-time paced so in-flight real IO (file writes, mocked resolvers) can
        // complete; runCurrent() drains scheduler-queued VM continuations without
        // advancing virtual time (advanceUntilIdle would fire the withTimeout timer).
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                while (!predicate(viewModel.ui.value)) {
                    testScheduler.runCurrent()
                    delay(1L)
                }
            }
        }
    }
}
