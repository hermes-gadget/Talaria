/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The success path is not injectable: ReplyWorker resolves TalariaApp.instance
 * and constructs ChatRepository internally. This test covers the public worker
 * boundary that can be exercised without changing production interfaces: a
 * malformed notification payload is rejected before global app state is read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReplyWorkerDeliveryBoundaryTest {
    @Test
    fun `missing reply text fails before opening a transport`() = runBlocking {
        val params = mockk<WorkerParameters>()
        every { params.inputData } returns Data.Builder()
            .putString(ReplyWorker.KEY_CONNECTION_ID, "connection-1")
            .build()

        val worker = ReplyWorker(
            ApplicationProvider.getApplicationContext<Context>(),
            params,
        )

        assertEquals(
            ListenableWorker.Result.failure().javaClass,
            worker.doWork().javaClass,
        )
    }
}
