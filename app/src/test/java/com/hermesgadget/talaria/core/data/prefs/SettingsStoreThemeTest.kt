/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.core.data.prefs

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsStoreThemeTest {
    private lateinit var context: Context
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clear()
        store = SettingsStore(context)
    }

    @After
    fun tearDown() = clear()

    @Test
    fun themePresetRoundTripsAcrossStoreRecreation() {
        assertEquals("dark", store.themePreset)

        store.themePreset = "nord"

        assertEquals("nord", SettingsStore(context).themePreset)
        assertEquals("nord", SettingsStore(context).themePresetFlow.value)
    }

    private fun clear() {
        contextOrNull()?.getSharedPreferences("talaria_settings", Context.MODE_PRIVATE)?.edit()?.clear()?.commit()
    }

    private fun contextOrNull(): Context? =
        if (::context.isInitialized) context else ApplicationProvider.getApplicationContext()
}
