/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.notifications

import java.io.File

/**
 * WorkManager `Data` is capped at 10,240 bytes; a long paste into a
 * notification reply field would throw IllegalStateException inside
 * `onReceive`. Replies within the inline budget travel as text; anything
 * larger spills to a unique cache file and only the path enters `Data`
 * (M5). The worker resolves both forms.
 */
internal const val MAX_INLINE_REPLY_BYTES = 4096

internal sealed interface ReplyPayload {
    data class Inline(val text: String) : ReplyPayload
    data class File(val path: String) : ReplyPayload
}

internal class ReplyPayloadBuilder(
    private val repliesDir: File,
) {
    init {
        require(repliesDir.isDirectory || repliesDir.mkdirs()) {
            "Could not create notification reply spill directory"
        }
    }

    fun build(reply: String, nowMillis: Long = System.currentTimeMillis()): ReplyPayload {
        val bytes = reply.toByteArray(Charsets.UTF_8)
        return if (bytes.size <= MAX_INLINE_REPLY_BYTES) {
            ReplyPayload.Inline(reply)
        } else {
            val file = File(repliesDir, "$nowMillis-${reply.hashCode().toUInt().toString(16)}.txt")
            file.writeText(reply)
            ReplyPayload.File(file.absolutePath)
        }
    }
}

/** WorkManager Data view of the payload: exactly one of these is non-null. */
internal val ReplyPayload.text: String?
    get() = (this as? ReplyPayload.Inline)?.text

internal val ReplyPayload.filePath: String?
    get() = (this as? ReplyPayload.File)?.path
