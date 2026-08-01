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

import com.hermesgadget.talaria.core.network.HermesSideEvent

class AgentAlertDispatcher(private val notifier: TalariaNotifier) {
    fun dispatch(
        identity: AgentThreadIdentity,
        event: HermesSideEvent,
        connectionId: String?,
        managementProfile: String?,
    ): AgentAlert? {
        val alert = AgentNotificationPolicy.alert(identity, event) ?: return null
        val target = AgentNotificationTarget(
            watcherId = identity.watcherId,
            agentName = alert.agentName,
            sessionId = alert.sessionId,
            connectionId = connectionId,
            managementProfile = managementProfile,
        )
        when (alert) {
            is AgentAlert.PermissionRequired -> notifier.notifyAgentPermission(
                target = target,
                notificationKey = alert.notificationKey,
                fingerprint = alert.fingerprint,
                body = alert.body,
            )
            is AgentAlert.PermissionExpired -> notifier.cancelAgentPermission(
                target = target,
                notificationKey = alert.notificationKey,
            )
            is AgentAlert.TaskFinished -> notifier.notifyAgentTaskFinished(
                target = target,
                fingerprint = alert.fingerprint,
                body = alert.body,
                failed = alert.failed,
                background = alert.background,
            )
        }
        return alert
    }
}
