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

package com.hermesgadget.talaria.feature.voice

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Hard limits for the voice data-URL compatibility path. */
internal object VoiceAudioLimits {
    const val MAX_RECORDING_DURATION_MS: Long = 60_000L
    const val MAX_RECORDING_BYTES: Long = 2L * 1024L * 1024L
    const val MAX_RECORDING_DATA_URL_CHARS: Int = 3 * 1024 * 1024

    const val MAX_PLAYBACK_BYTES: Long = 8L * 1024L * 1024L
    const val MAX_PLAYBACK_DATA_URL_CHARS: Int = 12 * 1024 * 1024

    const val MAX_DATA_URL_METADATA_CHARS: Int = 512
    const val IO_BUFFER_BYTES: Int = 8 * 1024
}

internal data class DecodedVoiceAudioFile(
    val file: File,
    val mimeType: String,
)

/**
 * Builds the existing server STT data-URL contract without first copying the
 * complete recording into a byte array. The file is checked before the output
 * StringBuilder is allocated, and the streaming sink checks the same ceiling
 * again in case the file changes between stat and read.
 */
internal fun encodeRecordedVoiceDataUrl(file: File, mimeType: String): String {
    require(file.isFile) { "Recording file is no longer available" }
    val expectedBytes = file.length()
    require(expectedBytes > 0L) { "Recording produced no audio" }
    require(expectedBytes <= VoiceAudioLimits.MAX_RECORDING_BYTES) {
        "Recording exceeds the ${VoiceAudioLimits.MAX_RECORDING_BYTES} byte audio limit"
    }

    val header = "data:${mimeType.trim().ifBlank { "audio/mpeg" }};base64,"
    val expectedBase64Chars = base64Length(expectedBytes)
    require(header.length.toLong() + expectedBase64Chars <= VoiceAudioLimits.MAX_RECORDING_DATA_URL_CHARS) {
        "Recording data URL exceeds the supported size limit"
    }

    val output = StringBuilder((header.length + expectedBase64Chars).toInt())
    output.append(header)
    val sink = StringBuilderOutputStream(output, VoiceAudioLimits.MAX_RECORDING_DATA_URL_CHARS)
    var readBytes = 0L
    FileInputStream(file).use { input ->
        Base64.getEncoder().wrap(sink).use { encoder ->
            val buffer = ByteArray(VoiceAudioLimits.IO_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                readBytes += count.toLong()
                require(readBytes <= VoiceAudioLimits.MAX_RECORDING_BYTES) {
                    "Recording exceeds the ${VoiceAudioLimits.MAX_RECORDING_BYTES} byte audio limit"
                }
                encoder.write(buffer, 0, count)
            }
        }
    }
    require(readBytes == expectedBytes) { "Recording changed while it was being encoded" }
    return output.toString()
}

/**
 * Decodes a server audio data URL directly into a bounded cache file. Base64
 * is decoded through a streaming decoder, so the complete payload never also
 * exists as a decoded ByteArray in memory.
 */
internal fun decodeVoiceAudioDataUrl(value: String, cacheDir: File): DecodedVoiceAudioFile {
    require(value.length <= VoiceAudioLimits.MAX_PLAYBACK_DATA_URL_CHARS) {
        "Server audio data URL exceeds the ${VoiceAudioLimits.MAX_PLAYBACK_DATA_URL_CHARS} character limit"
    }
    val dataUrl = value.trim()
    require(dataUrl.startsWith("data:", ignoreCase = true)) {
        "Hermes returned an invalid audio data URL"
    }
    val comma = dataUrl.indexOf(',')
    require(comma > DATA_PREFIX.length) { "Hermes returned an empty audio data URL" }
    require(comma - DATA_PREFIX.length <= VoiceAudioLimits.MAX_DATA_URL_METADATA_CHARS) {
        "Hermes returned an invalid audio data URL header"
    }
    val metadata = dataUrl.substring(DATA_PREFIX.length, comma)
    val mimeType = metadata.substringBefore(';').trim().ifBlank { "audio/mpeg" }
    val isBase64 = metadata.split(';').any { it.equals("base64", ignoreCase = true) }

    val file = File.createTempFile("talaria-tts-", extensionFor(mimeType), cacheDir)
    try {
        val byteCount = BufferedOutputStream(FileOutputStream(file)).use { output ->
            if (isBase64) {
                val payload = Base64.getDecoder().wrap(
                    Base64PayloadInputStream(dataUrl, comma + 1),
                )
                payload.use { input ->
                    copyBounded(input, output, VoiceAudioLimits.MAX_PLAYBACK_BYTES)
                }
            } else {
                writeUrlDecodedPayload(
                    value = dataUrl,
                    start = comma + 1,
                    output = output,
                    maximumBytes = VoiceAudioLimits.MAX_PLAYBACK_BYTES,
                )
            }
        }
        require(byteCount > 0L) { "Hermes returned empty audio" }
        require(byteCount <= VoiceAudioLimits.MAX_PLAYBACK_BYTES) {
            "Server audio exceeds the ${VoiceAudioLimits.MAX_PLAYBACK_BYTES} byte limit"
        }
        return DecodedVoiceAudioFile(file = file, mimeType = mimeType)
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
}

private const val DATA_PREFIX = "data:"

private fun base64Length(byteCount: Long): Long = ((byteCount + 2L) / 3L) * 4L

private fun copyBounded(
    input: InputStream,
    output: OutputStream,
    maximumBytes: Long,
): Long {
    val buffer = ByteArray(VoiceAudioLimits.IO_BUFFER_BYTES)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return total
        if (count == 0) continue
        require(total <= maximumBytes - count.toLong()) {
            "Server audio exceeds the $maximumBytes byte limit"
        }
        output.write(buffer, 0, count)
        total += count.toLong()
    }
}

private fun writeUrlDecodedPayload(
    value: String,
    start: Int,
    output: OutputStream,
    maximumBytes: Long,
): Long {
    var index = start
    var total = 0L
    while (index < value.length) {
        val current = value[index]
        if (current == '%') {
            require(index + 2 < value.length) { "Hermes returned malformed audio data" }
            val high = hexValue(value[index + 1])
            val low = hexValue(value[index + 2])
            require(total < maximumBytes) { "Server audio exceeds the $maximumBytes byte limit" }
            output.write((high shl 4) or low)
            total++
            index += 3
            continue
        }

        val codePoint = value.codePointAt(index)
        val bytes = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8)
        require(total <= maximumBytes - bytes.size.toLong()) {
            "Server audio exceeds the $maximumBytes byte limit"
        }
        output.write(bytes)
        total += bytes.size.toLong()
        index += Character.charCount(codePoint)
    }
    return total
}

private fun hexValue(value: Char): Int = when (value) {
    in '0'..'9' -> value - '0'
    in 'a'..'f' -> value - 'a' + 10
    in 'A'..'F' -> value - 'A' + 10
    else -> throw IllegalArgumentException("Hermes returned malformed audio data")
}

private class Base64PayloadInputStream(
    private val value: String,
    private val start: Int,
) : InputStream() {
    private var index = start

    override fun read(): Int {
        while (index < value.length) {
            val character = value[index++]
            if (character == ' ' || character == '\n' || character == '\r' || character == '\t') {
                continue
            }
            require(isBase64Character(character)) { "Hermes returned malformed base64 audio" }
            return character.code
        }
        return -1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
            "Invalid audio decoder buffer"
        }
        if (length == 0) return 0
        var count = 0
        while (count < length) {
            val next = read()
            if (next < 0) return if (count == 0) -1 else count
            buffer[offset + count] = next.toByte()
            count++
        }
        return count
    }

    private fun isBase64Character(value: Char): Boolean =
        value in 'A'..'Z' || value in 'a'..'z' || value in '0'..'9' || value == '+' ||
            value == '/' || value == '='
}

private class StringBuilderOutputStream(
    private val output: StringBuilder,
    private val maximumChars: Int,
) : OutputStream() {
    override fun write(value: Int) {
        require(output.length < maximumChars) { "Encoded audio data URL exceeds its size limit" }
        output.append(value.toChar())
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
            "Invalid audio encoder buffer"
        }
        require(output.length <= maximumChars - length) {
            "Encoded audio data URL exceeds its size limit"
        }
        for (index in offset until offset + length) {
            output.append(buffer[index].toInt().and(0xff).toChar())
        }
    }
}

private fun extensionFor(mimeType: String): String = when {
    mimeType.equals("audio/mp4", ignoreCase = true) ||
        mimeType.equals("audio/x-m4a", ignoreCase = true) -> ".m4a"
    mimeType.equals("audio/ogg", ignoreCase = true) -> ".ogg"
    mimeType.equals("audio/wav", ignoreCase = true) ||
        mimeType.equals("audio/x-wav", ignoreCase = true) -> ".wav"
    mimeType.equals("audio/flac", ignoreCase = true) -> ".flac"
    mimeType.equals("audio/3gpp", ignoreCase = true) -> ".3gp"
    else -> ".mp3"
}
