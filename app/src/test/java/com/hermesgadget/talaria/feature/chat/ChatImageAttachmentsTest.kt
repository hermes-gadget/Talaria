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

package com.hermesgadget.talaria.feature.chat

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatImageAttachmentsTest {
    @Test
    fun `magic bytes override an incorrect picker mime type`() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )

        val image = ChatImageAttachments.validate(png, "screen", "application/octet-stream")

        assertEquals("image/png", image.mimeType)
        assertEquals("screen.png", image.filename)
    }

    @Test
    fun `jpeg aliases preserve a safe filename`() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00)

        val image = ChatImageAttachments.validate(jpeg, "..\\photo.jpeg", "image/jpeg")

        assertEquals("photo.jpeg", image.filename)
    }

    @Test
    fun `unsupported image payload is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatImageAttachments.validate("not an image".encodeToByteArray(), "note.png", "image/png")
        }
    }

    @Test
    fun `bounded reader accepts the limit and rejects one byte more`() {
        assertEquals(4, ChatImageAttachments.readCapped(ByteArrayInputStream(ByteArray(4)), 4).size)
        assertThrows(IllegalArgumentException::class.java) {
            ChatImageAttachments.readCapped(ByteArrayInputStream(ByteArray(5)), 4)
        }
    }

    @Test
    fun `decoded pixel budget rejects huge dimensions before decode`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatImageAttachments.validateDecodedPixels(100_000, 100_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatImageAttachments.validateDecodedPixels(Int.MAX_VALUE, 2)
        }
    }
}
