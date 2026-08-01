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

package com.hermesgadget.talaria.core.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermesgadget.talaria.TalariaApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentNotificationsInstrumentedTest {
    @Test
    fun permissionIsNamedAndClearedByNamedCompletion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = (context as TalariaApp).container
        val manager = context.getSystemService(NotificationManager::class.java)
        val suffix = System.nanoTime().toString()
        val name = "Permission Agent $suffix"
        val target = AgentNotificationTarget(
            watcherId = "watcher-$suffix",
            agentName = name,
            sessionId = "session-$suffix",
            connectionId = null,
            managementProfile = null,
        )
        val postedIds = mutableSetOf<Int>()
        val oldEnabled = container.settingsStore.notificationsEnabled
        val oldPermissions = container.settingsStore.notifyAgentPermissions
        val oldCompletions = container.settingsStore.notifyTaskCompletions
        val hadNotificationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        try {
            if (!hadNotificationPermission) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                    context.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
            container.settingsStore.notificationsEnabled = true
            container.settingsStore.notifyAgentPermissions = true
            container.settingsStore.notifyTaskCompletions = true
            NotificationChannels.ensure(context)

            container.notifier.notifyAgentPermission(
                target = target,
                notificationKey = "request-$suffix",
                fingerprint = "permission-$suffix",
                body = "Permission required: run the requested command?",
            )

            val permission = manager.activeNotifications.firstOrNull {
                it.notification.channelId == NotificationChannels.AGENT_PERMISSIONS &&
                    it.notification.extras.getString(Notification.EXTRA_TITLE) == "$name needs permission"
            }
            assertNotNull(permission)
            postedIds += permission!!.id
            assertEquals(
                "Permission required: run the requested command?",
                permission.notification.extras.getString(Notification.EXTRA_TEXT),
            )
            assertEquals(0, permission.notification.flags and Notification.FLAG_AUTO_CANCEL)

            container.notifier.notifyAgentTaskFinished(
                target = target,
                fingerprint = "completion-$suffix",
                body = "The requested work is complete.",
                failed = false,
                background = false,
            )

            assertFalse(manager.activeNotifications.any { it.id == permission.id })
            val completion = manager.activeNotifications.firstOrNull {
                it.notification.channelId == NotificationChannels.AGENT_TASKS &&
                    it.notification.extras.getString(Notification.EXTRA_TITLE) == "$name completed the task"
            }
            assertNotNull(completion)
            postedIds += completion!!.id
            assertEquals(
                "The requested work is complete.",
                completion.notification.extras.getString(Notification.EXTRA_TEXT),
            )
        } finally {
            postedIds.forEach(manager::cancel)
            container.settingsStore.notificationsEnabled = oldEnabled
            container.settingsStore.notifyAgentPermissions = oldPermissions
            container.settingsStore.notifyTaskCompletions = oldCompletions
            // Do not revoke a runtime permission from inside instrumentation:
            // Android terminates the target process before JUnit can report.
            // The connected-test task uninstalls this test app after the run.
        }
    }
}
