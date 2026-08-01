/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hermesgadget.talaria.core.data.prefs

import android.content.Context
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsStoreAgentNotificationsTest {
    private lateinit var context: Context
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clear()
        store = SettingsStore(context)
    }

    @After
    fun tearDown() = clear()

    @Test
    fun duplicateClaimsAreSuppressedOnlyInsideWindow() {
        assertTrue(store.claimAgentNotification("session|complete", "same", nowMillis = 1_000L))
        assertFalse(store.claimAgentNotification("session|complete", "same", nowMillis = 2_000L))
        assertTrue(store.claimAgentNotification("session|complete", "different", nowMillis = 2_001L))
        assertTrue(store.claimAgentNotification("session|complete", "same", nowMillis = 40_000L))
    }

    @Test
    fun activeWatchesSurviveStoreRecreation() {
        val watch = PersistedAgentWatch(
            watcherId = "tab-1",
            agentName = "Build agent",
            channelId = "channel-1",
            sessionId = "session-1",
            connectionId = "connection-1",
            managementProfile = "default",
        )
        store.saveAgentWatches(listOf(watch))

        assertEquals(listOf(watch), SettingsStore(context).loadAgentWatches())
    }

    @Test
    fun permissionNotificationIdsAreTakenAtomically() {
        store.addActiveAgentPermission("session-1", 10)
        store.addActiveAgentPermission("session-1", 20)

        assertEquals(setOf(10, 20), store.takeActiveAgentPermissions("session-1"))
        assertTrue(store.takeActiveAgentPermissions("session-1").isEmpty())
    }

    private fun clear() {
        contextOrNull()?.getSharedPreferences("talaria_settings", Context.MODE_PRIVATE)?.edit()?.clear()?.commit()
    }

    private fun contextOrNull(): Context? =
        if (::context.isInitialized) context else ApplicationProvider.getApplicationContext()
}
