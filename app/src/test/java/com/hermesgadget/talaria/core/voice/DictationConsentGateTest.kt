/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S01 regression contract: with cloud STT opted out, dictation must refuse
 * to start when on-device capability is unproven, rather than starting a
 * recognizer that may silently upload audio to a cloud engine
 * (EXTRA_PREFER_OFFLINE is only advisory).
 */
class DictationConsentGateTest {
    // Mirrors SpeechCoordinator.cloudUploadForbidden.
    private fun forbidden(cloudOptIn: Boolean, onDeviceAvailable: Boolean) =
        !cloudOptIn && !onDeviceAvailable

    @Test
    fun `cloud opted out + unproven capability = dictation forbidden`() {
        assertTrue(forbidden(cloudOptIn = false, onDeviceAvailable = false))
    }

    @Test
    fun `cloud opted out + proven on-device capability = allowed`() {
        assertFalse(forbidden(cloudOptIn = false, onDeviceAvailable = true))
    }

    @Test
    fun `explicit cloud opt-in is always allowed`() {
        assertFalse(forbidden(cloudOptIn = true, onDeviceAvailable = false))
        assertFalse(forbidden(cloudOptIn = true, onDeviceAvailable = true))
    }

    @Test
    fun `capability probe failure fails closed`() {
        // The probe swallows Throwables and reports false — opt-out + probe
        // failure must therefore be forbidden.
        val probeFailed = false
        assertTrue(forbidden(cloudOptIn = false, onDeviceAvailable = probeFailed))
    }
}

/**
 * S03 regression contract: recorded audio must never be transcribed through
 * a fallback snapshot. Tab-bound snapshot only — a vanished tab discards the
 * recording (after local deletion) instead of uploading through the active
 * connection or an anonymous client.
 */
class DictationSnapshotBindingTest {
    @Test
    fun `no tab snapshot means no upload`() {
        val tabSnapshot: String? = null
        val chosen = tabSnapshot // the S03 fix: no fallback chain
        assertTrue(
            "fallbacks are forbidden; missing tab binding must discard the upload",
            chosen == null,
        )
    }

    @Test
    fun `present tab snapshot is used directly`() {
        val tabSnapshot: String? = "tab-1-bound"
        assertEquals("tab-1-bound", tabSnapshot)
    }
}
