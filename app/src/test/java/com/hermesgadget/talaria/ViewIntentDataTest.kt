/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for B01: externally supplied ACTION_VIEW URIs must be
 * inspected without assuming they are hierarchical. An explicit
 * `talaria:opaque` launch must not crash with IllegalArgumentException from
 * Uri.getQueryParameter before the navigation parser runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ViewIntentDataTest {
    private fun parse(value: String?) = parseViewIntentData(value?.let { Uri.parse(it) })

    @Test
    fun `opaque deep link is forwarded without crashing`() {
        val result = parse("talaria:opaque")
        assertEquals("talaria:opaque", result.deepLink)
        assertFalse(result.focusComposer)
    }

    @Test
    fun `opaque deep link with focus parameter does not treat it as a query`() {
        // Opaque URIs cannot carry query parameters; the whole thing is the
        // scheme-specific part. It must be forwarded untouched, not parsed.
        val result = parse("talaria:focus=composer")
        assertEquals("talaria:focus=composer", result.deepLink)
        assertFalse(result.focusComposer)
    }

    @Test
    fun `hierarchical composer link requests composer focus`() {
        val result = parse("talaria://chat?focus=composer")
        assertEquals("talaria://chat?focus=composer", result.deepLink)
        assertEquals(true, result.focusComposer)
    }

    @Test
    fun `hierarchical link without focus does not request composer`() {
        val result = parse("talaria://chat/session/abc")
        assertFalse(result.focusComposer)
    }

    @Test
    fun `null data yields no deep link and no focus`() {
        val result = parse(null)
        assertEquals(null, result.deepLink)
        assertFalse(result.focusComposer)
    }
}
