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


package com.nousresearch.talaria.voice

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nousresearch.talaria.R
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.core.notifications.NotificationChannels
import com.nousresearch.talaria.core.voice.SttEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Foreground service wrapping continuous on-device dictation so recognition
 * survives brief backgrounding under Doze with an explicit user-visible FGS.
 */
class VoiceDictationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, NotificationChannels.SYNC)
            .setContentTitle("Talaria dictation")
            .setContentText("Listening…")
            .setSmallIcon(R.drawable.ic_talaria)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(42, notification)
        }
        val speech = TalariaApp.instance.container.speechCoordinator
        job?.cancel()
        job = scope.launch {
            speech.listen(continuous = true).collect { event ->
                _events.emit(event)
                if (event is SttEvent.Error) stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 32)
        val events = _events.asSharedFlow()
    }
}
