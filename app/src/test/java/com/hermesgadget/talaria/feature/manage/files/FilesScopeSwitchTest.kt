/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.hermesgadget.talaria.feature.manage.files

import com.hermesgadget.talaria.core.network.ConnectionScope
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import com.hermesgadget.talaria.domain.model.ManagedFileEntry
import com.hermesgadget.talaria.domain.model.ManagedFilesListResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A->B profile-switch regression net for the Files destination (#54, follow-up to #22):
 * after the connection scope switches, no in-flight A request may mutate state or be
 * answered against connection A, and follow-up requests must route to connection B.
 */
class FilesScopeSwitchTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var flow: MutableStateFlow<ConnectionScope?>

    private fun scope(id: String, generation: Long) =
        ConnectionScope(
            ConnectionSnapshot(
                ConnectionProfile(id = id, name = id, baseUrl = "http://$id.test", createdAt = 0L),
                ConnectionSecrets(),
            ),
            generation,
        )

    private fun apiFor(
        id: String,
        listing: ManagedFilesListResponse,
        gate: CompletableDeferred<Unit>? = null,
    ): HermesApi {
        val api = mockk<HermesApi>()
        coEvery { api.listManagedFiles(any(), any()) } coAnswers {
            gate?.await()
            listing
        }
        return api
    }

    private fun viewModel(apiA: HermesApi, apiB: HermesApi, scopeA: ConnectionScope, scopeB: ConnectionScope) =
        FilesViewModel(
            apiProvider = { scope ->
                when (scope?.key) {
                    scopeA.key -> apiA
                    scopeB.key -> apiB
                    else -> error("unexpected scope $scope")
                }
            },
            scopeFlow = flow,
            cacheDirectory = tempFolder.root,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `stale A listing never renders after switch to B`() = runTest {
        val scopeA = scope("a", 1)
        val scopeB = scope("b", 1)
        flow = MutableStateFlow(scopeA)
        val gateA = CompletableDeferred<Unit>()
        val listingA = ManagedFilesListResponse(
            path = "/",
            entries = listOf(ManagedFileEntry(name = "from-a.txt", path = "/from-a.txt")),
        )
        val listingB = ManagedFilesListResponse(
            path = "/",
            entries = listOf(ManagedFileEntry(name = "from-b.txt", path = "/from-b.txt")),
        )
        val vm = viewModel(apiFor("a", listingA, gateA), apiFor("b", listingB), scopeA, scopeB)

        vm.open(null)
        flow.value = scopeB
        gateA.complete(Unit)

        withTimeout(5_000) {
            val state = vm.ui.value
            assertEquals(listOf("from-b.txt"), state.entries.map { it.name })
            assertTrue("stale A entry rendered under B", state.entries.none { it.name == "from-a.txt" })
            assertFalse(state.loading)
        }
    }

    @Test
    fun `follow-up request after switch routes to B not A`() = runTest {
        val scopeA = scope("a", 1)
        val scopeB = scope("b", 1)
        flow = MutableStateFlow(scopeA)
        val apiA = apiFor("a", ManagedFilesListResponse(entries = listOf(ManagedFileEntry(name = "a.txt", path = "/a.txt"))))
        val apiB = apiFor("b", ManagedFilesListResponse(entries = listOf(ManagedFileEntry(name = "b.txt", path = "/b.txt"))))
        val vm = viewModel(apiA, apiB, scopeA, scopeB)

        // init opens the root listing against A automatically (boundScope != null).
        flow.value = scopeB
        vm.open("subdir")

        withTimeout(5_000) {
            val state = vm.ui.value
            assertEquals("subdir", state.path)
            assertEquals(listOf("b.txt"), state.entries.map { it.name })
        }
        coVerify(exactly = 1) { apiA.listManagedFiles(any(), any()) }
        coVerify(exactly = 2) { apiB.listManagedFiles(any(), any()) }
    }

    @Test
    fun `stale A preview bytes never rendered under B`() = runTest {
        val scopeA = scope("a", 1)
        val scopeB = scope("b", 1)
        flow = MutableStateFlow(scopeA)
        val gateA = CompletableDeferred<Unit>()
        val apiA = mockk<HermesApi>()
        coEvery { apiA.listManagedFiles(any(), any()) } returns
            ManagedFilesListResponse(entries = listOf(ManagedFileEntry(name = "note.md", path = "/note.md")))
        coEvery { apiA.readManagedFileBody(any(), any()) } coAnswers {
            gateA.await()
            ResponseBody.create(null, "stale content from A".toByteArray())
        }
        val apiB = mockk<HermesApi>()
        coEvery { apiB.listManagedFiles(any(), any()) } returns ManagedFilesListResponse(entries = emptyList())
        val vm = viewModel(apiA, apiB, scopeA, scopeB)

        // init already opened the root listing (entries are A's); start preview on A.
        val entry = vm.ui.value.entries.single()
        vm.openFile(entry)
        flow.value = scopeB
        gateA.complete(Unit)

        withTimeout(5_000) {
            val state = vm.ui.value
            assertTrue("A preview rendered under B", state.previewBytes?.isEmpty() != false)
            assertNull(state.previewError)
        }
    }

    @Test
    fun `stale A download never completes under B`() = runTest {
        val scopeA = scope("a", 1)
        val scopeB = scope("b", 1)
        flow = MutableStateFlow(scopeA)
        val gateA = CompletableDeferred<Unit>()
        val apiA = mockk<HermesApi>()
        coEvery { apiA.listManagedFiles(any(), any()) } returns
            ManagedFilesListResponse(entries = listOf(ManagedFileEntry(name = "big.bin", path = "/big.bin", size = 1_000_000)))
        coEvery { apiA.downloadManagedFile(any(), any()) } coAnswers {
            gateA.await()
            ResponseBody.create(null, ByteArray(1024))
        }
        val apiB = mockk<HermesApi>()
        coEvery { apiB.listManagedFiles(any(), any()) } returns ManagedFilesListResponse(entries = emptyList())
        val vm = viewModel(apiA, apiB, scopeA, scopeB)

        // init already opened the root listing (entries are A's); start download on A.
        val entry = vm.ui.value.entries.single()
        vm.download(entry)
        flow.value = scopeB
        gateA.complete(Unit)

        withTimeout(5_000) {
            val state = vm.ui.value
            assertTrue(
                "A download surfaced under B: ${state.downloadState}",
                state.downloadState is FileDownloadState.Idle,
            )
            assertFalse(tempFolder.root.listFiles()?.any { it.name == "big.bin" } ?: false)
        }
    }
}
