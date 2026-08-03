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

package com.hermesgadget.talaria.feature.manage.artifacts

import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.domain.model.FsDataUrl
import com.hermesgadget.talaria.domain.model.FsTextFile
import com.hermesgadget.talaria.domain.model.SessionMessage
import com.hermesgadget.talaria.domain.model.SessionsPage
import com.hermesgadget.talaria.feature.manage.files.MAX_SHARE_FILE_BYTES
import com.hermesgadget.talaria.feature.manage.files.ShareFileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val ARTIFACT_SESSION_LIMIT = 50
private const val ARTIFACT_MESSAGE_CONCURRENCY = 4
private const val MAX_ARTIFACT_PREVIEW_BYTES = 16L * 1024L * 1024L
private const val MAX_ARTIFACT_DATA_URL_CHARS = 24L * 1024L * 1024L

enum class ArtifactFilter {
    ALL,
    IMAGE,
    TEXT,
    ARCHIVE,
}

sealed interface ArtifactLoadState {
    data object Loading : ArtifactLoadState
    data class Ready(val artifacts: List<ArtifactRecord>) : ArtifactLoadState
    data class Failed(val message: String) : ArtifactLoadState
}

sealed interface ArtifactPreview {
    val artifact: ArtifactRecord

    data class Image(
        override val artifact: ArtifactRecord,
        val dataUrl: String,
        val mimeType: String,
        val byteSize: Long,
    ) : ArtifactPreview

    data class Text(
        override val artifact: ArtifactRecord,
        val text: String,
        val language: String?,
        val byteSize: Long,
        val truncated: Boolean,
    ) : ArtifactPreview

    data class Binary(
        override val artifact: ArtifactRecord,
        val mimeType: String,
        val byteSize: Long,
    ) : ArtifactPreview
}

data class ArtifactShareRequest(
    val uri: Uri,
    val mimeType: String,
    val subject: String,
)

data class ArtifactsUiState(
    val load: ArtifactLoadState = ArtifactLoadState.Loading,
    val filter: ArtifactFilter = ArtifactFilter.ALL,
    val page: Int = 0,
    val preview: ArtifactPreview? = null,
    val previewLoading: Boolean = false,
    val previewError: String? = null,
    val sharing: Boolean = false,
    val shareRequest: ArtifactShareRequest? = null,
    val shareError: String? = null,
)

/** Loads recent sessions, derives artifact paths, and handles remote previews. */
class ArtifactsViewModel(
    private val loadSessions: suspend () -> Result<SessionsPage> = {
        TalariaApp.instance.container.hermesRepository.getSessionsPage(
            limit = ARTIFACT_SESSION_LIMIT,
            offset = 0,
        )
    },
    private val loadMessages: suspend (String) -> Result<List<SessionMessage>> = { sessionId ->
        TalariaApp.instance.container.hermesRepository.loadMessages(sessionId)
    },
    private val readText: suspend (String) -> Result<FsTextFile> = { path ->
        TalariaApp.instance.container.hermesRepository.fsReadText(path)
    },
    private val readDataUrl: suspend (String) -> FsDataUrl = { path ->
        TalariaApp.instance.container.clientFactory.api().fsReadDataUrl(path)
    },
    private val shareRequestBuilder: (suspend (ArtifactRecord) -> ArtifactShareRequest)? = null,
    private val shareFileManager: ShareFileManager? = null,
) : ViewModel() {
    private val _ui = MutableStateFlow(ArtifactsUiState())
    val ui: StateFlow<ArtifactsUiState> = _ui.asStateFlow()
    private var artifactLoadJob: Job? = null
    private var artifactLoadGeneration = 0L
    private var previewJob: Job? = null
    private var previewGeneration = 0L
    private var previewArtifactId: String? = null
    private var previewArtifactPath: String? = null
    private var shareJob: Job? = null
    private val defaultShareFileManager by lazy {
        ShareFileManager(TalariaApp.instance.cacheDir)
    }

    init {
        runCatching { (shareFileManager ?: defaultShareFileManager).cleanupStaleFiles() }
        refresh()
    }

    fun refresh() {
        artifactLoadGeneration += 1
        val generation = artifactLoadGeneration
        artifactLoadJob?.cancel()
        cancelPreview()
        _ui.update {
            it.copy(
                load = ArtifactLoadState.Loading,
                page = 0,
                preview = null,
                previewLoading = false,
                previewError = null,
                shareError = null,
            )
        }
        artifactLoadJob = viewModelScope.launch {
            try {
                val artifacts = loadArtifacts()
                if (generation == artifactLoadGeneration) {
                    _ui.update { it.copy(load = ArtifactLoadState.Ready(artifacts), page = 0) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == artifactLoadGeneration) {
                    _ui.update {
                        it.copy(
                            load = ArtifactLoadState.Failed(error.message ?: "Could not load artifacts"),
                            page = 0,
                        )
                    }
                }
            }
        }
    }

    fun setFilter(filter: ArtifactFilter) {
        _ui.update { it.copy(filter = filter, page = 0) }
    }

    fun setPage(page: Int) {
        _ui.update { it.copy(page = page.coerceAtLeast(0)) }
    }

    fun openPreview(artifact: ArtifactRecord) {
        previewJob?.cancel()
        previewGeneration += 1
        val generation = previewGeneration
        previewArtifactId = artifact.id
        previewArtifactPath = artifact.path
        _ui.update { it.copy(preview = null, previewLoading = true, previewError = null) }
        previewJob = viewModelScope.launch {
            try {
                val preview = when (artifact.kind) {
                    ArtifactKind.TEXT -> {
                        val file = readText(artifact.path).getOrThrow()
                        require(file.byteSize <= 0L || file.byteSize <= MAX_ARTIFACT_PREVIEW_BYTES) {
                            "Artifact preview is too large"
                        }
                        ArtifactPreview.Text(
                            artifact = artifact,
                            text = file.text,
                            language = file.language,
                            byteSize = file.byteSize,
                            truncated = file.truncated,
                        )
                    }

                    ArtifactKind.IMAGE -> {
                        val file = readDataUrl(artifact.path)
                        boundedDataUrl(file.dataUrl, file.byteSize)
                        ArtifactPreview.Image(
                            artifact = artifact,
                            dataUrl = file.dataUrl,
                            mimeType = file.mimeType ?: "image/*",
                            byteSize = file.byteSize,
                        )
                    }

                    ArtifactKind.ARCHIVE -> {
                        val file = readDataUrl(artifact.path)
                        boundedDataUrl(file.dataUrl, file.byteSize)
                        ArtifactPreview.Binary(
                            artifact = artifact,
                            mimeType = file.mimeType ?: "application/octet-stream",
                            byteSize = file.byteSize,
                        )
                    }
                }
                if (isCurrentPreview(generation, artifact)) {
                    _ui.update { it.copy(preview = preview, previewLoading = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrentPreview(generation, artifact)) {
                    _ui.update {
                        it.copy(
                            previewLoading = false,
                            previewError = error.message ?: "Could not preview ${artifact.label}",
                        )
                    }
                }
            }
        }
    }

    fun closePreview() {
        cancelPreview()
        _ui.update { it.copy(preview = null, previewLoading = false, previewError = null) }
    }

    fun share(artifact: ArtifactRecord) {
        if (_ui.value.sharing) return
        shareJob?.cancel()
        _ui.update { it.copy(sharing = true, shareError = null, shareRequest = null) }
        shareJob = viewModelScope.launch {
            try {
                val request = shareRequestBuilder?.invoke(artifact) ?: prepareShare(artifact)
                _ui.update { it.copy(sharing = false, shareRequest = request) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _ui.update {
                    it.copy(
                        sharing = false,
                        shareError = error.message ?: "Could not share ${artifact.label}",
                    )
                }
            }
        }
    }

    fun consumeShareRequest() {
        _ui.update { it.copy(shareRequest = null) }
    }

    private suspend fun loadArtifacts(): List<ArtifactRecord> = coroutineScope {
        // The desktop browser intentionally scans a recent bounded session slice;
        // doing the same keeps a mobile refresh responsive on large Hermes homes.
        val sessions = loadSessions().getOrThrow().sessions.take(ARTIFACT_SESSION_LIMIT)
        val permits = Semaphore(ARTIFACT_MESSAGE_CONCURRENCY)
        sessions.map { session ->
            async {
                permits.withPermit {
                    session to loadMessages(session.id)
                }
            }
        }.let { requests ->
            requests.map { it.await() }
        }.flatMap { (session, result) ->
            result.getOrNull()?.let { extractArtifacts(session, it) }.orEmpty()
        }.sortedWith(compareByDescending<ArtifactRecord> { it.timestamp.orEmpty() }.thenBy { it.path })
    }

    private fun cancelPreview() {
        previewJob?.cancel()
        previewJob = null
        previewGeneration += 1
        previewArtifactId = null
        previewArtifactPath = null
    }

    private fun isCurrentPreview(generation: Long, artifact: ArtifactRecord): Boolean =
        generation == previewGeneration &&
            artifact.id == previewArtifactId &&
            artifact.path == previewArtifactPath

    override fun onCleared() {
        artifactLoadJob?.cancel()
        cancelPreview()
        shareJob?.cancel()
        super.onCleared()
    }

    private suspend fun prepareShare(artifact: ArtifactRecord): ArtifactShareRequest = withContext(Dispatchers.IO) {
        val app = TalariaApp.instance
        val (bytes, mimeType) = when (artifact.kind) {
            ArtifactKind.TEXT -> {
                val text = readText(artifact.path).getOrThrow()
                val bytes = text.text.toByteArray(Charsets.UTF_8)
                require(bytes.size.toLong() <= MAX_SHARE_FILE_BYTES) {
                    "Artifact is too large to share"
                }
                bytes to (text.mimeType ?: "text/plain")
            }

            ArtifactKind.IMAGE, ArtifactKind.ARCHIVE -> {
                val data = readDataUrl(artifact.path)
                val decoded = decodeDataUrl(boundedDataUrl(data.dataUrl, data.byteSize))
                decoded.bytes to (data.mimeType ?: decoded.mimeType ?: mimeTypeFor(artifact))
            }
        }
        require(bytes.isNotEmpty()) { "${artifact.label} is empty" }

        val safeName = artifact.label
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_', '.')
            .take(80)
            .ifBlank { "artifact${suffixFor(artifact)}" }
        val file = (shareFileManager ?: defaultShareFileManager).createShareFile(
            prefix = "artifact-",
            suffix = "-$safeName",
            bytes = bytes,
        )
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        ArtifactShareRequest(
            uri = uri,
            mimeType = mimeType,
            subject = "Hermes artifact ${artifact.label}",
        )
    }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ArtifactsViewModel() as T
        }

        private fun suffixFor(artifact: ArtifactRecord): String = when (artifact.kind) {
            ArtifactKind.IMAGE -> ".img"
            ArtifactKind.TEXT -> ".txt"
            ArtifactKind.ARCHIVE -> ".bin"
        }

        private fun mimeTypeFor(artifact: ArtifactRecord): String = when (artifact.kind) {
            ArtifactKind.IMAGE -> "image/*"
            ArtifactKind.TEXT -> "text/plain"
            ArtifactKind.ARCHIVE -> "application/octet-stream"
        }

        private data class DecodedDataUrl(
            val bytes: ByteArray,
            val mimeType: String?,
        )

        private fun boundedDataUrl(dataUrl: String, declaredBytes: Long): String {
            require(dataUrl.length.toLong() <= MAX_ARTIFACT_DATA_URL_CHARS) {
                "Artifact preview is too large"
            }
            require(declaredBytes <= 0L || declaredBytes <= MAX_ARTIFACT_PREVIEW_BYTES) {
                "Artifact preview is too large"
            }
            val comma = dataUrl.indexOf(',')
            if (comma > 0) {
                val payloadLength = (dataUrl.length - comma - 1).toLong()
                val estimatedBytes = if (dataUrl.substring(0, comma)
                        .split(';')
                        .any { it.equals("base64", ignoreCase = true) }
                ) {
                    (payloadLength / 4L) * 3L
                } else {
                    payloadLength
                }
                require(estimatedBytes <= MAX_ARTIFACT_PREVIEW_BYTES) {
                    "Artifact preview is too large"
                }
            }
            return dataUrl
        }

        private fun decodeDataUrl(value: String): DecodedDataUrl {
            require(value.startsWith("data:", ignoreCase = true)) { "Filesystem preview was not a data URL" }
            val comma = value.indexOf(',')
            require(comma > 5) { "Malformed filesystem data URL" }
            val metadata = value.substring(5, comma)
            val payload = value.substring(comma + 1)
            val mime = metadata.substringBefore(';').takeIf { it.isNotBlank() }
            val bytes = if (metadata.split(';').any { it.equals("base64", ignoreCase = true) }) {
                require(payload.length.toLong() <= MAX_ARTIFACT_DATA_URL_CHARS) {
                    "Artifact preview is too large"
                }
                Base64.decode(payload, Base64.DEFAULT).also {
                    require(it.size.toLong() <= MAX_SHARE_FILE_BYTES) {
                        "Artifact is too large to share"
                    }
                }
            } else {
                Uri.decode(payload).toByteArray(Charsets.UTF_8).also {
                    require(it.size.toLong() <= MAX_SHARE_FILE_BYTES) {
                        "Artifact is too large to share"
                    }
                }
            }
            return DecodedDataUrl(bytes, mime)
        }
    }
}
