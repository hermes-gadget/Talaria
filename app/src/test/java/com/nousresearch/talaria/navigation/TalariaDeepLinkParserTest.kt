/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.nousresearch.talaria.navigation

import com.nousresearch.talaria.ui.navigation.TalariaDeepLink
import com.nousresearch.talaria.ui.navigation.TalariaDeepLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TalariaDeepLinkParserTest {
    @Test
    fun parsesEncodedSessionId() {
        assertEquals(
            TalariaDeepLink.Session("session id+1"),
            TalariaDeepLinkParser.parse("talaria://session/session%20id%2B1"),
        )
    }

    @Test
    fun parsesDecodedManagementProfile() {
        assertEquals(
            TalariaDeepLink.Connect("work/profile"),
            TalariaDeepLinkParser.parse("talaria://connect?profile=work%2Fprofile"),
        )
    }

    @Test
    fun parsesNotificationConnectionScope() {
        assertEquals(
            TalariaDeepLink.Session("abc def", "connection-1", "work/profile"),
            TalariaDeepLinkParser.parse(
                "talaria://session/abc%20def?connection=connection-1&profile=work%2Fprofile",
            ),
        )
    }

    @Test
    fun doesNotRouteOnSubstringMatches() {
        assertNull(TalariaDeepLinkParser.parse("https://example.test/path/pairing"))
        assertNull(TalariaDeepLinkParser.parse("talaria://unknown?next=session/secret"))
    }

    @Test
    fun rejectsExtraSessionPathSegments() {
        assertNull(TalariaDeepLinkParser.parse("talaria://session/one/two"))
        assertNull(TalariaDeepLinkParser.parse("talaria://session/one%2Ftwo"))
    }
}
