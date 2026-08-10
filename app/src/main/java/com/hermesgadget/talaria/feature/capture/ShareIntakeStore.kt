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

import android.content.Context
import androidx.core.content.edit
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.feature.manage.files.SHARE_FILE_TTL_MILLIS
import com.hermesgadget.talaria.feature.manage.files.ShareFileManager
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
enum class ShareDraftDeliveryState {
    DRAFT,
    SENDING,
    DELIVERY_UNKNOWN,
}

@Serializable
data class ShareIntakeItem(
    val id: String,
    val sourceUri: String,
    val localPath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: ShareItemKind,
)

@Serializable
data class ShareIntakeDraft(
    val id: String = UUID.randomUUID().toString(),
    val scopeId: String,
    val connectionId: String,
    val managementProfile: String,
    val text: String = "",
    val subject: String = "",
    val instruction: String = "",
    val targetKind: ShareTargetKind = ShareTargetKind.NEW,
    val targetSessionId: String? = null,
    val items: List<ShareIntakeItem> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val deliveryState: ShareDraftDeliveryState = ShareDraftDeliveryState.DRAFT,
    val deliveryMessage: String? = null,
)

@Serializable
enum class ShareTargetKind {
    CURRENT,
    PINNED,
    NEW,
}

/**
 * Durable metadata for one incoming task. Bytes stay in ShareFileManager's
 * owned cache; this store only retains bounded names, paths, and user choices.
 */
class ShareIntakeStore(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences(
        "talaria_share_intake",
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun load(scopeId: String): ShareIntakeDraft? {
        val raw = prefs.getString(key(scopeId), null) ?: return null
        val draft = runCatching { JsonConfig.json.decodeFromString<ShareIntakeDraft>(raw) }.getOrNull()
            ?: return null.also { prefs.edit(commit = true) { remove(key(scopeId)) } }
        if (isExpired(draft)) return null.also { remove(draft) }
        // A process can die after the durable state changes to SENDING but
        // before the PTY acknowledgement. Keep it visible but permanently
        // non-resendable, because the server may already have accepted it.
        return if (draft.deliveryState == ShareDraftDeliveryState.SENDING) {
            draft.copy(
                deliveryState = ShareDraftDeliveryState.DELIVERY_UNKNOWN,
                deliveryMessage = appContext.getString(R.string.capture_delivery_unknown),
            ).also(::save)
        } else {
            draft
        }
    }

    @Synchronized
    fun save(draft: ShareIntakeDraft) {
        if (draft.scopeId.isBlank()) return
        prefs.edit(commit = true) {
            putString(key(draft.scopeId), JsonConfig.json.encodeToString(draft))
        }
    }

    @Synchronized
    fun remove(draft: ShareIntakeDraft) {
        prefs.edit(commit = true) { remove(key(draft.scopeId)) }
    }

    /** Clean stale metadata and its owned files across all profile scopes. */
    @Synchronized
    fun cleanup(fileManager: ShareFileManager) {
        fileManager.cleanupStaleFiles()
        prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach { prefKey ->
            val draft = prefs.getString(prefKey, null)
                ?.let { runCatching { JsonConfig.json.decodeFromString<ShareIntakeDraft>(it) }.getOrNull() }
            if (draft == null || isExpired(draft)) {
                draft?.items.orEmpty().forEach { fileManager.deleteOwnedFile(File(it.localPath)) }
                prefs.edit(commit = true) { remove(prefKey) }
            }
        }
    }

    private fun isExpired(draft: ShareIntakeDraft): Boolean =
        nowMillis() - draft.updatedAt >= SHARE_FILE_TTL_MILLIS

    private fun key(scopeId: String): String = KEY_PREFIX + scopeId

    private companion object {
        const val KEY_PREFIX = "draft_"
    }
}
