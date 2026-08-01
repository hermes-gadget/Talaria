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

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.prefs.PersistedAgentWatch
import com.hermesgadget.talaria.core.network.HermesEventClient
import com.hermesgadget.talaria.core.network.HermesSideEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a lightweight `/api/events` subscriber alive for each user-started turn.
 * The service is foreground only while work is active, so Android can keep the
 * permission/completion listener alive when Talaria is backgrounded.
 */
class AgentTaskNotificationService : Service() {
    private data class Runtime(
        var watch: PersistedAgentWatch,
        val client: HermesEventClient,
        val collectJob: Job,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimes = linkedMapOf<String, Runtime>()
    private val container get() = TalariaApp.instance.container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!container.notifier.canMonitorAgentTasks()) {
            container.settingsStore.saveAgentWatches(emptyList())
            stopAllInternal()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_WATCH -> intent.readWatch()?.let(::watch)
            ACTION_UPDATE -> intent.readWatch()?.let(::update)
            ACTION_STOP -> intent.getStringExtra(EXTRA_WATCHER_ID)?.let(::stopWatch)
            ACTION_STOP_ALL -> stopAllInternal()
            else -> restorePersistedWatches()
        }
        return if (runtimes.isEmpty()) START_NOT_STICKY else START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopAllInternal()
        stopSelf(startId)
    }

    override fun onDestroy() {
        runtimes.values.forEach {
            it.collectJob.cancel()
            it.client.dispose()
        }
        runtimes.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun restorePersistedWatches() {
        container.settingsStore.loadAgentWatches().forEach(::watch)
        if (runtimes.isEmpty()) stopSelf()
    }

    private fun watch(record: PersistedAgentWatch) {
        val existing = runtimes[record.watcherId]
        if (existing != null && existing.watch.channelId == record.channelId) {
            existing.watch = merge(existing.watch, record)
            persistAndRefreshForeground()
            return
        }
        existing?.let {
            it.collectJob.cancel()
            it.client.dispose()
            runtimes.remove(record.watcherId)
        }
        // Call startForeground before opening sockets: startForegroundService
        // callers have a strict five-second deadline on modern Android.
        val previewNames = runtimes.values.map { it.watch.agentName } + record.agentName
        startForeground(
            TalariaNotifier.AGENT_MONITOR_NOTIFICATION_ID,
            container.notifier.buildAgentMonitorNotification(previewNames),
        )

        val client = HermesEventClient(
            container.clientFactory,
            container.connectionStore,
            container.wsAuthHelper,
        )
        // Monitoring only needs channel events. Avoid a second RPC socket and
        // its model/catalog probes for every active turn.
        client.start(record.channelId, includeRpc = false)
        val job = serviceScope.launch {
            client.events.collect { event -> handle(record.watcherId, event) }
        }
        runtimes[record.watcherId] = Runtime(record, client, job)
        persistAndRefreshForeground()
    }

    private fun update(record: PersistedAgentWatch) {
        val runtime = runtimes[record.watcherId]
        if (runtime == null) {
            watch(record)
            return
        }
        runtime.watch = merge(runtime.watch, record)
        persistAndRefreshForeground()
    }

    private fun handle(watcherId: String, event: HermesSideEvent) {
        val runtime = runtimes[watcherId] ?: return
        event.sessionIdOrNull()?.takeIf(String::isNotBlank)?.let { sessionId ->
            if (runtime.watch.sessionId != sessionId) {
                runtime.watch = runtime.watch.copy(sessionId = sessionId)
                persistAndRefreshForeground()
            }
        }
        val watch = runtime.watch
        container.agentAlertDispatcher.dispatch(
            identity = AgentThreadIdentity(watch.watcherId, watch.agentName, watch.sessionId),
            event = event,
            connectionId = watch.connectionId,
            managementProfile = watch.managementProfile,
        )
        when {
            event is HermesSideEvent.MessageComplete -> stopWatch(watcherId)
            event is HermesSideEvent.TransportError && event.isTerminalMonitorError() -> {
                container.notifier.notifyError(
                    "${watch.agentName} monitoring stopped",
                    event.message,
                )
                stopWatch(watcherId)
            }
        }
    }

    private fun stopWatch(watcherId: String) {
        runtimes.remove(watcherId)?.let {
            it.collectJob.cancel()
            it.client.dispose()
        }
        persistAndRefreshForeground()
        if (runtimes.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopAllInternal() {
        runtimes.keys.toList().forEach(::stopWatch)
        container.settingsStore.saveAgentWatches(emptyList())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun persistAndRefreshForeground() {
        val watches = runtimes.values.map(Runtime::watch)
        container.settingsStore.saveAgentWatches(watches)
        if (watches.isNotEmpty()) {
            startForeground(
                TalariaNotifier.AGENT_MONITOR_NOTIFICATION_ID,
                container.notifier.buildAgentMonitorNotification(watches.map(PersistedAgentWatch::agentName)),
            )
        }
    }

    private fun merge(old: PersistedAgentWatch, new: PersistedAgentWatch): PersistedAgentWatch = old.copy(
        agentName = new.agentName.ifBlank { old.agentName },
        channelId = new.channelId.ifBlank { old.channelId },
        sessionId = new.sessionId ?: old.sessionId,
        connectionId = new.connectionId ?: old.connectionId,
        managementProfile = new.managementProfile ?: old.managementProfile,
    )

    private fun HermesSideEvent.sessionIdOrNull(): String? = when (this) {
        is HermesSideEvent.MessageStart -> sessionId
        is HermesSideEvent.MessageDelta -> sessionId
        is HermesSideEvent.MessageInterim -> sessionId
        is HermesSideEvent.MessageComplete -> sessionId
        is HermesSideEvent.BackgroundComplete -> sessionId
        is HermesSideEvent.Status -> sessionId
        is HermesSideEvent.Prompt -> sessionId
        is HermesSideEvent.PromptExpired -> sessionId
        is HermesSideEvent.SessionInfo -> sessionId
        else -> null
    }

    private fun HermesSideEvent.TransportError.isTerminalMonitorError(): Boolean =
        socket == "auth" || message == "Invalid dashboard URL" || message.startsWith("reconnect failed")

    private fun Intent.readWatch(): PersistedAgentWatch? {
        val watcherId = getStringExtra(EXTRA_WATCHER_ID)?.takeIf(String::isNotBlank) ?: return null
        val agentName = getStringExtra(EXTRA_AGENT_NAME)?.takeIf(String::isNotBlank) ?: return null
        val channelId = getStringExtra(EXTRA_CHANNEL_ID)?.takeIf(String::isNotBlank) ?: return null
        return PersistedAgentWatch(
            watcherId = watcherId,
            agentName = agentName,
            channelId = channelId,
            sessionId = getStringExtra(EXTRA_SESSION_ID),
            connectionId = getStringExtra(EXTRA_CONNECTION_ID),
            managementProfile = getStringExtra(EXTRA_MANAGEMENT_PROFILE),
        )
    }

    companion object {
        private const val ACTION_WATCH = "com.hermesgadget.talaria.action.WATCH_AGENT_TASK"
        private const val ACTION_UPDATE = "com.hermesgadget.talaria.action.UPDATE_AGENT_TASK"
        private const val ACTION_STOP = "com.hermesgadget.talaria.action.STOP_AGENT_TASK"
        private const val ACTION_STOP_ALL = "com.hermesgadget.talaria.action.STOP_ALL_AGENT_TASKS"
        private const val EXTRA_WATCHER_ID = "watcher_id"
        private const val EXTRA_AGENT_NAME = "agent_name"
        private const val EXTRA_CHANNEL_ID = "channel_id"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_CONNECTION_ID = "connection_id"
        private const val EXTRA_MANAGEMENT_PROFILE = "management_profile"

        fun startWatching(context: Context, watch: PersistedAgentWatch) {
            val app = context.applicationContext as? TalariaApp ?: return
            if (!app.container.notifier.canMonitorAgentTasks()) return
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    intent(context, ACTION_WATCH, watch),
                )
            }
        }

        fun updateWatching(context: Context, watch: PersistedAgentWatch) {
            val app = context.applicationContext as? TalariaApp ?: return
            if (!app.container.notifier.canMonitorAgentTasks()) return
            runCatching { context.startService(intent(context, ACTION_UPDATE, watch)) }
        }

        fun stopWatching(context: Context, watcherId: String) {
            runCatching {
                context.startService(
                    Intent(context, AgentTaskNotificationService::class.java).apply {
                        action = ACTION_STOP
                        putExtra(EXTRA_WATCHER_ID, watcherId)
                    },
                )
            }
        }

        fun stopAll(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, AgentTaskNotificationService::class.java).apply { action = ACTION_STOP_ALL },
                )
            }
        }

        private fun intent(context: Context, actionName: String, watch: PersistedAgentWatch) =
            Intent(context, AgentTaskNotificationService::class.java).apply {
                action = actionName
                putExtra(EXTRA_WATCHER_ID, watch.watcherId)
                putExtra(EXTRA_AGENT_NAME, watch.agentName)
                putExtra(EXTRA_CHANNEL_ID, watch.channelId)
                putExtra(EXTRA_SESSION_ID, watch.sessionId)
                putExtra(EXTRA_CONNECTION_ID, watch.connectionId)
                putExtra(EXTRA_MANAGEMENT_PROFILE, watch.managementProfile)
            }
    }
}
