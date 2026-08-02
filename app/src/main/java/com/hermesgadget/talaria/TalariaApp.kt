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
package com.hermesgadget.talaria

import android.app.Application
import android.content.Context
import com.hermesgadget.talaria.core.data.prefs.LocaleManager
import com.hermesgadget.talaria.core.notifications.NotificationChannels
import com.hermesgadget.talaria.di.AppContainer
import com.hermesgadget.talaria.worker.SyncScheduler

class TalariaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        container.localeManager.apply(this)
        NotificationChannels.ensure(this)
        SyncScheduler.ensurePeriodic(this)
        container.foregroundObserver.install()
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.wrap(base))
    }

    companion object {
        lateinit var instance: TalariaApp
            private set
    }
}
