/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * B16: deployments may live under a path prefix (`https://host/hermes/`).
 * Retrofit preserves that prefix, so request paths arrive as
 * `/hermes/api/status` — path checks that assume `/api/...` at the root miss
 * them and the profile/auth/limit policies silently stop applying.
 *
 * Every interceptor derives its policy route from [routePath], which strips
 * the snapshot's base path prefix and re-anchors the request at the app's
 * virtual root.
 */
object RoutePath {
    /** Normalized base path of [baseUrl] ("/" when the deployment is at the root). */
    fun basePath(baseUrl: String): String {
        val url = baseUrl.toHttpUrlOrNull() ?: return "/"
        val segments = url.pathSegments.dropLastWhile { it.isEmpty() }
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/") + "/"
    }

    /**
     * The app-relative route for [requestPath] given the deployment's
     * [basePrefix]. When the prefix is present it is removed; otherwise the
     * path is returned unchanged (root deployments and direct-origin probes).
     */
    fun routePath(requestPath: String, basePrefix: String): String {
        if (basePrefix == "/") return requestPath
        val normalizedPrefix = if (basePrefix.endsWith("/")) basePrefix else "$basePrefix/"
        return if (requestPath.startsWith(normalizedPrefix)) {
            val rest = requestPath.removePrefix(normalizedPrefix)
            if (rest.isEmpty()) "/" else "/$rest"
        } else {
            requestPath
        }
    }

    /** Convenience for request URLs against a snapshot's base URL. */
    fun routePath(url: HttpUrl, snapshotBaseUrl: String): String =
        routePath(url.encodedPath, basePath(snapshotBaseUrl))
}
