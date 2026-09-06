/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.hermesgadget.talaria.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U18: server skin overrides must not erase foreground/background contrast —
 * overriding primary/accent/background recomputes the matching on-colors by
 * luminance so text/icons on the overridden roles stay readable.
 */
class ThemeSkinContrastTest {

    @Test
    fun `white skin primary on dark scheme flips onPrimary to black`() {
        val preset = ThemePresets.DARK
        val skin = ThemeSkin(primary = Color.White)
        val skinned = preset.withServerSkin(skin).darkScheme

        assertEquals(Color.White, skinned.primary)
        assertEquals(Color.Black, skinned.onPrimary)
    }

    @Test
    fun `black skin accent flips onSecondary and onTertiary to white`() {
        val preset = ThemePresets.DARK
        val skin = ThemeSkin(accent = Color.Black)
        val skinned = preset.withServerSkin(skin).darkScheme

        assertEquals(Color.Black, skinned.secondary)
        assertEquals(Color.White, skinned.onSecondary)
        assertEquals(Color.White, skinned.onTertiary)
    }

    @Test
    fun `white skin background keeps onBackground and surface readable`() {
        val preset = ThemePresets.DARK
        val skin = ThemeSkin(background = Color.White)
        val skinned = preset.withServerSkin(skin).darkScheme

        assertEquals(Color.White, skinned.background)
        assertEquals(Color.Black, skinned.onBackground)
        assertEquals(Color.White, skinned.surface)
        assertEquals(Color.Black, skinned.onSurface)
    }

    @Test
    fun `luminance boundary splits on-color both ways in light scheme`() {
        val preset = ThemePresets.LIGHT ?: ThemePresets.all.first()
        val white = preset.withServerSkin(ThemeSkin(primary = Color.White)).lightScheme
        val black = preset.withServerSkin(ThemeSkin(primary = Color.Black)).lightScheme

        assertEquals(Color.Black, white.onPrimary)
        assertEquals(Color.White, black.onPrimary)
    }

    @Test
    fun `empty skin keeps preset colors and consistent contrast pairs`() {
        val preset = ThemePresets.DARK
        val before = preset.darkScheme
        val after = preset.withServerSkin(ThemeSkin()).darkScheme

        // No overrides: every color role keeps the preset value…
        assertEquals(before.primary, after.primary)
        assertEquals(before.secondary, after.secondary)
        assertEquals(before.background, after.background)
        // …and surface stays aligned with background with a contrast-safe onSurface.
        assertEquals(after.background, after.surface)
        assertEquals(after.onBackground, after.onSurface)
    }
}
