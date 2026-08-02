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

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** The app-supported locale overrides shown in You > Language. */
enum class AppLocale(val languageTag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    JAPANESE("ja"),
    SIMPLIFIED_CHINESE("zh"),
    TRADITIONAL_CHINESE("zh-TW"),
    ARABIC("ar"),
    ;

    companion object {
        /** Resolve stored/user-provided tags to a supported locale, safely falling back to system. */
        fun fromLanguageTag(tag: String?): AppLocale {
            val normalized = tag
                ?.trim()
                ?.replace('_', '-')
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotEmpty() }
                ?: return SYSTEM
            return when {
                normalized == "system" || normalized == "default" -> SYSTEM
                normalized == "en" || normalized.startsWith("en-") -> ENGLISH
                normalized == "ja" || normalized.startsWith("ja-") -> JAPANESE
                normalized == "zh-hant" || normalized.startsWith("zh-hant-") ||
                    normalized == "zh-tw" || normalized.startsWith("zh-tw-") ||
                    normalized == "zh-hk" || normalized.startsWith("zh-hk-") ||
                    normalized == "zh-mo" || normalized.startsWith("zh-mo-") -> TRADITIONAL_CHINESE
                normalized == "zh" || normalized.startsWith("zh-") -> SIMPLIFIED_CHINESE
                normalized == "ar" || normalized.startsWith("ar-") -> ARABIC
                else -> SYSTEM
            }
        }
    }
}

private fun AppLocale.toLocaleList(): LocaleList = when (this) {
    AppLocale.SYSTEM -> LocaleList.getEmptyLocaleList()
    else -> LocaleList.forLanguageTags(languageTag.orEmpty())
}

/** Applies the persisted locale using Android's per-app API where available. */
class LocaleManager(private val settingsStore: SettingsStore) {
    fun currentLocale(): AppLocale = AppLocale.fromLanguageTag(settingsStore.localeTag)

    fun setLocale(context: Context, locale: AppLocale) {
        settingsStore.localeTag = locale.languageTag
        apply(context, locale)
    }

    fun apply(context: Context) = apply(context, currentLocale())

    private fun apply(context: Context, locale: AppLocale) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val platformManager = context.getSystemService(android.app.LocaleManager::class.java) ?: return
        platformManager.applicationLocales = locale.toLocaleList()
    }

    companion object {
        /** Wrap a pre-33 context so resources follow the persisted app override. */
        fun wrap(context: Context): Context {
            val locale = AppLocale.fromLanguageTag(SettingsStore(context).localeTag)
            if (locale == AppLocale.SYSTEM) return context
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocales(locale.toLocaleList())
            return context.createConfigurationContext(configuration)
        }
    }
}
