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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.prefs.ThemeMode

private val HermesDarkScheme = darkColorScheme(
    primary = HermesEmber,
    onPrimary = HermesVoid,
    primaryContainer = HermesEmberContainer,
    onPrimaryContainer = HermesEmber,
    secondary = HermesWing,
    onSecondary = HermesVoid,
    secondaryContainer = HermesWingContainer,
    onSecondaryContainer = HermesWing,
    tertiary = HermesMist,
    onTertiary = HermesVoid,
    tertiaryContainer = HermesPanelHigh,
    onTertiaryContainer = HermesMist,
    background = HermesVoid,
    onBackground = HermesText,
    surface = HermesInk,
    onSurface = HermesText,
    surfaceVariant = HermesPanel,
    onSurfaceVariant = HermesMist,
    surfaceTint = HermesEmber,
    surfaceBright = HermesPanelHigh,
    surfaceDim = HermesVoid,
    surfaceContainer = HermesInk,
    surfaceContainerHigh = HermesPanel,
    surfaceContainerHighest = HermesPanelHigh,
    surfaceContainerLow = Color(0xFF0E1118),
    surfaceContainerLowest = HermesVoid,
    inverseSurface = HermesMist,
    inverseOnSurface = HermesVoid,
    inversePrimary = HermesLightPrimary,
    error = HermesDanger,
    onError = Color.White,
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = HermesOutline,
    outlineVariant = Color(0xFF2A3140),
    scrim = Color.Black,
)

private val HermesLightScheme = lightColorScheme(
    primary = HermesLightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB8),
    onPrimaryContainer = Color(0xFF2C1600),
    secondary = HermesLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E4FF),
    onSecondaryContainer = Color(0xFF001D36),
    tertiary = Color(0xFF5B5B7A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE1E0FF),
    onTertiaryContainer = Color(0xFF181833),
    background = HermesLightBg,
    onBackground = HermesLightOn,
    surface = HermesLightSurface,
    onSurface = HermesLightOn,
    surfaceVariant = Color(0xFFE8EAF0),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceTint = HermesLightPrimary,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDDDFE5),
    surfaceContainer = Color(0xFFEEF0F6),
    surfaceContainerHigh = Color(0xFFE8EAF0),
    surfaceContainerHighest = Color(0xFFE2E4EA),
    surfaceContainerLow = Color(0xFFF4F6FA),
    surfaceContainerLowest = Color.White,
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    inversePrimary = HermesEmber,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = HermesLightOutline,
    outlineVariant = Color(0xFFC4C6D0),
    scrim = Color.Black,
)

@Composable
fun TalariaTheme(content: @Composable () -> Unit) {
    val settings = TalariaApp.instance.container.settingsStore
    val themeMode by settings.themeModeFlow.collectAsState()
    val dynamicColor by settings.dynamicColorFlow.collectAsState()
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val scheme = talariaColorScheme(
        darkTheme = dark,
        dynamicColor = dynamicColor,
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
fun talariaColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme {
    val context = LocalContext.current
    return when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> HermesDarkScheme
        else -> HermesLightScheme
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
