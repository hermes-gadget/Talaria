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

package com.hermesgadget.talaria.feature.manage.system

import android.content.ContentResolver
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.domain.model.ActionStatus
import com.hermesgadget.talaria.domain.model.OpsActionResponse
import com.hermesgadget.talaria.domain.model.OpsBackupRequest
import com.hermesgadget.talaria.domain.model.OpsDebugShareRequest
import com.hermesgadget.talaria.domain.model.OpsDebugShareResponse
import com.hermesgadget.talaria.domain.model.OpsHookCreateRequest
import com.hermesgadget.talaria.domain.model.OpsHookDeleteRequest
import com.hermesgadget.talaria.domain.model.OpsHooksResponse
import com.hermesgadget.talaria.domain.model.OpsRawConfigResponse
import com.hermesgadget.talaria.domain.model.OpsRawConfigUpdate
import com.hermesgadget.talaria.domain.model.SystemStats
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.HttpException

data class SystemShareRequest(
    val uri: Uri,
    val mimeType: String,
    val subject: String,
    val chooserTitle: String,
)

/**
 * System operations are kept behind a small boundary so the ViewModel can be
 * exercised without constructing the app-wide repository or Retrofit client.
 * The production adapter below delegates to the existing repository/API
 * methods; it does not add or duplicate any network routes.
 */
interface SystemGateway {
    suspend fun getSystemStats(): Result<SystemStats>
    suspend fun getPortal(): Result<JsonElement>
    suspend fun gateway(action: String): Result<ActionStatus>
    suspend fun runDoctor(): Result<ActionStatus>
    suspend fun runSecurityAudit(): Result<ActionStatus>
    suspend fun runBackup(): Result<ActionStatus>
    suspend fun checkUpdate(): Result<JsonElement>

    suspend fun getOpsHooks(): OpsHooksResponse
    suspend fun createOpsHook(request: OpsHookCreateRequest): OpsActionResponse
    suspend fun deleteOpsHook(request: OpsHookDeleteRequest): OpsActionResponse
    suspend fun importOpsUpload(file: MultipartBody.Part, force: RequestBody): OpsActionResponse
    suspend fun createOpsBackup(request: OpsBackupRequest): OpsActionResponse
    suspend fun downloadOpsBackup(archive: String): ResponseBody
    suspend fun createOpsDebugShare(request: OpsDebugShareRequest): OpsDebugShareResponse
    suspend fun getOpsRawConfig(): OpsRawConfigResponse
    suspend fun putOpsRawConfig(update: OpsRawConfigUpdate): OpsActionResponse

    suspend fun applyHermesUpdate(): JsonElement
    suspend fun drainGateway(): JsonElement
    suspend fun getOpsCheckpoints(): JsonElement
    suspend fun pruneOpsCheckpoints(): JsonElement
    suspend fun runConfigMigrate(): JsonElement
    suspend fun runOpsDump(): JsonElement
    suspend fun runOpsPromptSize(): JsonElement
}

private class DefaultSystemGateway(
    private val repo: HermesRepository = TalariaApp.instance.container.hermesRepository,
    private val api: HermesApi = TalariaApp.instance.container.clientFactory.api(),
) : SystemGateway {
    override suspend fun getSystemStats() = repo.getSystemStats()
    override suspend fun getPortal() = repo.getPortal()
    override suspend fun gateway(action: String) = repo.gateway(action)
    override suspend fun runDoctor() = repo.runDoctorToCompletion()
    override suspend fun runSecurityAudit() = repo.runSecurityAuditToCompletion()
    override suspend fun runBackup() = repo.runBackupToCompletion()
    override suspend fun checkUpdate() = repo.checkUpdate()

    override suspend fun getOpsHooks() = api.getOpsHooks()
    override suspend fun createOpsHook(request: OpsHookCreateRequest) = api.createOpsHook(request)
    override suspend fun deleteOpsHook(request: OpsHookDeleteRequest) = api.deleteOpsHook(request)
    override suspend fun importOpsUpload(file: MultipartBody.Part, force: RequestBody) =
        api.importOpsUpload(file, force)
    override suspend fun createOpsBackup(request: OpsBackupRequest) = api.createOpsBackup(request)
    override suspend fun downloadOpsBackup(archive: String) = api.downloadOpsBackup(archive)
    override suspend fun createOpsDebugShare(request: OpsDebugShareRequest) = api.createOpsDebugShare(request)
    override suspend fun getOpsRawConfig() = api.getOpsRawConfig()
    override suspend fun putOpsRawConfig(update: OpsRawConfigUpdate) = api.putOpsRawConfig(update)

    override suspend fun applyHermesUpdate() = api.applyHermesUpdate()
    override suspend fun drainGateway() = api.drainGateway()
    override suspend fun getOpsCheckpoints() = api.getOpsCheckpoints()
    override suspend fun pruneOpsCheckpoints() = api.pruneOpsCheckpoints()
    override suspend fun runConfigMigrate() = api.runConfigMigrate()
    override suspend fun runOpsDump() = api.runOpsDump()
    override suspend fun runOpsPromptSize() = api.runOpsPromptSize()
}

sealed interface HooksUiState {
    data object Loading : HooksUiState
    data class Ready(val response: OpsHooksResponse) : HooksUiState
    data class Failed(val message: String) : HooksUiState
}

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Preparing(val displayName: String) : ImportUiState
    data class Ready(val file: File, val displayName: String) : ImportUiState
    data class Running(val displayName: String) : ImportUiState
    data class Complete(val response: OpsActionResponse) : ImportUiState
    data class Failed(val message: String) : ImportUiState
}

sealed interface BackupDownloadUiState {
    data object Idle : BackupDownloadUiState
    data object Running : BackupDownloadUiState
    data class Complete(val archive: String, val bytes: Long) : BackupDownloadUiState
    data class Failed(val message: String) : BackupDownloadUiState
}

sealed interface DebugShareUiState {
    data object Idle : DebugShareUiState
    data object Running : DebugShareUiState
    data class Complete(val response: OpsDebugShareResponse) : DebugShareUiState
    data class Failed(val message: String) : DebugShareUiState
}

sealed interface RawConfigUiState {
    data object Loading : RawConfigUiState
    data class Ready(
        val yaml: String,
        val path: String?,
        val savedYaml: String = yaml,
        val saving: Boolean = false,
        val message: String? = null,
    ) : RawConfigUiState
    data class Unsupported(val message: String) : RawConfigUiState
    data class Failed(val message: String) : RawConfigUiState
}

data class SystemUiState(
    val stats: com.hermesgadget.talaria.domain.model.SystemStats? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val doctor: String? = null,
    val audit: String? = null,
    val backup: String? = null,
    val update: String? = null,
    val updateAction: String? = null,
    val gatewayDrain: String? = null,
    val portal: String? = null,
    val busy: Boolean = false,
    val updateBusy: Boolean = false,
    val hooks: HooksUiState = HooksUiState.Loading,
    val hooksBusy: Boolean = false,
    val hooksMessage: String? = null,
    val importState: ImportUiState = ImportUiState.Idle,
    val backupDownload: BackupDownloadUiState = BackupDownloadUiState.Idle,
    val debugShare: DebugShareUiState = DebugShareUiState.Idle,
    val rawConfig: RawConfigUiState = RawConfigUiState.Loading,
    val opsCheckpoints: String? = null,
    val opsCheckpointsLoading: Boolean = false,
    val opsResult: String? = null,
    val opsError: String? = null,
    val opsBusy: Boolean = false,
    val shareRequest: SystemShareRequest? = null,
)

class SystemViewModel(
    private val gateway: SystemGateway = DefaultSystemGateway(),
    private val cacheDirectory: File = TalariaApp.instance.cacheDir,
    private val autoRefresh: Boolean = true,
) : ViewModel() {
    private val _ui = MutableStateFlow(SystemUiState())
    val ui: StateFlow<SystemUiState> = _ui.asStateFlow()

    init {
        if (autoRefresh) refresh()
    }

    fun refresh() {
        loadSystem()
        refreshHooks()
        refreshRawConfig()
        getOpsCheckpoints()
    }

    fun loadSystem() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            launch {
                gateway.getSystemStats().fold(
                    onSuccess = { stats -> _ui.update { it.copy(stats = stats, loading = false, error = null) } },
                    onFailure = { error -> _ui.update { it.copy(loading = false, error = error.message) } },
                )
            }
            launch {
                gateway.getPortal().onSuccess { portal ->
                    _ui.update { it.copy(portal = prettySystemJson(portal)) }
                }
            }
        }
    }

    fun runGateway(action: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            gateway.gateway(action)
                .onFailure { error -> _ui.update { it.copy(error = error.message) } }
            _ui.update { it.copy(busy = false) }
            loadSystem()
        }
    }

    fun runDoctor() {
        viewModelScope.launch {
            gateway.runDoctor().fold(
                onSuccess = { action -> _ui.update { it.copy(doctor = formatSystemAction(action)) } },
                onFailure = { error -> _ui.update { it.copy(doctor = error.message) } },
            )
        }
    }

    fun runSecurityAudit() {
        viewModelScope.launch {
            gateway.runSecurityAudit().fold(
                onSuccess = { action -> _ui.update { it.copy(audit = formatSystemAction(action)) } },
                onFailure = { error -> _ui.update { it.copy(audit = error.message) } },
            )
        }
    }

    fun runBackup() {
        viewModelScope.launch {
            gateway.runBackup().fold(
                onSuccess = { action -> _ui.update { it.copy(backup = formatSystemAction(action)) } },
                onFailure = { error -> _ui.update { it.copy(backup = error.message) } },
            )
        }
    }

    fun checkUpdate() {
        viewModelScope.launch {
            gateway.checkUpdate()
                .onSuccess { result -> _ui.update { it.copy(update = prettySystemJson(result)) } }
                .onFailure { error -> _ui.update { it.copy(update = error.message) } }
        }
    }

    fun refreshPortal() {
        viewModelScope.launch {
            gateway.getPortal()
                .onSuccess { result -> _ui.update { it.copy(portal = prettySystemJson(result)) } }
                .onFailure { error -> _ui.update { it.copy(portal = error.message) } }
        }
    }

    fun applyHermesUpdate() {
        viewModelScope.launch {
            _ui.update { it.copy(updateBusy = true) }
            runCatching { gateway.applyHermesUpdate() }.fold(
                onSuccess = { result ->
                    _ui.update { it.copy(updateAction = prettySystemJson(result), updateBusy = false) }
                },
                onFailure = { error ->
                    _ui.update { it.copy(updateAction = errorText(error), updateBusy = false) }
                },
            )
        }
    }

    fun drainGateway() {
        viewModelScope.launch {
            _ui.update { it.copy(updateBusy = true) }
            runCatching { gateway.drainGateway() }.fold(
                onSuccess = { result ->
                    _ui.update { it.copy(gatewayDrain = prettySystemJson(result), updateBusy = false) }
                },
                onFailure = { error ->
                    _ui.update { it.copy(gatewayDrain = errorText(error), updateBusy = false) }
                },
            )
        }
    }

    fun getOpsCheckpoints() {
        viewModelScope.launch {
            _ui.update { it.copy(opsCheckpointsLoading = true, opsError = null) }
            runCatching { gateway.getOpsCheckpoints() }.fold(
                onSuccess = { result ->
                    _ui.update {
                        it.copy(
                            opsCheckpoints = prettySystemJson(result),
                            opsCheckpointsLoading = false,
                        )
                    }
                },
                onFailure = { error ->
                    _ui.update {
                        it.copy(opsCheckpointsLoading = false, opsError = errorText(error))
                    }
                },
            )
        }
    }

    fun pruneOpsCheckpoints() {
        runOpsAction(refreshCheckpoints = true) { gateway.pruneOpsCheckpoints() }
    }

    fun runConfigMigrate() {
        runOpsAction { gateway.runConfigMigrate() }
    }

    fun runOpsDump() {
        runOpsAction { gateway.runOpsDump() }
    }

    fun runOpsPromptSize() {
        runOpsAction { gateway.runOpsPromptSize() }
    }

    private fun runOpsAction(
        refreshCheckpoints: Boolean = false,
        action: suspend () -> JsonElement,
    ) {
        viewModelScope.launch {
            _ui.update { it.copy(opsBusy = true, opsError = null) }
            runCatching { action() }.fold(
                onSuccess = { result ->
                    _ui.update { it.copy(opsResult = prettySystemJson(result), opsBusy = false) }
                    if (refreshCheckpoints) getOpsCheckpoints()
                },
                onFailure = { error ->
                    _ui.update { it.copy(opsBusy = false, opsError = errorText(error)) }
                },
            )
        }
    }

    fun refreshHooks() {
        _ui.update { it.copy(hooks = HooksUiState.Loading, hooksMessage = null) }
        viewModelScope.launch {
            runCatching { gateway.getOpsHooks() }.fold(
                onSuccess = { response -> _ui.update { it.copy(hooks = HooksUiState.Ready(response)) } },
                onFailure = { error -> _ui.update { it.copy(hooks = HooksUiState.Failed(error.message ?: "Could not load hooks")) } },
            )
        }
    }

    fun createHook(request: OpsHookCreateRequest) {
        val validationError = when {
            request.event.isBlank() -> "Choose a hook event"
            request.command.isBlank() -> "Enter a hook command"
            else -> null
        }
        if (validationError != null) {
            _ui.update { it.copy(hooksMessage = validationError) }
            return
        }

        viewModelScope.launch {
            _ui.update { it.copy(hooksBusy = true, hooksMessage = null) }
            runCatching {
                gateway.createOpsHook(request)
                gateway.getOpsHooks()
            }.fold(
                onSuccess = { response ->
                    _ui.update {
                        it.copy(
                            hooks = HooksUiState.Ready(response),
                            hooksBusy = false,
                            hooksMessage = "Hook saved; it takes effect on the next gateway/session restart.",
                        )
                    }
                },
                onFailure = { error ->
                    _ui.update {
                        it.copy(hooksBusy = false, hooksMessage = error.message ?: "Could not save hook")
                    }
                },
            )
        }
    }

    fun deleteHook(entry: com.hermesgadget.talaria.domain.model.OpsHookEntry) {
        val command = entry.command?.takeIf { it.isNotBlank() }
        if (entry.event.isBlank() || command == null) {
            _ui.update { it.copy(hooksMessage = "This hook cannot be deleted because its event or command is missing") }
            return
        }

        viewModelScope.launch {
            _ui.update { it.copy(hooksBusy = true, hooksMessage = null) }
            runCatching {
                gateway.deleteOpsHook(OpsHookDeleteRequest(entry.event, command))
                gateway.getOpsHooks()
            }.fold(
                onSuccess = { response ->
                    _ui.update {
                        it.copy(
                            hooks = HooksUiState.Ready(response),
                            hooksBusy = false,
                            hooksMessage = "Hook deleted",
                        )
                    }
                },
                onFailure = { error ->
                    _ui.update {
                        it.copy(hooksBusy = false, hooksMessage = error.message ?: "Could not delete hook")
                    }
                },
            )
        }
    }

    fun selectImport(uri: Uri, displayName: String, resolver: ContentResolver) {
        val oldFile = (_ui.value.importState as? ImportUiState.Ready)?.file
        oldFile?.delete()
        _ui.update { it.copy(importState = ImportUiState.Preparing(displayName)) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val safeName = displayName.ifBlank { "import.json" }
                    val extension = safeName.substringAfterLast('.', "").lowercase()
                    val suffix = if (extension.isBlank()) ".bin" else ".${extension.take(8)}"
                    val directory = File(cacheDirectory, "ops-import").apply { mkdirs() }
                    val file = File.createTempFile("hermes-import-", suffix, directory)
                    try {
                        val input = resolver.openInputStream(uri)
                            ?: error("Could not open the selected file")
                        input.use { source ->
                            file.outputStream().use { destination -> source.copyTo(destination) }
                        }
                        OpsImportFileValidation.validate(safeName, file)?.let(::error)
                        file
                    } catch (error: Throwable) {
                        file.delete()
                        throw error
                    }
                }
            }.fold(
                onSuccess = { file -> _ui.update { it.copy(importState = ImportUiState.Ready(file, displayName)) } },
                onFailure = { error -> _ui.update { it.copy(importState = ImportUiState.Failed(error.message ?: "Could not read import file")) } },
            )
        }
    }

    fun cancelImport() {
        (_ui.value.importState as? ImportUiState.Ready)?.file?.delete()
        _ui.update { it.copy(importState = ImportUiState.Idle) }
    }

    fun confirmImport() {
        val pending = _ui.value.importState as? ImportUiState.Ready ?: return
        _ui.update { it.copy(importState = ImportUiState.Running(pending.displayName)) }
        viewModelScope.launch {
            runCatching {
                val mediaType = when (pending.displayName.substringAfterLast('.', "").lowercase()) {
                    "json" -> "application/json"
                    "zip" -> "application/zip"
                    else -> "application/octet-stream"
                }.toMediaType()
                val filePart = MultipartBody.Part.createFormData(
                    "file",
                    pending.displayName,
                    pending.file.asRequestBody(mediaType),
                )
                val forcePart = "true".toRequestBody("text/plain".toMediaType())
                gateway.importOpsUpload(filePart, forcePart)
            }.fold(
                onSuccess = { response ->
                    pending.file.delete()
                    _ui.update { it.copy(importState = ImportUiState.Complete(response)) }
                },
                onFailure = { error ->
                    _ui.update { it.copy(importState = ImportUiState.Failed(error.message ?: "Import failed")) }
                },
            )
        }
    }

    fun downloadAndShareBackup() {
        _ui.update { it.copy(backupDownload = BackupDownloadUiState.Running, shareRequest = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val response = gateway.createOpsBackup(OpsBackupRequest())
                    val archive = response.archive?.takeIf { it.isNotBlank() }
                        ?: error(response.error ?: "The backup endpoint did not return an archive")
                    val directory = File(cacheDirectory, "ops-backups").apply { mkdirs() }
                    val sourceName = archive.substringAfterLast('/').ifBlank { "hermes-backup.zip" }
                    val safeName = sourceName.replace(Regex("[^A-Za-z0-9._-]+"), "_")
                    val output = File.createTempFile("hermes-backup-", "-$safeName", directory)
                    gateway.downloadOpsBackup(archive).use { body ->
                        body.byteStream().use { input ->
                            output.outputStream().use { file -> input.copyTo(file) }
                        }
                    }
                    val uri = FileProvider.getUriForFile(
                        TalariaApp.instance,
                        "${TalariaApp.instance.packageName}.files",
                        output,
                    )
                    Triple(archive, output.length(), SystemShareRequest(
                        uri = uri,
                        mimeType = "application/zip",
                        subject = "Hermes backup",
                        chooserTitle = "Share Hermes backup",
                    ))
                }
            }.fold(
                onSuccess = { (archive, bytes, share) ->
                    _ui.update {
                        it.copy(
                            backupDownload = BackupDownloadUiState.Complete(archive, bytes),
                            shareRequest = share,
                        )
                    }
                },
                onFailure = { error ->
                    _ui.update {
                        it.copy(backupDownload = BackupDownloadUiState.Failed(error.message ?: "Could not download backup"))
                    }
                },
            )
        }
    }

    fun createDebugShare() {
        _ui.update { it.copy(debugShare = DebugShareUiState.Running, shareRequest = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val response = gateway.createOpsDebugShare(OpsDebugShareRequest())
                    val text = buildString {
                        appendLine("Hermes debug share")
                        appendLine("Redacted: ${response.redacted}")
                        if (response.autoDeleteSeconds > 0) {
                            appendLine("Auto-delete: ${response.autoDeleteSeconds} seconds")
                        }
                        response.urls.toSortedMap().forEach { (label, url) -> appendLine("$label: $url") }
                        response.failures.forEach { failure -> appendLine("Failure: $failure") }
                    }
                    val directory = File(cacheDirectory, "ops-debug").apply { mkdirs() }
                    val output = File.createTempFile("hermes-debug-share-", ".txt", directory)
                        .also { it.writeText(text) }
                    val uri = FileProvider.getUriForFile(
                        TalariaApp.instance,
                        "${TalariaApp.instance.packageName}.files",
                        output,
                    )
                    response to SystemShareRequest(
                        uri = uri,
                        mimeType = "text/plain",
                        subject = "Hermes debug share",
                        chooserTitle = "Share Hermes debug output",
                    )
                }
            }.fold(
                onSuccess = { (response, share) ->
                    _ui.update {
                        it.copy(debugShare = DebugShareUiState.Complete(response), shareRequest = share)
                    }
                },
                onFailure = { error ->
                    _ui.update { it.copy(debugShare = DebugShareUiState.Failed(error.message ?: "Could not create debug share")) }
                },
            )
        }
    }

    fun refreshRawConfig() {
        _ui.update { it.copy(rawConfig = RawConfigUiState.Loading) }
        viewModelScope.launch {
            runCatching { gateway.getOpsRawConfig() }.fold(
                onSuccess = { response ->
                    _ui.update { it.copy(rawConfig = RawConfigUiState.Ready(response.yaml, response.path)) }
                },
                onFailure = { error ->
                    _ui.update { it.copy(rawConfig = rawConfigFailure(error)) }
                },
            )
        }
    }

    fun updateRawConfig(yaml: String) {
        val current = _ui.value.rawConfig as? RawConfigUiState.Ready ?: return
        _ui.update { it.copy(rawConfig = current.copy(yaml = yaml, message = null)) }
    }

    fun saveRawConfig() {
        val current = _ui.value.rawConfig as? RawConfigUiState.Ready ?: return
        if (current.yaml == current.savedYaml) {
            _ui.update { it.copy(rawConfig = current.copy(message = "No changes to save")) }
            return
        }
        _ui.update { it.copy(rawConfig = current.copy(saving = true, message = null)) }
        viewModelScope.launch {
            runCatching { gateway.putOpsRawConfig(OpsRawConfigUpdate(current.yaml)) }
                .fold(
                    onSuccess = {
                        _ui.update {
                            val ready = it.rawConfig as? RawConfigUiState.Ready ?: return@update it
                            it.copy(rawConfig = ready.copy(savedYaml = ready.yaml, saving = false, message = "Saved"))
                        }
                    },
                    onFailure = { error ->
                        _ui.update {
                            val ready = it.rawConfig as? RawConfigUiState.Ready ?: return@update it
                            it.copy(rawConfig = ready.copy(saving = false, message = error.message ?: "Could not save raw config"))
                        }
                    },
                )
        }
    }

    fun consumeShareRequest() {
        _ui.update { it.copy(shareRequest = null) }
    }

    private fun rawConfigFailure(error: Throwable): RawConfigUiState =
        if (error is HttpException && error.code() in setOf(404, 405)) {
            RawConfigUiState.Unsupported("Raw YAML is not supported by this Hermes dashboard")
        } else {
            RawConfigUiState.Failed(error.message ?: "Could not load raw config")
        }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SystemViewModel() as T
        }
    }
}

private fun errorText(error: Throwable): String = error.message ?: error.toString()

internal fun formatSystemAction(action: ActionStatus): String = buildString {
    append(if (action.exit_code == 0) "Completed" else "Exited ${action.exit_code ?: "?"}")
    if (action.lines.isNotEmpty()) {
        append('\n')
        append(action.lines.joinToString("\n").takeLast(4_000))
    }
}

private fun prettySystemJson(element: kotlinx.serialization.json.JsonElement): String {
    val raw = element.toString()
    return if (raw.length > 2_000) raw.take(2_000) + "…" else raw
}
