/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.core.network

import okhttp3.Interceptor
import okhttp3.Response

/** Enforces the saved profile's cleartext decision before OkHttp opens a socket. */
class CleartextPolicyInterceptor(
    private val snapshot: ConnectionSnapshot,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        CleartextPolicy.check(snapshot, chain.request().url)
        return chain.proceed(chain.request())
    }
}

