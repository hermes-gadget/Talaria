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

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Android emulator alias `10.0.2.2` reaches the host loopback, but Hermes
 * dashboards bound to `127.0.0.1` reject any Host/Origin that isn't a
 * loopback name (DNS-rebinding defence). Rewrite Host so REST + WebSocket
 * upgrades look like they targeted `127.0.0.1`.
 *
 * Must run as an **application** interceptor. OkHttp's WebSocket path skips
 * network interceptors, while BridgeInterceptor preserves an explicit Host
 * header that an application interceptor supplied.
 */
class EmulatorLoopbackInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != EMULATOR_HOST_LOOPBACK) {
            return chain.proceed(request)
        }
        val port = request.url.port
        val hostHeader = if (port == 80 || port == 443) "127.0.0.1" else "127.0.0.1:$port"
        return chain.proceed(
            request.newBuilder()
                .header("Host", hostHeader)
                .build(),
        )
    }

    companion object {
        const val EMULATOR_HOST_LOOPBACK = "10.0.2.2"
    }
}

/** Add the emulator-only Host rewrite without allowing it into release clients. */
internal fun OkHttpClient.Builder.addEmulatorLoopbackInterceptorIf(enabled: Boolean): OkHttpClient.Builder =
    apply {
        if (enabled) addInterceptor(EmulatorLoopbackInterceptor())
    }
