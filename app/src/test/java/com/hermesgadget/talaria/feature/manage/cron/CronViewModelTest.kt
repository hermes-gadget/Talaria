/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.manage.cron

import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.domain.model.AutomationBlueprint
import com.hermesgadget.talaria.domain.model.CronDeliveryTarget
import com.hermesgadget.talaria.domain.model.CronRun
import com.hermesgadget.talaria.domain.model.ManageCronJob
import com.hermesgadget.talaria.util.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CronViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun parsesRunStatusTimeAndExpandableOutput() {
        val root = JsonConfig.json.parseToJsonElement(
            """{"runs":[{"id":"run-1","started_at":1710000000,"ended_at":1710000010,"end_reason":"completed","preview":"Finished the task","message_count":3,"is_active":false}]}""",
        )

        val run = parseCronRuns(root).single()

        assertEquals("run-1", run.id)
        assertEquals("completed", run.status)
        assertEquals("1710000000", run.startedAt)
        assertEquals("Finished the task", run.output)
        assertEquals("Finished the task", run.preview)
        assertTrue(run.raw.contains("message_count"))
    }

    @Test
    fun blueprintInstantiationSendsBlueprintAndValues() = runTest {
        val gateway = FakeCronGateway()
        val vm = CronViewModel(gateway)
        advanceUntilIdle()

        vm.instantiateBlueprint("daily-report", mapOf("name" to "Morning report", "prompt" to "Summarize"))
        advanceUntilIdle()

        assertEquals("daily-report", gateway.instantiatedBlueprint)
        assertEquals("Morning report", gateway.instantiatedValues["name"])
        assertEquals("Summarize", gateway.instantiatedValues["prompt"])
    }

    private class FakeCronGateway : CronGateway {
        var instantiatedBlueprint: String? = null
        var instantiatedValues: Map<String, String> = emptyMap()

        override suspend fun load() = CronSnapshot(
            jobs = emptyList<ManageCronJob>(),
            deliveryTargets = listOf(CronDeliveryTarget("local", "Local")),
            blueprints = listOf(AutomationBlueprint("daily-report", "Daily report")),
        )

        override suspend fun loadRuns(jobId: String, limit: Int): List<CronRun> = emptyList()
        override suspend fun create(prompt: String, schedule: String, name: String?, deliver: String) = Unit
        override suspend fun update(jobId: String, prompt: String, schedule: String, deliver: String) = Unit
        override suspend fun pause(jobId: String) = Unit
        override suspend fun resume(jobId: String) = Unit
        override suspend fun trigger(jobId: String) = Unit
        override suspend fun delete(jobId: String) = Unit

        override suspend fun instantiate(blueprint: String, values: Map<String, String>) {
            instantiatedBlueprint = blueprint
            instantiatedValues = values
        }
    }
}
