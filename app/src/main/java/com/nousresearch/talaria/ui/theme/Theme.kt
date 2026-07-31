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


package com.nousresearch.talaria.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.nousresearch.talaria.TalariaApp

private val HermesDark = darkColorScheme(
    primary = HermesEmber,
    onPrimary = HermesVoid,
    secondary = HermesWing,
    onSecondary = HermesVoid,
    tertiary = HermesMist,
    background = HermesVoid,
    onBackground = HermesMist,
    surface = HermesInk,
    onSurface = HermesMist,
    surfaceVariant = HermesPanel,
    onSurfaceVariant = HermesMist,
    error = HermesDanger,
)

private val HermesLight = lightColorScheme(
    primary = Color(0xFF8A5520),
    onPrimary = Color.White,
    secondary = Color(0xFF2F5F99),
    background = Color(0xFFF4F6FA),
    surface = Color.White,
    onBackground = Color(0xFF12151C),
    onSurface = Color(0xFF12151C),
)

@Composable
fun TalariaTheme(content: @Composable () -> Unit) {
    val dynamic = TalariaApp.instance.container.settingsStore.dynamicColor
    val dark = isSystemInDarkTheme() || true // Hermes aesthetic defaults to dark
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 && !dark -> dynamicLightColorScheme(context)
        dynamic && Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context).copy(
            background = HermesVoid,
            surface = HermesInk,
        )
        dark -> HermesDark
        else -> HermesLight
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = TalariaTypography,
        content = content,
    )
}
