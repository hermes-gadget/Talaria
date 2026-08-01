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

package com.hermesgadget.talaria.network

import com.hermesgadget.talaria.core.network.EmulatorLoopbackInterceptor
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmulatorLoopbackInterceptorTest {
    @Test
    fun rewritesHostForEmulatorLoopbackAlias() {
        val interceptor = EmulatorLoopbackInterceptor()
        var seenHost: String? = null
        val chain = object : Interceptor.Chain {
            override fun request(): Request =
                Request.Builder().url("http://10.0.2.2:9119/api/status").build()

            override fun proceed(request: Request): Response {
                seenHost = request.header("Host")
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody(null))
                    .build()
            }

            override fun connection() = null
            override fun call() = error("unused")
            override fun connectTimeoutMillis() = 0
            override fun readTimeoutMillis() = 0
            override fun writeTimeoutMillis() = 0
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }
        interceptor.intercept(chain)
        assertEquals("127.0.0.1:9119", seenHost)
    }

    @Test
    fun leavesNonEmulatorHostsAlone() {
        val interceptor = EmulatorLoopbackInterceptor()
        var seenHost: String? = "sentinel"
        val chain = object : Interceptor.Chain {
            override fun request(): Request =
                Request.Builder().url("http://127.0.0.1:9119/api/status").build()

            override fun proceed(request: Request): Response {
                seenHost = request.header("Host")
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody(null))
                    .build()
            }

            override fun connection() = null
            override fun call() = error("unused")
            override fun connectTimeoutMillis() = 0
            override fun readTimeoutMillis() = 0
            override fun writeTimeoutMillis() = 0
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }
        interceptor.intercept(chain)
        // Unchanged request: no Host header was injected.
        assertNull(seenHost)
    }

    @Test
    fun okHttpStillBuilds() {
        // WebSockets skip network interceptors, so this must remain attached to
        // the application chain used by both HTTP and WebSocket requests.
        val client = OkHttpClient.Builder().addInterceptor(EmulatorLoopbackInterceptor()).build()
        assertEquals(1, client.interceptors.size)
        assertEquals(0, client.networkInterceptors.size)
    }
}
