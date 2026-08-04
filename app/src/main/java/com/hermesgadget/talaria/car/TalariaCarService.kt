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
import com.hermesgadget.talaria.TalariaApp

/**
 * Android Auto / Automotive OS entry point for Talaria.
 *
 * Sideloaded APKs are discovered by the car host through the manifest
 * intent filter (action `androidx.car.app.CarAppService`, category
 * `androidx.car.app.category.MESSAGING`) — no Play Store involvement.
 */
class TalariaCarService : CarAppService() {

    /**
     * DHU/debug keeps the permissive v0.8.2 behavior. Release uses the exact
     * AndroidX sample package/certificate pairs plus handset-enrolled pairs;
     * package name by itself is never an allow decision.
     */
    override fun createHostValidator(): HostValidator = CarHostValidatorFactory.create(
        context = this,
        debugBuild = BuildConfig.DEBUG,
        trustStore = TalariaApp.instance.container.carHostTrustStore,
    )

    override fun onCreateSession(): Session {
        // One immutable destination/auth scope owns the entire car session.
        // Phone-side profile switches cannot retarget reads or prompt delivery.
        val snapshot = TalariaApp.instance.container.clientFactory.snapshot()
        val trustStore = TalariaApp.instance.container.carHostTrustStore
        return object : Session() {
            override fun onCreateScreen(intent: Intent): SessionListScreen {
                val identity = if (BuildConfig.DEBUG) {
                    null
                } else {
                    carContext.hostInfo?.let { CarHostIdentityResolver.resolve(carContext, it) }
                }
                val authorizer = CarHostSessionAuthorizer(
                    debugBuild = BuildConfig.DEBUG,
                    identity = identity,
                    knownHosts = if (BuildConfig.DEBUG) {
                        emptySet()
                    } else {
                        AndroidxKnownCarHosts.identities(carContext)
                    },
                    trustStore = trustStore,
                )
                authorizer.startSession()
                return SessionListScreen(carContext, snapshot, authorizer)
            }
        }
    }
}
