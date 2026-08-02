/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.manage.artifacts

import android.net.Uri
import com.hermesgadget.talaria.domain.model.FsDataUrl
import com.hermesgadget.talaria.domain.model.FsTextFile
import com.hermesgadget.talaria.domain.model.SessionMessage
import com.hermesgadget.talaria.domain.model.SessionSummary
import com.hermesgadget.talaria.domain.model.SessionsPage
import com.hermesgadget.talaria.util.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

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
    fun `image preview uses data URL metadata`() = runTest {
        val artifact = artifact(ArtifactKind.IMAGE, "/tmp/render.png")
        val vm = viewModel(
            messages = emptyList(),
            readDataUrl = {
                FsDataUrl(
                    path = it,
                    dataUrl = "data:image/png;base64,aGVsbG8=",
                    mimeType = "image/png",
                    byteSize = 5,
                )
            },
        )
        advanceUntilIdle()

        vm.openPreview(artifact)
        advanceUntilIdle()

        val preview = vm.ui.value.preview as ArtifactPreview.Image
        assertEquals("data:image/png;base64,aGVsbG8=", preview.dataUrl)
        assertEquals("image/png", preview.mimeType)
        assertEquals(5, preview.byteSize)
    }

    @Test
    fun `preview failure is surfaced and loading stops`() = runTest {
        val vm = viewModel(
            messages = emptyList(),
            readText = { Result.failure(IllegalStateException("read failed")) },
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
            uri = Uri.parse("content://talaria/artifact-1"),
            mimeType = "text/plain",
            subject = "Hermes artifact notes.md",
        )
        val vm = viewModel(
            messages = emptyList(),
            shareRequestBuilder = { request },
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
        )
        advanceUntilIdle()

        val failure = vm.ui.value.load as ArtifactLoadState.Failed
        assertEquals("sessions unavailable", failure.message)
    }

    private fun viewModel(
        messages: List<SessionMessage>,
        readText: suspend (String) -> Result<FsTextFile> = { Result.success(FsTextFile(path = it)) },
        readDataUrl: suspend (String) -> FsDataUrl = { FsDataUrl(path = it) },
        shareRequestBuilder: (suspend (ArtifactRecord) -> ArtifactShareRequest)? = null,
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
    )

    private fun artifact(kind: ArtifactKind, path: String) = ArtifactRecord(
        id = "session-1:$path",
        path = path,
        kind = kind,
        label = path.substringAfterLast('/'),
        sessionId = "session-1",
        sessionTitle = "Build preview",
    )
}
