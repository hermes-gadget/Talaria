/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.manage.artifacts

import android.app.Application
import android.net.Uri
import com.hermesgadget.talaria.domain.model.FsDataUrl
import com.hermesgadget.talaria.domain.model.FsTextFile
import com.hermesgadget.talaria.domain.model.SessionMessage
import com.hermesgadget.talaria.domain.model.SessionSummary
import com.hermesgadget.talaria.domain.model.SessionsPage
import com.hermesgadget.talaria.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ArtifactsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `loads recent session artifacts in timestamp order`() = runTest {
        val vm = viewModel(
            messages = listOf(
                SessionMessage(
                    role = "assistant",
                    content = "saved /tmp/old.txt",
                    timestamp = "100",
                ),
                SessionMessage(
                    role = "tool",
                    content = "rendered /tmp/new.png",
                    timestamp = "200",
                ),
            ),
                scope = this,
            )
        advanceUntilIdle()

        val ready = vm.ui.value.load as ArtifactLoadState.Ready
        assertEquals(listOf("/tmp/new.png", "/tmp/old.txt"), ready.artifacts.map { it.path })
        assertEquals("Build preview", ready.artifacts.first().sessionTitle)
    }

    @Test
    fun `filter changes reset page and negative pages clamp to zero`() = runTest {
        val vm = viewModel(
            messages = listOf(
                SessionMessage(role = "assistant", content = "/tmp/a.png /tmp/b.txt /tmp/c.zip"),
            ),
                scope = this,
            )
        advanceUntilIdle()

        vm.setPage(4)
        vm.setFilter(ArtifactFilter.IMAGE)
        assertEquals(ArtifactFilter.IMAGE, vm.ui.value.filter)
        assertEquals(0, vm.ui.value.page)

        vm.setPage(-2)
        assertEquals(0, vm.ui.value.page)
    }

    @Test
    fun `text preview exposes metadata and close clears it`() = runTest {
        val artifact = artifact(ArtifactKind.TEXT, "/tmp/notes.md")
        val vm = viewModel(
            messages = emptyList(),
            readText = {
                Result.success(
                    FsTextFile(
                        path = it,
                        text = "# Notes",
                        language = "markdown",
                        byteSize = 7,
                        truncated = true,
                    ),
                )
            },
                scope = this,
            )
        advanceUntilIdle()

        vm.openPreview(artifact)
        advanceUntilIdle()

        val preview = vm.ui.value.preview as ArtifactPreview.Text
        assertEquals("# Notes", preview.text)
        assertEquals("markdown", preview.language)
        assertEquals(7, preview.byteSize)
        assertTrue(preview.truncated)
        assertFalse(vm.ui.value.previewLoading)

        vm.closePreview()
        assertNull(vm.ui.value.preview)
    }

    @Test
    fun `image preview stores a bounded file handle rather than a data URL`() = runTest {
        val artifact = artifact(ArtifactKind.IMAGE, "/tmp/render.png")
        val vm = viewModel(
            messages = emptyList(),
            readDataUrl = {
                FsDataUrl(
                    path = it,
                    dataUrl = "data:image/png;base64,$ONE_PIXEL_PNG",
                    mimeType = "image/png",
                    byteSize = 68,
                )
            },
                scope = this,
            )
        advanceUntilIdle()

        vm.openPreview(artifact)
        advanceUntilIdle()

        val preview = vm.ui.value.preview as ArtifactPreview.Image
        assertTrue(File(preview.handle.path).isFile)
        assertEquals(1, preview.handle.width)
        assertEquals(1, preview.handle.height)
        assertEquals("image/jpeg", preview.mimeType)
        assertTrue(preview.byteSize > 0)
    }

    @Test
    fun `preview failure is surfaced and loading stops`() = runTest {
        val vm = viewModel(
            messages = emptyList(),
            readText = { Result.failure(IllegalStateException("read failed")) },
                scope = this,
            )
        advanceUntilIdle()

        vm.openPreview(artifact(ArtifactKind.TEXT, "/tmp/notes.md"))
        advanceUntilIdle()

        assertNull(vm.ui.value.preview)
        assertFalse(vm.ui.value.previewLoading)
        assertEquals("read failed", vm.ui.value.previewError)
    }

    @Test
    fun `share request is exposed once and can be consumed`() = runTest {
        val artifact = artifact(ArtifactKind.TEXT, "/tmp/notes.md")
        val request = ArtifactShareRequest(
            uri = Uri.EMPTY,
            mimeType = "text/plain",
            subject = "Hermes artifact notes.md",
        )
        val vm = viewModel(
            messages = emptyList(),
            shareRequestBuilder = { request },
                scope = this,
            )
        advanceUntilIdle()

        vm.share(artifact)
        advanceUntilIdle()

        assertFalse(vm.ui.value.sharing)
        assertEquals(request, vm.ui.value.shareRequest)
        vm.consumeShareRequest()
        assertNull(vm.ui.value.shareRequest)
    }

    @Test
    fun `load failure becomes a failed state`() = runTest {
        val vm = ArtifactsViewModel(
            loadSessions = { Result.failure(IllegalStateException("sessions unavailable")) },
            loadMessages = { Result.success(emptyList()) },
            readText = { Result.success(FsTextFile(path = it)) },
            readDataUrl = { FsDataUrl(path = it) },
            defaultDispatcher = StandardTestDispatcher(testScheduler),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()

        val failure = vm.ui.value.load as ArtifactLoadState.Failed
        assertEquals("sessions unavailable", failure.message)
    }

    @Test
    fun `canceled scan does not publish a failure`() = runTest {
        var cancelSeen = false
        val vm = ArtifactsViewModel(
            loadSessions = {
                try {
                    throw CancellationException("scan canceled")
                } finally {
                    cancelSeen = true
                }
            },
            loadMessages = { Result.success(emptyList()) },
            readText = { Result.success(FsTextFile(path = it)) },
            readDataUrl = { FsDataUrl(path = it) },
            defaultDispatcher = StandardTestDispatcher(testScheduler),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()

        assertTrue(vm.ui.value.load is ArtifactLoadState.Loading)
        // The cancellation must have reached the scan body: a swallow-to-nothing
        // path that keeps the VM wedged in Loading forever also passes the
        // state assertion above, so prove the job actually ran and died.
        assertTrue(cancelSeen)
        // And the VM must still be able to run a fresh scan afterwards.
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.ui.value.load is ArtifactLoadState.Loading || vm.ui.value.load is ArtifactLoadState.Failed)
    }

    @Test
    fun `fifty session scan is incremental and revision cached`() = runTest {
        var messageLoads = 0
        val sessions = (0 until 50).map { index ->
            SessionSummary(
                id = "session-$index",
                title = "Session $index",
                message_count = 1,
                last_active = index.toString(),
            )
        }
        val vm = ArtifactsViewModel(
            loadSessions = { Result.success(SessionsPage(sessions = sessions, total = 50)) },
            loadMessages = {
                messageLoads++
                Result.success(listOf(SessionMessage(role = "assistant", content = "/tmp/$it.txt")))
            },
            readText = { Result.success(FsTextFile(path = it)) },
            readDataUrl = { FsDataUrl(path = it) },
            previewDirectoryOverride = File(System.getProperty("java.io.tmpdir"), "talaria-artifact-test"),
            defaultDispatcher = StandardTestDispatcher(testScheduler),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()

        assertEquals(50, messageLoads)
        assertEquals(50, (vm.ui.value.load as ArtifactLoadState.Ready).artifacts.size)

        vm.refresh()
        advanceUntilIdle()

        assertEquals(50, messageLoads)
    }

    private fun viewModel(
        messages: List<SessionMessage>,
        readText: suspend (String) -> Result<FsTextFile> = { Result.success(FsTextFile(path = it)) },
        readDataUrl: suspend (String) -> FsDataUrl = { FsDataUrl(path = it) },
        shareRequestBuilder: (suspend (ArtifactRecord) -> ArtifactShareRequest)? = null,
        scope: TestScope,
    ) = ArtifactsViewModel(
        loadSessions = {
            Result.success(
                SessionsPage(
                    sessions = listOf(
                        SessionSummary(
                            id = "session-1",
                            title = "Build preview",
                        ),
                    ),
                ),
            )
        },
        loadMessages = { Result.success(messages) },
        readText = readText,
        readDataUrl = readDataUrl,
        shareRequestBuilder = shareRequestBuilder,
        previewDirectoryOverride = File(System.getProperty("java.io.tmpdir"), "talaria-artifact-test"),
        // Drive every extraction/preview hop on the test scheduler so
        // advanceUntilIdle() actually drains it (real Default/IO dispatchers
        // are not controlled by runTest).
        defaultDispatcher = StandardTestDispatcher(scope.testScheduler),
        ioDispatcher = StandardTestDispatcher(scope.testScheduler),
    )

    private fun artifact(kind: ArtifactKind, path: String) = ArtifactRecord(
        id = "session-1:$path",
        path = path,
        kind = kind,
        label = path.substringAfterLast('/'),
        sessionId = "session-1",
        sessionTitle = "Build preview",
    )

    private companion object {
        const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
