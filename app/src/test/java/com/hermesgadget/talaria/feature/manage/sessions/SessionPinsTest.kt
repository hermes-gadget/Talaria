/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.manage.sessions

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionPinsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clear()
    }

    @After
    fun tearDown() = clear()

    @Test
    fun pinsSurviveStoreRecreationWithinOneConnectionScope() {
        SharedPreferencesSessionPinStore(context).setPinned("connection-a", "session-1", true)

        assertEquals(
            setOf("session-1"),
            SharedPreferencesSessionPinStore(context).load("connection-a"),
        )
    }

    @Test
    fun connectionAndManagementProfileScopesStayIsolated() {
        val store = SharedPreferencesSessionPinStore(context)
        store.setPinned("connection-a|profile|work", "session-1", true)
        store.setPinned("connection-b|profile|work", "session-2", true)

        assertEquals(setOf("session-1"), store.load("connection-a|profile|work"))
        assertEquals(setOf("session-2"), store.load("connection-b|profile|work"))
        assertTrue(store.load("connection-a").isEmpty())
    }

    private fun clear() {
        contextOrNull()?.getSharedPreferences("talaria_settings", Context.MODE_PRIVATE)?.edit()?.clear()?.commit()
    }

    private fun contextOrNull(): Context? =
        if (::context.isInitialized) context else ApplicationProvider.getApplicationContext()
}
