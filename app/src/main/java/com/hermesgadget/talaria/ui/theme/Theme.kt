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

package com.hermesgadget.talaria.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.prefs.ThemeMode

@Composable
fun TalariaTheme(content: @Composable () -> Unit) {
    val settings = TalariaApp.instance.container.settingsStore
    val themeMode by settings.themeModeFlow.collectAsState()
    val dynamicColor by settings.dynamicColorFlow.collectAsState()
    val themePreset by settings.themePresetFlow.collectAsState()
    val serverSkin by ThemeOverrides.skin.collectAsState()
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val scheme = talariaColorScheme(
        darkTheme = dark,
        dynamicColor = dynamicColor,
        preset = ThemePresets.byId(themePreset),
        serverSkin = serverSkin,
    )
    CompositionLocalProvider(LocalSpacing provides Spacing()) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TalariaTypography,
            shapes = TalariaShapes,
            content = content,
        )
    }
}

@Composable
@ReadOnlyComposable
fun talariaColorScheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    preset: ThemePreset = ThemePresets.DARK,
    serverSkin: ThemeSkin? = null,
): ColorScheme {
    val context = LocalContext.current
    return when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        serverSkin != null -> preset.withServerSkin(serverSkin).scheme(darkTheme)
        else -> preset.scheme(darkTheme)
    }
}

/** Whether the active scheme is dark (for system bar icon contrast). */
@Composable
@ReadOnlyComposable
fun isTalariaDarkTheme(): Boolean {
    val settings = TalariaApp.instance.container.settingsStore
    return when (settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
}
