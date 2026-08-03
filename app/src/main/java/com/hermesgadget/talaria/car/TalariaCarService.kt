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

package com.hermesgadget.talaria.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.hermesgadget.talaria.BuildConfig

/**
 * Android Auto / Automotive OS entry point for Talaria.
 *
 * Sideloaded APKs are discovered by the car host through the manifest
 * intent filter (action `androidx.car.app.CarAppService`, category
 * `androidx.car.app.category.MESSAGING`) — no Play Store involvement.
 */
class TalariaCarService : CarAppService() {

    /**
     * AndroidX ships the package/signature pairs for the supported Android
     * Auto and Automotive Templates Hosts in its sample allowlist. Keep the
     * permissive validator available only for local development and testing.
     */
    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent) = SessionListScreen(carContext)
    }
}
