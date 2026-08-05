/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.feature.capture

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareIntentParserTest {
    @Test
    fun `multiple streams and clip data are merged with stable uri dedupe`() {
        val first = Uri.parse("content://first")
        val second = Uri.parse("content://second")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
        // Robolectric's instrumented setClipData() returns void (unlike the
        // SDK's fluent Intent), so it cannot be chained.
        intent.setClipData(ClipData.newRawUri("shared", first))
        intent.putExtra(
            Intent.EXTRA_STREAM,
            arrayListOf(first, second),
        )

        val payload = ShareIntentParser.parse(intent)

        assertEquals(listOf(first.toString(), second.toString()), payload.uriStrings)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, payload.action)
    }

    @Test
    fun `process text reads selected text rather than ordinary text extra`() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "wrong")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, "selected")

        assertEquals("selected", ShareIntentParser.parse(intent).text)
    }
}
