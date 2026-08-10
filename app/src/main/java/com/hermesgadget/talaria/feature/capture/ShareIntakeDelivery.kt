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

import com.hermesgadget.talaria.core.data.repo.ChatRepository
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.HermesEventClient
import com.hermesgadget.talaria.core.network.HermesEventScope
import com.hermesgadget.talaria.core.network.PtyPromptDelivery
import com.hermesgadget.talaria.core.network.WebSocketFrameBudget
import com.hermesgadget.talaria.core.network.WsAuthHelper
import com.hermesgadget.talaria.core.network.HermesClientFactory
import com.hermesgadget.talaria.feature.chat.ChatImageAttachments
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Delivers one persisted share task without ever resolving a new active scope mid-flight. */
internal class ShareIntakeDelivery(
    private val clientFactory: HermesClientFactory,
    private val chatRepository: ChatRepository,
    private val wsAuth: WsAuthHelper,
) {
    suspend fun deliver(
        snapshot: ConnectionSnapshot,
        draft: ShareIntakeDraft,
    ): String {
        val files = draft.items.map { item ->
            val file = File(item.localPath)
            require(file.isFile) { "${item.displayName} is no longer available" }
            require(file.length() == item.sizeBytes) { "${item.displayName} changed before delivery" }
            file
        }
        val plan = buildShareDeliveryPlan(draft, files)
        val inlineImages = plan.inlineImages
        val managedReferences = plan.managedUploads.map { upload ->
            ManagedReference(upload.item, upload.path)
        }
        val prompt = SharePromptBuilder.build(draft, inlineImages.map { it.first }, managedReferences)
        require(WebSocketFrameBudget.textWithinLimit(prompt)) {
            "Shared text and instructions exceed the ${WebSocketFrameBudget.MAX_FRAME_BYTES} byte PTY limit"
        }
        // Classification and prompt validation must be side-effect free. Upload each
        // managed item exactly once after the complete delivery payload is accepted.
        plan.managedUploads.forEach { managed ->
            upload(snapshot, managed.path, managed.item, managed.file)
        }

        val channelId = UUID.randomUUID().toString()
        val eventClient = HermesEventClient(
            clientFactory = clientFactory,
            wsAuth = wsAuth,
            fixedSnapshot = snapshot,
            fixedEventScope = HermesEventScope(
                connectionId = snapshot.connectionId,
                managementProfile = snapshot.managementProfile,
                channelId = channelId,
                tabId = "share-${draft.id}",
                sessionId = draft.targetSessionId,
            ),
        )
        val (session, ptyEvents) = chatRepository.openPty(
            snapshot = snapshot,
            resumeSessionId = draft.targetSessionId,
            channelId = channelId,
        )
        eventClient.start(channelId, includeRpc = true)
        return try {
            if (inlineImages.isNotEmpty()) {
                check(eventClient.awaitRpcConnected()) {
                    "Hermes image attachment channel is unavailable; use the managed upload alternative"
                }
            }
            PtyPromptDelivery.deliver(
                session = session,
                ptyEvents = ptyEvents,
                text = prompt,
                eventClient = eventClient,
                beforeSend = { sessionId ->
                    inlineImages.forEach { (item, file) ->
                        attachImage(eventClient, sessionId, item, file)
                    }
                },
            ).sessionKey
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            // PtyPromptDelivery closes both on its normal and exceptional paths;
            // these calls also cover a failure while the PTY is being opened.
            eventClient.stop()
            session.close()
        }
    }

    private suspend fun upload(
        snapshot: ConnectionSnapshot,
        path: String,
        item: ShareIntakeItem,
        file: File,
    ) {
        require(file.isFile && file.length() == item.sizeBytes) {
            "${item.displayName} changed before upload"
        }
        val mime = item.mimeType.toMediaType()
        val body = file.asRequestBody(mime)
        clientFactory.api(snapshot).uploadManagedFileStream(
            path = path.toRequestBody("text/plain".toMediaType()),
            // The draft path is deterministic, so a retry after a process death
            // is idempotent instead of failing on an already-uploaded file.
            overwrite = "true".toRequestBody("text/plain".toMediaType()),
            file = MultipartBody.Part.createFormData("file", item.displayName, body),
            profile = snapshot.managementProfile,
        )
    }

    private suspend fun attachImage(
        eventClient: HermesEventClient,
        sessionId: String,
        item: ShareIntakeItem,
        file: File,
    ) {
        val content = withContext(Dispatchers.Default) { encodeBase64(file) }
        val response = eventClient.requestRpc(
            method = "image.attach_bytes",
            params = buildJsonObject {
                put("session_id", sessionId)
                put("content_base64", content)
                put("filename", item.displayName)
            },
        ) as? JsonObject
        if (response?.get("attached")?.jsonPrimitive?.booleanOrNull != true) {
            val message = response?.get("message")?.jsonPrimitive?.contentOrNull
            throw IllegalStateException(
                message ?: "Hermes could not attach ${item.displayName}; use the managed upload alternative",
            )
        }
    }

    private fun encodeBase64(file: File): String {
        require(file.length() <= ChatImageAttachments.MAX_TRANSPORT_BYTES) {
            "${file.name} is too large for an inline image attachment; use managed upload"
        }
        val estimated = (file.length() * 4L / 3L + 4L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val encoded = ByteArrayOutputStream(estimated)
        Base64.getEncoder().wrap(encoded).use { output ->
            file.inputStream().use { input -> input.copyTo(output, 16 * 1024) }
        }
        return encoded.toString(StandardCharsets.US_ASCII.name())
    }

}

internal data class ShareDeliveryPlan(
    val inlineImages: List<Pair<ShareIntakeItem, File>>,
    val managedUploads: List<ManagedUpload>,
)

internal data class ManagedUpload(
    val item: ShareIntakeItem,
    val path: String,
    val file: File,
)

/** Classify files without network or filesystem side effects. */
internal fun buildShareDeliveryPlan(
    draft: ShareIntakeDraft,
    files: List<File>,
): ShareDeliveryPlan {
    require(draft.items.size == files.size) { "Each shared item must have one local file" }
    val inlineImages = mutableListOf<Pair<ShareIntakeItem, File>>()
    val managedUploads = mutableListOf<ManagedUpload>()
    draft.items.zip(files).forEach { (item, file) ->
        if (item.kind == ShareItemKind.IMAGE &&
            file.length() <= ChatImageAttachments.MAX_TRANSPORT_BYTES
        ) {
            inlineImages += item to file
        } else {
            managedUploads += ManagedUpload(
                item = item,
                path = managedPathForShareItem(draft.id, item),
                file = file,
            )
        }
    }
    return ShareDeliveryPlan(
        inlineImages = inlineImages,
        managedUploads = managedUploads,
    )
}

private fun managedPathForShareItem(taskId: String, item: ShareIntakeItem): String {
    val task = taskId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(32)
    val itemId = item.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(16)
    val name = ShareIntakePolicy.safeFilename(item.displayName).replace(' ', '_')
    return "/talaria-share-intake-$task-$itemId-$name"
}

internal data class ManagedReference(
    val item: ShareIntakeItem,
    val path: String,
)

/** Pure prompt projection keeps arbitrary binaries explicitly link-like. */
internal object SharePromptBuilder {
    fun build(
        draft: ShareIntakeDraft,
        inlineImages: List<ShareIntakeItem>,
        managedReferences: List<ManagedReference>,
    ): String = buildString {
        draft.instruction.trim().takeIf { it.isNotEmpty() }?.let {
            append(it)
            append("\n\n")
        }
        draft.subject.trim().takeIf { it.isNotEmpty() }?.let {
            append("Shared subject: ")
            append(it)
            append("\n")
        }
        draft.text.takeIf { it.isNotBlank() }?.let {
            append("Shared text:\n")
            append(it)
            append("\n")
        }
        if (inlineImages.isNotEmpty() || managedReferences.isNotEmpty()) {
            append("Shared items (in order):\n")
            val inlineById = inlineImages.associateBy { it.id }
            val managedById = managedReferences.associateBy { it.item.id }
            draft.items.forEach { item ->
                if (item.id in inlineById) {
                    append("- Inline image: ")
                    append(ShareIntakePolicy.safeFilename(item.displayName))
                    append('\n')
                } else {
                    managedById[item.id]?.let { reference ->
                        append("- Managed file reference: ")
                        append(reference.path)
                        append(" (uploaded alternative; this is not an inline attachment)\n")
                    }
                }
            }
        }
        if (length == 0) append("Please inspect the shared items.")
    }
}
