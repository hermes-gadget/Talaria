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
package com.hermesgadget.talaria.feature.manage.config

import com.hermesgadget.talaria.core.network.JsonConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The editor generation captured when a save was requested. */
internal data class ConfigSaveRequest(
    val text: String,
    val draftGeneration: Long,
)

/** A result from the single-owner save pipeline. */
internal sealed class ConfigSaveResult {
    abstract val generation: Long
    abstract val request: ConfigSaveRequest

    internal data class Committed(
        override val generation: Long,
        override val request: ConfigSaveRequest,
        val authoritativeText: String,
    ) : ConfigSaveResult()

    internal data class Failed(
        override val generation: Long,
        override val request: ConfigSaveRequest,
        val message: String,
    ) : ConfigSaveResult()
}

/**
 * Serializes full-config writes and verifies each acknowledgement against a
 * fresh authoritative read before reporting it as committed.
 *
 * The mutex deliberately covers both the PUT and the subsequent GET. That
 * keeps the next draft from reaching the server until the previous write has
 * been verified, so a later submitted draft is always the last write in this
 * client-owned pipeline.
 */
internal class ConfigSaveCoordinator(
    private val putConfig: suspend (JsonObject) -> Result<Unit>,
    private val getConfig: suspend () -> Result<JsonObject>,
) {
    private val saveMutex = Mutex()
    private var nextGeneration = 0L

    suspend fun save(request: ConfigSaveRequest): ConfigSaveResult = saveMutex.withLock {
        val generation = ++nextGeneration
        val config = try {
            JsonConfig.json.parseToJsonElement(request.text).jsonObject
        } catch (error: Throwable) {
            return@withLock ConfigSaveResult.Failed(
                generation = generation,
                request = request,
                message = error.message ?: "Invalid config JSON",
            )
        }

        val putResult = call { putConfig(config) }
        putResult.exceptionOrNull()?.let { error ->
            return@withLock ConfigSaveResult.Failed(
                generation = generation,
                request = request,
                message = error.message ?: "Could not save config",
            )
        }

        val authoritativeResult = call { getConfig() }
        val authoritative = authoritativeResult.getOrElse { error ->
            return@withLock ConfigSaveResult.Failed(
                generation = generation,
                request = request,
                message = "Saved, but could not verify: ${error.message ?: "unknown error"}",
            )
        }

        ConfigSaveResult.Committed(
            generation = generation,
            request = request,
            authoritativeText = JsonConfig.json.encodeToString(authoritative),
        )
    }

    private suspend fun <T> call(block: suspend () -> Result<T>): Result<T> =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
}

/** Compose-independent state transition logic for the config editor. */
internal data class ConfigEditorState(
    val text: String = "",
    val savedText: String = "",
    val draftGeneration: Long = 0L,
    private val lastAppliedSaveGeneration: Long = 0L,
    val message: String? = null,
) {
    val isDirty: Boolean get() = text != savedText

    fun edit(newText: String): ConfigEditorState =
        if (newText == text) {
            this
        } else {
            copy(
                text = newText,
                draftGeneration = draftGeneration + 1,
                message = null,
            )
        }

    fun loaded(serverText: String): ConfigEditorState = copy(
        text = serverText,
        savedText = serverText,
        draftGeneration = draftGeneration + 1,
        message = null,
    )

    fun withMessage(newMessage: String?): ConfigEditorState = copy(message = newMessage)

    fun applySaveResult(result: ConfigSaveResult): ConfigEditorState {
        if (result.generation < lastAppliedSaveGeneration) return this

        val applied = copy(lastAppliedSaveGeneration = result.generation)
        return when (result) {
            is ConfigSaveResult.Committed -> {
                if (draftGeneration == result.request.draftGeneration) {
                    applied.copy(
                        text = result.authoritativeText,
                        savedText = result.authoritativeText,
                        message = "Saved",
                    )
                } else {
                    applied.copy(
                        savedText = result.authoritativeText,
                        message = "Saved; newer edits remain unsaved",
                    )
                }
            }
            is ConfigSaveResult.Failed -> applied.copy(message = result.message)
        }
    }
}
