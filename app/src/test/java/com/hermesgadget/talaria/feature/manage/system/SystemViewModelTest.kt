/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.manage.system

import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.domain.model.ActionStatus
import com.hermesgadget.talaria.domain.model.OpsActionResponse
import com.hermesgadget.talaria.domain.model.OpsBackupRequest
import com.hermesgadget.talaria.domain.model.OpsDebugShareRequest
import com.hermesgadget.talaria.domain.model.OpsDebugShareResponse
import com.hermesgadget.talaria.domain.model.OpsHookCreateRequest
import com.hermesgadget.talaria.domain.model.OpsHookDeleteRequest
import com.hermesgadget.talaria.domain.model.OpsHooksResponse
import com.hermesgadget.talaria.domain.model.OpsRawConfigResponse
import com.hermesgadget.talaria.domain.model.OpsRawConfigUpdate
import com.hermesgadget.talaria.domain.model.SystemStats
import com.hermesgadget.talaria.util.MainDispatcherRule
import java.io.File
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SystemViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun updateAndDrainActionsPublishGatewayResponses() = runTest {
        val gateway = FakeSystemGateway()
        val vm = testViewModel(gateway)

        vm.applyHermesUpdate()
        vm.drainGateway()
        advanceUntilIdle()

        assertTrue(gateway.updateApplied)
        assertTrue(gateway.gatewayDrained)
        assertEquals("{\"name\":\"hermes-update\"}", vm.ui.value.updateAction)
        assertEquals("{\"action\":\"drain\",\"ok\":true}", vm.ui.value.gatewayDrain)
        assertTrue(!vm.ui.value.updateBusy)
    }

    @Test
    fun opsDepthLoadsCheckpointsAndRunsAllMaintenanceActions() = runTest {
        val gateway = FakeSystemGateway()
        val vm = testViewModel(gateway)

        vm.getOpsCheckpoints()
        advanceUntilIdle()
        assertTrue(vm.ui.value.opsCheckpoints?.contains("session-a") == true)
        assertTrue(vm.ui.value.opsCheckpoints?.contains("total_bytes") == true)

        vm.pruneOpsCheckpoints()
        vm.runConfigMigrate()
        vm.runOpsDump()
        vm.runOpsPromptSize()
        advanceUntilIdle()

        assertTrue(gateway.checkpointsPruned)
        assertTrue(gateway.configMigrated)
        assertTrue(gateway.dumpRun)
        assertTrue(gateway.promptSizeRun)
        assertTrue(gateway.checkpointReads >= 2)
        assertEquals("{\"name\":\"prompt-size\",\"ok\":true}", vm.ui.value.opsResult)
        assertTrue(!vm.ui.value.opsBusy)
    }

    @Test
    fun actionFailuresAreExposedAndClearBusyState() = runTest {
        val gateway = FakeSystemGateway().apply {
            drainFailure = IllegalStateException("drain failed")
            opsFailure = IllegalStateException("dump failed")
        }
        val vm = testViewModel(gateway)

        vm.drainGateway()
        vm.runOpsDump()
        advanceUntilIdle()

        assertEquals("drain failed", vm.ui.value.gatewayDrain)
        assertEquals("dump failed", vm.ui.value.opsError)
        assertTrue(!vm.ui.value.updateBusy)
        assertTrue(!vm.ui.value.opsBusy)
    }

    private fun testViewModel(gateway: SystemGateway): SystemViewModel =
        SystemViewModel(
            gateway = gateway,
            cacheDirectory = File("build/test-system-cache"),
            autoRefresh = false,
        )

    private class FakeSystemGateway : SystemGateway {
        var updateApplied = false
        var gatewayDrained = false
        var checkpointsPruned = false
        var configMigrated = false
        var dumpRun = false
        var promptSizeRun = false
        var checkpointReads = 0
        var drainFailure: Throwable? = null
        var opsFailure: Throwable? = null

        override suspend fun getSystemStats(): Result<SystemStats> = Result.success(SystemStats())
        override suspend fun getPortal(): Result<JsonElement> = Result.success(json("{}"))
        override suspend fun gateway(action: String): Result<ActionStatus> =
            Result.success(ActionStatus(name = "gateway-$action", exit_code = 0))

        override suspend fun runDoctor(): Result<ActionStatus> =
            Result.success(ActionStatus(name = "doctor", exit_code = 0))

        override suspend fun runSecurityAudit(): Result<ActionStatus> =
            Result.success(ActionStatus(name = "security-audit", exit_code = 0))

        override suspend fun runBackup(): Result<ActionStatus> =
            Result.success(ActionStatus(name = "backup", exit_code = 0))

        override suspend fun checkUpdate(): Result<JsonElement> = Result.success(json("{\"behind\":0}"))

        override suspend fun getOpsHooks(): OpsHooksResponse = OpsHooksResponse()
        override suspend fun createOpsHook(request: OpsHookCreateRequest): OpsActionResponse = OpsActionResponse(ok = true)
        override suspend fun deleteOpsHook(request: OpsHookDeleteRequest): OpsActionResponse = OpsActionResponse(ok = true)
        override suspend fun importOpsUpload(file: MultipartBody.Part, force: RequestBody): OpsActionResponse =
            OpsActionResponse(ok = true)

        override suspend fun createOpsBackup(request: OpsBackupRequest): OpsActionResponse =
            OpsActionResponse(ok = true, archive = "backup.zip")

        override suspend fun downloadOpsBackup(archive: String): ResponseBody = error("not used")
        override suspend fun createOpsDebugShare(request: OpsDebugShareRequest): OpsDebugShareResponse =
            OpsDebugShareResponse(ok = true)

        override suspend fun getOpsRawConfig(): OpsRawConfigResponse = OpsRawConfigResponse()
        override suspend fun putOpsRawConfig(update: OpsRawConfigUpdate): OpsActionResponse = OpsActionResponse(ok = true)

        override suspend fun applyHermesUpdate(): JsonElement {
            updateApplied = true
            return json("{\"name\":\"hermes-update\"}")
        }

        override suspend fun drainGateway(): JsonElement {
            drainFailure?.let { throw it }
            gatewayDrained = true
            return json("{\"action\":\"drain\",\"ok\":true}")
        }

        override suspend fun getOpsCheckpoints(): JsonElement {
            checkpointReads += 1
            return json(
                "{\"sessions\":[{\"session\":\"session-a\",\"files\":2,\"bytes\":64}],\"total_bytes\":64}",
            )
        }

        override suspend fun pruneOpsCheckpoints(): JsonElement {
            checkpointsPruned = true
            return json("{\"name\":\"checkpoints-prune\",\"ok\":true}")
        }

        override suspend fun runConfigMigrate(): JsonElement {
            configMigrated = true
            return json("{\"name\":\"config-migrate\",\"ok\":true}")
        }

        override suspend fun runOpsDump(): JsonElement {
            opsFailure?.let { throw it }
            dumpRun = true
            return json("{\"name\":\"dump\",\"ok\":true}")
        }

        override suspend fun runOpsPromptSize(): JsonElement {
            promptSizeRun = true
            return json("{\"name\":\"prompt-size\",\"ok\":true}")
        }

        private fun json(raw: String): JsonElement = JsonConfig.json.parseToJsonElement(raw)
    }

    private fun json(raw: String): JsonElement = JsonConfig.json.parseToJsonElement(raw)
}
