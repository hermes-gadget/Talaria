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
     * Auto and Automotive Templates Hosts in its sample allowlist. Use it in
     * debug builds so the AAOS emulator host is accepted.
     *
     * Release builds must accept every host: this app is distributed by
     * sideload (no Play Store), so Google's server-side host validation
     * never runs. The AndroidX sample allowlist only covers the three
     * AOSP/Play gearhead signatures — OEM-signed Android Auto variants
     * (preinstalled on many phones, including common CUPRA/SEAT
     * setups) fail validation and Android Auto then silently drops the
     * app from its launcher ("not available at all"). For a personal
     * sideloaded app the host-identity risk of ALLOW_ALL is acceptable:
     * a hostile host could drive the session UI, but session data is
     * already on the device.
     */
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent) = SessionListScreen(carContext)
    }
}
