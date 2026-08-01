/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.nousresearch.talaria.core.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentCookieJarTest {
    @Test
    fun retainsSameNameCookiesWithDifferentPaths() {
        val jar = PersistentCookieJar()
        val root = "https://example.test/".toHttpUrl()
        jar.saveFromResponse(
            root,
            listOf(
                Cookie.Builder().name("hermes_session_at").value("one")
                    .domain("example.test").path("/one").build(),
                Cookie.Builder().name("hermes_session_at").value("two")
                    .domain("example.test").path("/two").build(),
            ),
        )

        assertEquals("one", jar.loadForRequest("https://example.test/one/api".toHttpUrl()).single().value)
        assertEquals("two", jar.loadForRequest("https://example.test/two/api".toHttpUrl()).single().value)
        assertTrue(jar.loadForRequest(root).isEmpty())
    }

    @Test
    fun doesNotSendSecureCookieOverHttp() {
        val jar = PersistentCookieJar()
        jar.saveFromResponse(
            "https://example.test/".toHttpUrl(),
            listOf(
                Cookie.Builder().name("session").value("secret")
                    .domain("example.test").path("/").secure().build(),
            ),
        )

        assertTrue(jar.loadForRequest("http://example.test/api".toHttpUrl()).isEmpty())
    }
}
