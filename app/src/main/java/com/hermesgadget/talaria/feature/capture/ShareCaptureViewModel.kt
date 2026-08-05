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

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.core.network.SnapshotAuthGuard
import com.hermesgadget.talaria.di.AppContainer
import com.hermesgadget.talaria.domain.model.SessionSummary
import com.hermesgadget.talaria.domain.model.scopeId
import com.hermesgadget.talaria.feature.manage.sessions.SessionFilters
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

data class ShareTargetOption(
    val kind: ShareTargetKind,
    val sessionId: String?,
    val label: String,
    val profileName: String,
    val enabled: Boolean = true,
) {
    val key: String get() = "${kind.name}:${sessionId.orEmpty()}"
}

data class ShareCaptureItemUi(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: ShareItemKind,
    val inlineImageSupported: Boolean,
)

enum class ShareDeliveryUiState {
    IDLE,
    SENDING,
    DELIVERED,
}

data class ShareCaptureUiState(
    val text: String = "",
    val subject: String = "",
    val instruction: String = "",
    val items: List<ShareCaptureItemUi> = emptyList(),
    val targets: List<ShareTargetOption> = emptyList(),
    val selectedTargetKey: String? = null,
    val profileName: String? = null,
    val importing: Boolean = false,
    val loadingTargets: Boolean = false,
    val deliveryState: ShareDeliveryUiState = ShareDeliveryUiState.IDLE,
    val suggestions: List<String> = emptyList(),
    val error: String? = null,
    val completed: Boolean = false,
)

/** Scoped, restorable state machine for ACTION_SEND, SEND_MULTIPLE, and PROCESS_TEXT. */
class ShareCaptureViewModel(
    private val container: AppContainer = TalariaApp.instance.container,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val snapshot: ConnectionSnapshot? = container.clientFactory.snapshot()
    private val scopeId: String? = snapshot?.profile?.scopeId()
    private val resolver: ContentResolver = container.contentResolver
    private val fileManager = container.shareFileManager
    private val store = container.shareIntakeStore
    private val intakeDelivery = snapshot?.let {
        ShareIntakeDelivery(container.clientFactory, container.chatRepository, container.wsAuthHelper)
    }
    private val pinStore = container.sessionPinStore
    private val intakeMutex = Mutex()
    private var draft: ShareIntakeDraft? = null

    private val _ui = MutableStateFlow(
        ShareCaptureUiState(
            profileName = snapshot?.managementProfile,
            error = if (snapshot == null) "Connect to a Hermes profile before sharing" else null,
        ),
    )
    val ui: StateFlow<ShareCaptureUiState> = _ui.asStateFlow()

    init {
        fileManager.cleanupStaleFiles()
        val currentSnapshot = snapshot
        val currentScope = scopeId
        if (currentSnapshot != null && currentScope != null) {
            store.cleanup(fileManager)
            draft = store.load(currentScope)?.takeIf {
                it.scopeId == currentScope &&
                    it.connectionId == currentSnapshot.connectionId &&
                    it.managementProfile == currentSnapshot.managementProfile
            }
            if (draft == null) {
                draft = ShareIntakeDraft(
                    scopeId = currentScope,
                    connectionId = currentSnapshot.connectionId,
                    managementProfile = currentSnapshot.managementProfile,
                    createdAt = nowMillis(),
                    updatedAt = nowMillis(),
                )
                saveDraft()
            } else {
                pruneMissingItems()
            }
            publish()
            refreshTargets()
        }
    }

    fun acceptIntent(intent: android.content.Intent) {
        val currentSnapshot = snapshot ?: return
        val currentDraft = draft ?: return
        val payload = runCatching { ShareIntentParser.parse(intent) }.getOrElse { failure ->
            showError(failure.message ?: "The shared data could not be read")
            return
        }
        if (payload.action !in SUPPORTED_ACTIONS) {
            showError("This share type is not supported")
            return
        }
        val incomingText = payload.text
        val incomingSubject = payload.subject
        try {
            ShareIntakePolicy.checkText(incomingText)
            require(incomingSubject.length <= ShareIntakePolicy.MAX_SUBJECT_CHARS) {
                "Shared subject is too large"
            }
        } catch (failure: IllegalArgumentException) {
            showError(failure.message ?: "Shared text is over budget")
            return
        }

        val mergedText = when {
            incomingText.isBlank() -> currentDraft.text
            currentDraft.text.isBlank() -> incomingText
            currentDraft.text == incomingText -> currentDraft.text
            else -> "${currentDraft.text}\n\n$incomingText"
        }
        try {
            ShareIntakePolicy.checkText(mergedText)
        } catch (failure: IllegalArgumentException) {
            showError(failure.message ?: "Shared text is over budget")
            return
        }
        draft = currentDraft.copy(
            text = mergedText,
            subject = incomingSubject.takeIf { it.isNotBlank() } ?: currentDraft.subject,
            updatedAt = nowMillis(),
            deliveryMessage = null,
        )
        saveDraft()
        publish()

        if (payload.uriStrings.isNotEmpty()) {
            ingestUris(currentSnapshot, payload.uriStrings, payload.mimeType)
        } else if (incomingText.isBlank() && currentDraft.items.isEmpty()) {
            showError("The share did not contain text or a readable file")
        }
    }

    fun updateInstruction(value: String) {
        try {
            ShareIntakePolicy.checkInstruction(value)
        } catch (failure: IllegalArgumentException) {
            showError(failure.message ?: "Instruction is over budget")
            return
        }
        draft = draft?.copy(instruction = value, updatedAt = nowMillis(), deliveryMessage = null)
        saveDraft()
        publish()
    }

    fun selectTarget(option: ShareTargetOption) {
        if (!option.enabled) return
        draft = draft?.copy(
            targetKind = option.kind,
            targetSessionId = option.sessionId,
            updatedAt = nowMillis(),
            deliveryMessage = null,
        )
        saveDraft()
        publish()
    }

    fun removeItem(itemId: String) {
        val current = draft ?: return
        val item = current.items.firstOrNull { it.id == itemId } ?: return
        fileManager.deleteOwnedFile(java.io.File(item.localPath))
        draft = current.copy(
            items = current.items.filterNot { it.id == itemId },
            updatedAt = nowMillis(),
            deliveryMessage = null,
        )
        saveDraft()
        publish()
    }

    fun useSuggestion(suggestion: String) {
        if (suggestion !in ShareIntakePolicy.urlSuggestions(ui.value.text)) return
        updateInstruction(suggestion.replaceFirstChar(Char::uppercase) + " this URL")
    }

    fun send() {
        val current = draft ?: return
        val fixedSnapshot = snapshot ?: run {
            showError("Connect to a Hermes profile before sending")
            return
        }
        if (ui.value.deliveryState == ShareDeliveryUiState.SENDING) return
        if (current.text.isBlank() && current.items.isEmpty()) {
            showError("Add text or a file before sending")
            return
        }
        val activeSnapshot = container.clientFactory.snapshot()
        if (!SnapshotAuthGuard.isCurrent(fixedSnapshot, activeSnapshot)) {
            showError("The connection or profile changed; reopen this share to choose a safe target")
            return
        }
        val delivery = intakeDelivery ?: run {
            showError("Share delivery is unavailable until a profile is connected")
            return
        }
        val sending = current.copy(
            deliveryState = ShareDraftDeliveryState.SENDING,
            deliveryMessage = null,
            updatedAt = nowMillis(),
        )
        draft = sending
        store.save(sending)
        _ui.update { it.copy(deliveryState = ShareDeliveryUiState.SENDING, error = null) }
        viewModelScope.launch {
            val result = try {
                Result.success(delivery.deliver(fixedSnapshot, sending))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            result.onSuccess { sessionId ->
                sending.items.forEach { item -> fileManager.deleteOwnedFile(java.io.File(item.localPath)) }
                store.remove(sending)
                draft = null
                _ui.update {
                    it.copy(deliveryState = ShareDeliveryUiState.DELIVERED, completed = true, error = null)
                }
                // The session id is intentionally not persisted here: the task
                // is complete and the normal Chat profile refresh will discover it.
                Unit
            }.onFailure { failure ->
                val message = failure.message ?: "Share delivery failed"
                val retained = sending.copy(
                    deliveryState = ShareDraftDeliveryState.DRAFT,
                    deliveryMessage = message,
                    updatedAt = nowMillis(),
                )
                draft = retained
                store.save(retained)
                _ui.update {
                    it.copy(
                        deliveryState = ShareDeliveryUiState.IDLE,
                        error = message,
                    )
                }
            }
        }
    }

    fun discard() {
        val current = draft ?: return
        current.items.forEach { fileManager.deleteOwnedFile(java.io.File(it.localPath)) }
        store.remove(current)
        draft = null
        _ui.update { it.copy(completed = true, error = null) }
    }

    private fun ingestUris(
        fixedSnapshot: ConnectionSnapshot,
        uriStrings: List<String>,
        fallbackMimeType: String?,
    ) {
        val candidates = ShareIntentParser.dedupeUris(uriStrings)
        viewModelScope.launch {
            intakeMutex.withLock {
                _ui.update { it.copy(importing = true, error = null) }
                val errors = mutableListOf<String>()
                candidates.forEach { rawUri ->
                    val current = draft ?: return@forEach
                    if (current.items.any { it.sourceUri == rawUri }) return@forEach
                    try {
                        val item = copyAndValidate(fixedSnapshot, rawUri, fallbackMimeType)
                        draft = current.copy(
                            items = current.items + item,
                            updatedAt = nowMillis(),
                            deliveryMessage = null,
                        )
                        saveDraft()
                        publish()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        errors += "${ShareIntakePolicy.safeFilename(rawUri, "shared item")}: " +
                            (failure.message ?: "could not read")
                    }
                }
                _ui.update {
                    it.copy(
                        importing = false,
                        error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                    )
                }
            }
        }
    }

    private suspend fun copyAndValidate(
        fixedSnapshot: ConnectionSnapshot,
        rawUri: String,
        fallbackMimeType: String?,
    ): ShareIntakeItem = withContext(Dispatchers.IO) {
        val current = draft ?: error("Share draft is no longer available")
        require(current.scopeId == fixedSnapshot.profile.scopeId()) { "Share scope changed" }
        val uri = Uri.parse(rawUri)
        val displayName = ShareIntakePolicy.safeFilename(queryDisplayName(uri), "shared-file")
        val declaredMime = runCatching { resolver.getType(uri) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackMimeType
        val declaredBytes = queryLength(uri)
        if (declaredBytes > ShareIntakePolicy.MAX_ITEM_BYTES) {
            error("${displayName} is larger than ${ShareIntakePolicy.MAX_ITEM_BYTES / (1024 * 1024)} MB")
        }
        if (current.items.size >= ShareIntakePolicy.MAX_ITEMS) {
            error("You can share up to ${ShareIntakePolicy.MAX_ITEMS} items at once")
        }
        val existingBytes = current.items.sumOf { it.sizeBytes }
        val remaining = ShareIntakePolicy.remainingBytes(existingBytes)
        require(remaining > 0L) {
            "Shared files exceed the ${ShareIntakePolicy.MAX_TOTAL_BYTES / (1024 * 1024)} MB total limit"
        }
        if (declaredBytes == 0L) error("${displayName} is empty")
        if (declaredBytes > remaining) error("${displayName} exceeds the remaining share budget")

        val maxStreamBytes = minOf(
            ShareIntakePolicy.MAX_ITEM_BYTES,
            remaining,
            declaredBytes.takeIf { it >= 0L } ?: Long.MAX_VALUE,
        )
        val source = resolver.openInputStream(uri) ?: error("Could not open ${displayName}")
        val owned = try {
            fileManager.createShareFile(
                prefix = "capture-",
                suffix = ShareIntakePolicy.suffixFor(displayName),
                source = BoundedShareInputStream(source, maxStreamBytes),
                declaredBytes = declaredBytes,
            )
        } catch (failure: Throwable) {
            source.close()
            throw failure
        }
        try {
            val actualBytes = owned.length()
            ShareIntakePolicy.checkItemBudget(current.items.size, existingBytes, actualBytes)
            val prefix = readPrefix(owned)
            val classification = ShareIntakePolicy.classify(displayName, declaredMime, prefix)
            ShareIntakeItem(
                id = UUID.randomUUID().toString(),
                sourceUri = ShareIntakePolicy.normalizeUri(rawUri),
                localPath = owned.absolutePath,
                displayName = displayName,
                mimeType = classification.mimeType,
                sizeBytes = actualBytes,
                kind = classification.kind,
            )
        } catch (failure: Throwable) {
            fileManager.deleteOwnedFile(owned)
            throw failure
        }
    }

    private fun refreshTargets() {
        val fixedSnapshot = snapshot ?: return
        val fixedScope = scopeId ?: return
        _ui.update { it.copy(loadingTargets = true) }
        viewModelScope.launch {
            val sessions = try {
                withContext(Dispatchers.IO) {
                    decodeSessions(
                        container.clientFactory.api(fixedSnapshot).getSessionsForProfile(
                            profile = fixedSnapshot.managementProfile,
                            limit = 50,
                            offset = 0,
                            order = "recent",
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
            val saved = container.settingsStore.loadChatState(fixedScope)
            val currentSessionId = saved.activeSessionId
            val pinnedIds = pinStore.load(fixedScope)
            val options = buildList {
                if (!currentSessionId.isNullOrBlank()) {
                    add(
                        ShareTargetOption(
                            kind = ShareTargetKind.CURRENT,
                            sessionId = currentSessionId,
                            label = TalariaApp.instance.getString(R.string.capture_current_chat),
                            profileName = fixedSnapshot.managementProfile,
                        ),
                    )
                } else {
                    add(
                        ShareTargetOption(
                            kind = ShareTargetKind.CURRENT,
                            sessionId = null,
                            label = TalariaApp.instance.getString(R.string.capture_current_unavailable),
                            profileName = fixedSnapshot.managementProfile,
                            enabled = false,
                        ),
                    )
                }
                SessionFilters.prioritizePinned(sessions, pinnedIds)
                    .filter { it.id in pinnedIds }
                    .filterNot { it.id == currentSessionId }
                    .forEach { session ->
                        add(
                            ShareTargetOption(
                                kind = ShareTargetKind.PINNED,
                                sessionId = session.id,
                                label = session.title?.takeIf { it.isNotBlank() }
                                    ?: session.preview?.take(36)
                                    ?: TalariaApp.instance.getString(R.string.capture_current_chat),
                                profileName = fixedSnapshot.managementProfile,
                            ),
                        )
                    }
                add(
                    ShareTargetOption(
                        kind = ShareTargetKind.NEW,
                        sessionId = null,
                        label = TalariaApp.instance.getString(R.string.capture_new_chat),
                        profileName = fixedSnapshot.managementProfile,
                    ),
                )
            }
            val current = draft ?: return@launch
            val persistedKey = targetKey(current.targetKind, current.targetSessionId)
            val selected = options.firstOrNull { it.key == persistedKey && it.enabled }
                ?: options.firstOrNull { it.kind == ShareTargetKind.CURRENT && it.enabled }
                ?: options.first { it.kind == ShareTargetKind.NEW }
            if (selected.key != persistedKey) {
                draft = current.copy(
                    targetKind = selected.kind,
                    targetSessionId = selected.sessionId,
                    updatedAt = nowMillis(),
                )
                saveDraft()
            }
            _ui.update {
                it.copy(
                    targets = options,
                    selectedTargetKey = selected.key,
                    loadingTargets = false,
                )
            }
        }
    }

    private fun publish() {
        val current = draft ?: return
        val selected = targetKey(current.targetKind, current.targetSessionId)
        _ui.update {
            it.copy(
                text = current.text,
                subject = current.subject,
                instruction = current.instruction,
                items = current.items.map { item ->
                    ShareCaptureItemUi(
                        id = item.id,
                        displayName = item.displayName,
                        mimeType = item.mimeType,
                        sizeBytes = item.sizeBytes,
                        kind = item.kind,
                        inlineImageSupported = item.kind == ShareItemKind.IMAGE &&
                            item.sizeBytes <= com.hermesgadget.talaria.feature.chat.ChatImageAttachments.MAX_TRANSPORT_BYTES,
                    )
                },
                selectedTargetKey = selected,
                profileName = current.managementProfile,
                suggestions = ShareIntakePolicy.urlSuggestions(current.text),
                error = it.error ?: current.deliveryMessage,
                deliveryState = when (current.deliveryState) {
                    ShareDraftDeliveryState.DRAFT -> ShareDeliveryUiState.IDLE
                    ShareDraftDeliveryState.SENDING -> ShareDeliveryUiState.SENDING
                },
            )
        }
    }

    private fun saveDraft() {
        draft?.let(store::save)
    }

    private fun pruneMissingItems() {
        val current = draft ?: return
        val valid = current.items.filter { java.io.File(it.localPath).isFile }
        if (valid.size != current.items.size) {
            draft = current.copy(
                items = valid,
                updatedAt = nowMillis(),
                deliveryMessage = "Some expired share files were removed; review the remaining draft.",
            )
            saveDraft()
        }
    }

    private fun showError(message: String) {
        _ui.update { it.copy(error = message) }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun queryLength(uri: Uri): Long = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor -> descriptor.length }
    }.getOrNull()?.takeIf { it >= 0L } ?: runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private fun readPrefix(file: java.io.File): ByteArray = file.inputStream().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var remaining = ShareIntakePolicy.MAX_SNIFF_BYTES
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            remaining -= count
        }
        output.toByteArray()
    }

    private fun targetKey(kind: ShareTargetKind, sessionId: String?): String =
        "${kind.name}:${sessionId.orEmpty()}"

    private fun decodeSessions(raw: JsonElement): List<SessionSummary> {
        val array = when (raw) {
            is JsonArray -> raw
            else -> (raw as? kotlinx.serialization.json.JsonObject)?.get("sessions") as? JsonArray
                ?: (raw as? kotlinx.serialization.json.JsonObject)?.get("results") as? JsonArray
        }
        return array.orEmpty().mapNotNull { element ->
            runCatching { JsonConfig.json.decodeFromJsonElement<SessionSummary>(element) }.getOrNull()
        }
    }

    companion object {
        fun factory(
            container: AppContainer = TalariaApp.instance.container,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ShareCaptureViewModel(container) as T
        }

        val SUPPORTED_ACTIONS = setOf(
            android.content.Intent.ACTION_SEND,
            android.content.Intent.ACTION_SEND_MULTIPLE,
            android.content.Intent.ACTION_PROCESS_TEXT,
        )
    }
}

/** Stops before a declared, per-item, or aggregate cap is crossed. */
private class BoundedShareInputStream(
    input: InputStream,
    private val limit: Long,
) : FilterInputStream(input) {
    private var copied = 0L

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (copied >= limit) {
            val extra = super.read()
            if (extra >= 0) error("Shared file exceeds its byte budget")
            return -1
        }
        val allowed = minOf(length.toLong(), limit - copied).toInt()
        val count = super.read(buffer, offset, allowed)
        if (count > 0) copied += count
        return count
    }
}
