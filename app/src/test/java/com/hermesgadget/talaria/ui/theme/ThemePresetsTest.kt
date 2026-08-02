/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePresetsTest {
    @Test
    fun registryContainsAllDesktopPresetsAndBothSchemes() {
        assertEquals(
            listOf("dark", "light", "solarized", "nord", "dracula", "gruvbox"),
            ThemePresets.all.map(ThemePreset::id),
        )

        ThemePresets.all.forEach { preset ->
            assertNotEquals(Color.Unspecified, preset.darkScheme.primary)
            assertNotEquals(Color.Unspecified, preset.lightScheme.background)
            assertNotEquals(Color.Unspecified, preset.monochromeAccent)
            assertNotEquals(preset.darkScheme.background, preset.lightScheme.background)
            assertEquals(preset.monochromeAccent, preset.scheme(darkTheme = true, monochrome = true).primary)
        }
    }

    @Test
    fun lookupIsCaseInsensitiveAndUnknownIdsUseDarkDefault() {
        assertEquals(ThemePresets.NORD, ThemePresets.byId("NORD"))
        assertEquals(ThemePresets.DARK, ThemePresets.byId("missing"))
        assertTrue(ThemePresets.byId(null) === ThemePresets.DARK)
    }

    @Test
    fun serverSkinOnlyOverridesSupportedRoles() {
        val skin = ThemeSkin(
            primary = Color(0xFFFF0000),
            background = Color(0xFF101010),
        )
        val recolored = ThemePresets.NORD.withServerSkin(skin)

        assertEquals(skin.primary, recolored.darkScheme.primary)
        assertEquals(ThemePresets.NORD.darkScheme.secondary, recolored.darkScheme.secondary)
        assertEquals(skin.background, recolored.lightScheme.background)
    }
}
