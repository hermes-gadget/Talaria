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

package com.hermesgadget.talaria.core.network

import com.hermesgadget.talaria.core.security.CertificatePinnerFactory
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient

/**
 * Remove store-backed request interceptors from a client captured for one
 * connection. WebSocket auth is supplied in the already-captured query value;
 * leaving these interceptors installed would read a newly active profile when
 * the upgrade actually executes.
 */
internal fun OkHttpClient.fixedConnectionClient(
    profile: ConnectionProfile? = null,
): OkHttpClient = newBuilder().apply {
    interceptors().removeAll { interceptor ->
        interceptor is AuthInterceptor || interceptor is ProfileQueryInterceptor
    }
    profile?.let { snapshot ->
        certificatePinner(
            snapshot.pinSha256?.takeIf { it.isNotBlank() }?.let {
                CertificatePinnerFactory.forPin(snapshot.baseUrl, it)
            } ?: CertificatePinner.DEFAULT,
        )
    }
}.build()
