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

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A server-provided subset of colors that can be safely applied to a preset. */
data class ThemeSkin(
    val primary: Color? = null,
    val accent: Color? = null,
    val background: Color? = null,
) {
    val isEmpty: Boolean
        get() = primary == null && accent == null && background == null

    val supportedFields: Set<String>
        get() = buildSet {
            if (primary != null) add("primary")
            if (accent != null) add("accent")
            if (background != null) add("background")
        }
}

/** A named, complete palette pair used by the app theme and the picker UI. */
data class ThemePreset(
    val id: String,
    val displayName: String,
    val description: String,
    val darkScheme: ColorScheme,
    val lightScheme: ColorScheme,
    val monochromeAccent: Color,
) {
    fun scheme(darkTheme: Boolean, monochrome: Boolean = false): ColorScheme {
        val base = if (darkTheme) darkScheme else lightScheme
        if (!monochrome) return base
        return base.copy(
            primary = monochromeAccent,
            secondary = monochromeAccent,
            tertiary = monochromeAccent,
            surfaceTint = monochromeAccent,
        )
    }

    /** Apply only server fields that are present, preserving the rest of the preset. */
    fun withServerSkin(skin: ThemeSkin): ThemePreset = copy(
        darkScheme = darkScheme.withServerSkin(skin),
        lightScheme = lightScheme.withServerSkin(skin),
    )
}

/** Process-local skin overrides are intentionally not persisted; the preset key is. */
object ThemeOverrides {
    private val _skin = MutableStateFlow<ThemeSkin?>(null)
    val skin: StateFlow<ThemeSkin?> = _skin.asStateFlow()

    fun apply(skin: ThemeSkin) {
        _skin.value = skin.takeUnless { it.isEmpty }
    }

    fun clear() {
        _skin.value = null
    }
}

private fun ColorScheme.withServerSkin(skin: ThemeSkin): ColorScheme = copy(
    primary = skin.primary ?: primary,
    secondary = skin.accent ?: secondary,
    tertiary = skin.accent ?: tertiary,
    background = skin.background ?: background,
)

/**
 * All palette values live in one data-driven registry. The generated schemes
 * fill every Material 3 role, including the newer surface-container roles.
 */
object ThemePresets {
    val DARK = ThemePreset(
        id = "dark",
        displayName = "Dark",
        description = "Hermes ember on a deep blue-black canvas",
        darkScheme = PaletteSeed(
            primary = color(0xFFE8A45CL),
            primaryContainer = color(0xFF3D2A14L),
            secondary = color(0xFF7EB8FFL),
            secondaryContainer = color(0xFF1A3355L),
            tertiary = color(0xFFAAB3C4L),
            tertiaryContainer = color(0xFF242B3AL),
            background = color(0xFF0B0D12L),
            surface = color(0xFF12151CL),
            surfaceVariant = color(0xFF1A1F2BL),
            onBackground = color(0xFFE9EDF5L),
            onSurface = color(0xFFE9EDF5L),
            onSurfaceVariant = color(0xFFAAB3C4L),
            outline = color(0xFF333B4AL),
            outlineVariant = color(0xFF2A3140L),
            error = color(0xFFEB5757L),
        ).toScheme(dark = true),
        lightScheme = PaletteSeed(
            primary = color(0xFF8A5520L),
            primaryContainer = color(0xFFFFDDB8L),
            secondary = color(0xFF2F5F99L),
            secondaryContainer = color(0xFFD3E4FFL),
            tertiary = color(0xFF5B5B7AL),
            tertiaryContainer = color(0xFFE1E0FFL),
            background = color(0xFFF4F6FAL),
            surface = Color.White,
            surfaceVariant = color(0xFFE8EAF0L),
            onBackground = color(0xFF12151CL),
            onSurface = color(0xFF12151CL),
            onSurfaceVariant = color(0xFF44474FL),
            outline = color(0xFFC5CAD6L),
            outlineVariant = color(0xFFC4C6D0L),
            error = color(0xFFBA1A1AL),
        ).toScheme(dark = false),
        monochromeAccent = color(0xFFC8CDD8L),
    )

    val LIGHT = ThemePreset(
        id = "light",
        displayName = "Light",
        description = "Warm paper, clear ink, and a focused amber accent",
        darkScheme = PaletteSeed(
            primary = color(0xFFFFB866L),
            primaryContainer = color(0xFF5A3A13L),
            secondary = color(0xFF9EC5FFL),
            secondaryContainer = color(0xFF24456CL),
            tertiary = color(0xFFD6B9FFL),
            tertiaryContainer = color(0xFF493B62L),
            background = color(0xFF15110DL),
            surface = color(0xFF1E1813L),
            surfaceVariant = color(0xFF2B221BL),
            onBackground = color(0xFFF4E9DCL),
            onSurface = color(0xFFF4E9DCL),
            onSurfaceVariant = color(0xFFD0C0B1L),
            outline = color(0xFF594D43L),
            outlineVariant = color(0xFF3D332CL),
            error = color(0xFFFFB4ABL),
        ).toScheme(dark = true),
        lightScheme = PaletteSeed(
            primary = color(0xFF8C4E00L),
            primaryContainer = color(0xFFFFDDB8L),
            secondary = color(0xFF42648FL),
            secondaryContainer = color(0xFFD5E4FFL),
            tertiary = color(0xFF6C567CL),
            tertiaryContainer = color(0xFFF2DAFFL),
            background = color(0xFFFFF8F2L),
            surface = Color.White,
            surfaceVariant = color(0xFFF4E3D5L),
            onBackground = color(0xFF211A14L),
            onSurface = color(0xFF211A14L),
            onSurfaceVariant = color(0xFF51453BL),
            outline = color(0xFF82756AL),
            outlineVariant = color(0xFFD5C4B5L),
            error = color(0xFFBA1A1AL),
        ).toScheme(dark = false),
        monochromeAccent = color(0xFF5A5D65L),
    )

    val SOLARIZED = ThemePreset(
        id = "solarized",
        displayName = "Solarized",
        description = "The balanced blue-green contrast of Solarized",
        darkScheme = PaletteSeed(
            primary = color(0xFFB58900L),
            primaryContainer = color(0xFF5F4A00L),
            secondary = color(0xFF268BD2L),
            secondaryContainer = color(0xFF12466DL),
            tertiary = color(0xFF2AA198L),
            tertiaryContainer = color(0xFF145B58L),
            background = color(0xFF002B36L),
            surface = color(0xFF073642L),
            surfaceVariant = color(0xFF0B4652L),
            onBackground = color(0xFFEEE8D5L),
            onSurface = color(0xFFEEE8D5L),
            onSurfaceVariant = color(0xFF93A1A1L),
            outline = color(0xFF586E75L),
            outlineVariant = color(0xFF214A55L),
            error = color(0xFFDC322FL),
        ).toScheme(dark = true),
        lightScheme = PaletteSeed(
            primary = color(0xFF8A6800L),
            primaryContainer = color(0xFFF8E6A3L),
            secondary = color(0xFF1769A5L),
            secondaryContainer = color(0xFFC6E4F8L),
            tertiary = color(0xFF187D75L),
            tertiaryContainer = color(0xFFBDE8E2L),
            background = color(0xFFFDF6E3L),
            surface = color(0xFFEEE8D5L),
            surfaceVariant = color(0xFFE6DFCCL),
            onBackground = color(0xFF073642L),
            onSurface = color(0xFF073642L),
            onSurfaceVariant = color(0xFF586E75L),
            outline = color(0xFF839496L),
            outlineVariant = color(0xFFC7C9B8L),
            error = color(0xFFB52A28L),
        ).toScheme(dark = false),
        monochromeAccent = color(0xFF93A1A1L),
    )

    val NORD = ThemePreset(
        id = "nord",
        displayName = "Nord",
        description = "Arctic blue accents over Polar Night and Snow Storm",
        darkScheme = PaletteSeed(
            primary = color(0xFF88C0D0L),
            primaryContainer = color(0xFF3F606BL),
            secondary = color(0xFF81A1C1L),
            secondaryContainer = color(0xFF3A4E66L),
            tertiary = color(0xFFA3BE8CL),
            tertiaryContainer = color(0xFF46583FL),
            background = color(0xFF2E3440L),
            surface = color(0xFF3B4252L),
            surfaceVariant = color(0xFF434C5EL),
            onBackground = color(0xFFECEFF4L),
            onSurface = color(0xFFECEFF4L),
            onSurfaceVariant = color(0xFFD8DEE9L),
            outline = color(0xFF616E82L),
            outlineVariant = color(0xFF4C566AL),
            error = color(0xFFBF616AL),
        ).toScheme(dark = true),
        lightScheme = PaletteSeed(
            primary = color(0xFF5E81ACL),
            primaryContainer = color(0xFFD7EAF0L),
            secondary = color(0xFF4C6A8AL),
            secondaryContainer = color(0xFFD9E4F0L),
            tertiary = color(0xFF5C7D4CL),
            tertiaryContainer = color(0xFFDDEAD5L),
            background = color(0xFFECEFF4L),
            surface = color(0xFFE5E9F0L),
            surfaceVariant = color(0xFFD8DEE9L),
            onBackground = color(0xFF2E3440L),
            onSurface = color(0xFF2E3440L),
            onSurfaceVariant = color(0xFF4C566AL),
            outline = color(0xFF7B8798L),
            outlineVariant = color(0xFFB7C0CFL),
            error = color(0xFF9D3D47L),
        ).toScheme(dark = false),
        monochromeAccent = color(0xFFD8DEE9L),
    )

    val DRACULA = ThemePreset(
        id = "dracula",
        displayName = "Dracula",
        description = "Violet, cyan, and pink accents on a midnight canvas",
        darkScheme = PaletteSeed(
            primary = color(0xFFBD93F9L),
            primaryContainer = color(0xFF563D74L),
            secondary = color(0xFF8BE9FDL),
            secondaryContainer = color(0xFF245A69L),
            tertiary = color(0xFFFF79C6L),
            tertiaryContainer = color(0xFF713B5CL),
            background = color(0xFF282A36L),
            surface = color(0xFF303241L),
            surfaceVariant = color(0xFF44475AL),
            onBackground = color(0xFFF8F8F2L),
            onSurface = color(0xFFF8F8F2L),
            onSurfaceVariant = color(0xFFB8B8C7L),
            outline = color(0xFF6272A4L),
            outlineVariant = color(0xFF4C4E61L),
            error = color(0xFFFF5555L),
        ).toScheme(dark = true),
        lightScheme = PaletteSeed(
            primary = color(0xFF7046A8L),
            primaryContainer = color(0xFFE8DAFFL),
            secondary = color(0xFF147A8EL),
            secondaryContainer = color(0xFFC5F0F7L),
            tertiary = color(0xFFA51F65L),
            tertiaryContainer = color(0xFFFFD9E8L),
            background = color(0xFFF8F8F2L),
            surface = Color.White,
            surfaceVariant = color(0xFFE9E7F2L),
            onBackground = color(0xFF282A36L),
            onSurface = color(0xFF282A36L),
            onSurfaceVariant = color(0xFF55566BL),
            outline = color(0xFF77788FL),
            outlineVariant = color(0xFFC9C7D5L),
            error = color(0xFFB3261EL),
        ).toScheme(dark = false),
        monochromeAccent = color(0xFFF8F8F2L),
    )

    val GRUVBOX = ThemePreset(
        id = "gruvbox",
        displayName = "Gruvbox",
        description = "Earthy retro tones with a soft, readable contrast",
        darkScheme = PaletteSeed(
            primary = color(0xFFFABD2FL),
            primaryContainer = color(0xFF665C30L),
            secondary = color(0xFF83A598L),
            secondaryContainer = color(0xFF3E5A55L),
            tertiary = color(0xFFD3869BL),
            tertiaryContainer = color(0xFF68434FL),
            background = color(0xFF282828L),
            surface = color(0xFF32302FL),
            surfaceVariant = color(0xFF3C3836L),
            onBackground = color(0xFFEBDBB2L),
            onSurface = color(0xFFEBDBB2L),
            onSurfaceVariant = color(0xFFBDAE93L),
            outline = color(0xFF7C6F64L),
            outlineVariant = color(0xFF504945L),
            error = color(0xFFFB4934L),
        ).toScheme(dark = true),
        lightScheme = PaletteSeed(
            primary = color(0xFF9D5B00L),
            primaryContainer = color(0xFFF5D98BL),
            secondary = color(0xFF427B58L),
            secondaryContainer = color(0xFFCFE7D4L),
            tertiary = color(0xFF8F3F71L),
            tertiaryContainer = color(0xFFF4D7E8L),
            background = color(0xFFFBF1C7L),
            surface = color(0xFFFFF8DDL),
            surfaceVariant = color(0xFFEBDBB2L),
            onBackground = color(0xFF3C3836L),
            onSurface = color(0xFF3C3836L),
            onSurfaceVariant = color(0xFF665C54L),
            outline = color(0xFF928374L),
            outlineVariant = color(0xFFD5C4A1L),
            error = color(0xFFCC241DL),
        ).toScheme(dark = false),
        monochromeAccent = color(0xFFEBDBB2L),
    )

    val all: List<ThemePreset> = listOf(DARK, LIGHT, SOLARIZED, NORD, DRACULA, GRUVBOX)

    fun byId(id: String?): ThemePreset = all.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: DARK
}

private data class PaletteSeed(
    val primary: Color,
    val primaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val error: Color,
)

private fun PaletteSeed.toScheme(dark: Boolean): ColorScheme {
    val onPrimary = readableOn(primary)
    val onPrimaryContainer = readableOn(primaryContainer)
    val onSecondary = readableOn(secondary)
    val onSecondaryContainer = readableOn(secondaryContainer)
    val onTertiary = readableOn(tertiary)
    val onTertiaryContainer = readableOn(tertiaryContainer)
    val onError = readableOn(error)
    val errorContainer = if (dark) color(0xFF5C1A1AL) else color(0xFFFFDAD6L)
    val onErrorContainer = readableOn(errorContainer)
    val surfaceBright = if (dark) surfaceVariant else Color.White
    val surfaceDim = if (dark) background else surfaceVariant
    val surfaceContainer = if (dark) surface else surfaceVariant
    val surfaceContainerHigh = surfaceVariant
    val surfaceContainerHighest = surfaceVariant
    val surfaceContainerLow = if (dark) background else surface
    val surfaceContainerLowest = if (dark) background else Color.White

    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = primary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
            inverseSurface = onSurface,
            inverseOnSurface = surface,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = primary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
            inverseSurface = onSurface,
            inverseOnSurface = surface,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = Color.Black,
        )
    }
}

private fun readableOn(color: Color): Color {
    val luminance = (0.299f * color.red) + (0.587f * color.green) + (0.114f * color.blue)
    return if (luminance > 0.62f) color(0xFF171717L) else Color.White
}

private fun color(value: Long): Color = Color(value.toInt())
