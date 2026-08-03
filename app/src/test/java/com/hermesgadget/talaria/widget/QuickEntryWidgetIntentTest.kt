/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */

package com.hermesgadget.talaria.widget

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QuickEntryWidgetIntentTest {
    private val packageName = ApplicationProvider.getApplicationContext<android.content.Context>().packageName

    @Test
    fun newChatIntentTargetsComposerDeepLink() {
        val intent = QuickEntryWidgetIntents.newChat(packageName)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(packageName, intent.`package`)
        assertEquals("chat", intent.data?.host)
        assertEquals("composer", intent.data?.getQueryParameter("focus"))
        assertNotNull(intent.data)
    }

    @Test
    fun talkIntentTargetsVoiceScreen() {
        val intent = QuickEntryWidgetIntents.talk(packageName)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("voice", intent.data?.host)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }
}
