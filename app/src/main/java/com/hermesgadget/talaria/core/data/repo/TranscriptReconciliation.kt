/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.core.data.repo

import com.hermesgadget.talaria.core.data.db.CachedSessionEntity
import com.hermesgadget.talaria.domain.model.SessionMessage
import com.hermesgadget.talaria.domain.model.SessionMessagesResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The server metadata used to decide whether a transcript can be reused. */
data class TranscriptFingerprint(
    val revision: String?,
    val messageCount: Int,
    val contentHash: String,
) {
    /** A revision-only change must not cause a Room rewrite when the payload is equal. */
    val contentKey: String get() = "$messageCount:$contentHash"
}

/** A successful transcript read, including whether its message payload changed. */
data class TranscriptSnapshot(
    val messages: List<SessionMessage>,
    val fingerprint: TranscriptFingerprint,
    val contentChanged: Boolean,
)

object TranscriptFingerprintFactory {
    fun from(response: SessionMessagesResponse): TranscriptFingerprint {
        val messages = response.messages
        return TranscriptFingerprint(
            revision = response.revision ?: response.transcript_revision,
            messageCount = response.message_count ?: messages.size,
            contentHash = response.hash ?: response.content_hash ?: hash(messages),
        )
    }

    /** Stable hash fallback for gateways that predate transcript metadata. */
    fun hash(messages: List<SessionMessage>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        messages.forEachIndexed { index, message ->
            digest.update(index.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            digest.update(message.role.orEmpty().toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            digest.update(message.content.orEmpty().toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            digest.update(message.timestamp.orEmpty().toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            digest.update(message.name.orEmpty().toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            digest.update(message.tool_calls?.toString().orEmpty().toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    fun contentChanged(
        previous: TranscriptFingerprint?,
        next: TranscriptFingerprint,
    ): Boolean = previous?.contentKey != next.contentKey
}

/** Pure set and row comparisons used by the Room reconciliation boundary. */
object SessionReconciliation {
    fun staleSessionIds(cachedIds: Set<String>, serverIds: Set<String>): Set<String> =
        cachedIds - serverIds

    fun changedRows(
        cachedById: Map<String, CachedSessionEntity>,
        serverRows: List<CachedSessionEntity>,
    ): List<CachedSessionEntity> = serverRows.filter { next ->
        val old = cachedById[next.id] ?: return@filter true
        old.title != next.title ||
            old.source != next.source ||
            old.model != next.model ||
            old.preview != next.preview ||
            old.messageCount != next.messageCount ||
            old.lastActive != next.lastActive ||
            old.json != next.json ||
            old.platform != next.platform
    }
}
