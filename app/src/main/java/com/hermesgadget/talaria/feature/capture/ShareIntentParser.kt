/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.capture

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build

/** Converts Android's several share representations into one ordered payload. */
internal object ShareIntentParser {
    fun parse(intent: Intent): ShareIntentPayload {
        val text = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        }?.toString().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val uris = buildList {
            val clipData = intent.clipData
            if (clipData != null) addAll(clipDataUris(clipData))
            when (intent.action) {
                Intent.ACTION_SEND -> singleStream(intent)?.let(::add)
                Intent.ACTION_SEND_MULTIPLE -> addAll(multipleStreams(intent))
            }
        }.map(Uri::toString)

        return ShareIntentPayload(
            action = intent.action,
            mimeType = intent.type,
            text = text,
            subject = subject,
            uriStrings = dedupeUris(uris),
        )
    }

    fun dedupeUris(uris: List<String>): List<String> = buildList {
        val seen = mutableSetOf<String>()
        uris.forEach { raw ->
            val normalized = runCatching { ShareIntakePolicy.normalizeUri(raw) }.getOrNull().orEmpty()
            if (normalized.isNotBlank() && seen.add(normalized)) add(normalized)
        }
    }

    private fun clipDataUris(clipData: ClipData): List<Uri> = buildList {
        for (index in 0 until clipData.itemCount) {
            clipData.getItemAt(index).uri?.let(::add)
        }
    }

    private fun singleStream(intent: Intent): Uri? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    private fun multipleStreams(intent: Intent): List<Uri> = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    }
}
