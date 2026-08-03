/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.voice

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceAudioDataUrlTest {
    @Test
    fun `recording encoder preserves the data URL contract`() {
        val file = File.createTempFile("talaria-voice-test-", ".m4a")
        try {
            file.writeBytes("hello".toByteArray())

            assertEquals(
                "data:audio/mp4;base64,aGVsbG8=",
                encodeRecordedVoiceDataUrl(file, "audio/mp4"),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `decoder supports bounded base64 and URL encoded payloads`() {
        val base64 = decodeVoiceAudioDataUrl(
            "data:audio/mpeg;base64,aGVsbG8=",
            File(System.getProperty("java.io.tmpdir")),
        )
        val urlEncoded = decodeVoiceAudioDataUrl(
            "data:audio/mpeg,%68%65%6C%6C%6F",
            File(System.getProperty("java.io.tmpdir")),
        )
        try {
            assertArrayEquals("hello".toByteArray(), base64.file.readBytes())
            assertArrayEquals("hello".toByteArray(), urlEncoded.file.readBytes())
        } finally {
            base64.file.delete()
            urlEncoded.file.delete()
        }
    }

    @Test
    fun `recording encoder rejects an oversized file before reading it`() {
        val file = File.createTempFile("talaria-voice-test-", ".m4a")
        try {
            RandomAccessFile(file, "rw").use { handle ->
                handle.setLength(VoiceAudioLimits.MAX_RECORDING_BYTES + 1L)
            }

            assertThrows(IllegalArgumentException::class.java) {
                encodeRecordedVoiceDataUrl(file, "audio/mp4")
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `decoder rejects malformed base64`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeVoiceAudioDataUrl(
                "data:audio/mpeg;base64,not valid base64!",
                File(System.getProperty("java.io.tmpdir")),
            )
        }
    }
}
