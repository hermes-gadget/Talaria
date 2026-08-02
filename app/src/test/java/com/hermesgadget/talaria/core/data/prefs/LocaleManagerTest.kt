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

package com.hermesgadget.talaria.core.data.prefs

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LocaleManagerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPreferences()
    }

    @After
    fun tearDown() = clearPreferences()

    @Test
    fun localeOverridePersistsAcrossManagerRecreation() {
        LocaleManager(SettingsStore(context)).setLocale(context, AppLocale.JAPANESE)

        assertEquals("ja", SettingsStore(context).localeTag)
        assertEquals(AppLocale.JAPANESE, LocaleManager(SettingsStore(context)).currentLocale())
    }

    @Test
    fun selectingSystemDefaultClearsOverride() {
        val manager = LocaleManager(SettingsStore(context))
        manager.setLocale(context, AppLocale.ARABIC)
        manager.setLocale(context, AppLocale.SYSTEM)

        assertNull(SettingsStore(context).localeTag)
        assertEquals(AppLocale.SYSTEM, manager.currentLocale())
    }

    @Test
    fun supportedAndUnknownTagsResolveSafely() {
        assertEquals(AppLocale.TRADITIONAL_CHINESE, AppLocale.fromLanguageTag("zh-Hant-TW"))
        assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.fromLanguageTag("zh-CN"))
        assertEquals(AppLocale.ENGLISH, AppLocale.fromLanguageTag("en-GB"))
        assertEquals(AppLocale.SYSTEM, AppLocale.fromLanguageTag("xx-YY"))
        assertEquals(AppLocale.SYSTEM, AppLocale.fromLanguageTag(null))
    }

    @Test
    fun preTiramisuContextUsesPersistedLocale() {
        SettingsStore(context).localeTag = AppLocale.ARABIC.languageTag

        val wrapped = LocaleManager.wrap(context)

        assertEquals("ar", wrapped.resources.configuration.locales[0].language)
    }

    private fun clearPreferences() {
        context.getSharedPreferences("talaria_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
