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

package com.hermesgadget.talaria.feature.settings

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.prefs.SettingsStore
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.ui.theme.ThemeOverrides
import com.hermesgadget.talaria.ui.theme.ThemePreset
import com.hermesgadget.talaria.ui.theme.ThemeSkin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.hermesgadget.talaria.core.util.suspendResult

sealed interface ServerSkinState {
    data object Checking : ServerSkinState
    data class Available(val skin: ThemeSkin) : ServerSkinState
    data class Unsupported(val message: String) : ServerSkinState
    data class Unavailable(val message: String) : ServerSkinState
}

data class ThemeUiState(
    val selectedPresetId: String,
    val serverSkin: ServerSkinState = ServerSkinState.Checking,
    val syncing: Boolean = false,
    val message: String? = null,
)

class ThemeViewModel(
    private val settings: SettingsStore = TalariaApp.instance.container.settingsStore,
    private val repository: HermesRepository = TalariaApp.instance.container.hermesRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(ThemeUiState(selectedPresetId = settings.themePreset))
    val ui: StateFlow<ThemeUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            settings.themePresetFlow.collect { id ->
                _ui.update { it.copy(selectedPresetId = id) }
            }
        }
        refreshServerSkin()
    }

    fun selectPreset(preset: ThemePreset) {
        settings.themePreset = preset.id
        // A curated preset should be visible immediately even if Material You
        // had previously been enabled from the You screen.
        settings.dynamicColor = false
        ThemeOverrides.clear()
        _ui.update { it.copy(message = "${preset.displayName} applied") }
    }

    fun refreshServerSkin() {
        _ui.update { it.copy(serverSkin = ServerSkinState.Checking, message = null) }
        viewModelScope.launch {
            repository.getConfig().fold(
                onSuccess = { config ->
                    val skin = parseServerSkin(config)
                    _ui.update {
                        it.copy(
                            serverSkin = skin?.let(ServerSkinState::Available)
                                ?: ServerSkinState.Unsupported(
                                    "This server has no primary, accent, or background skin fields in /api/config.",
                                ),
                        )
                    }
                },
                onFailure = { error ->
                    _ui.update {
                        it.copy(
                            serverSkin = ServerSkinState.Unavailable(
                                error.message?.takeIf(String::isNotBlank)
                                    ?: "Connect to a Hermes server to check for skin data.",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun syncFromServer() {
        if (_ui.value.serverSkin !is ServerSkinState.Available || _ui.value.syncing) return
        _ui.update { it.copy(syncing = true, message = null) }
        viewModelScope.launch {
            repository.getConfig().fold(
                onSuccess = { config ->
                    val skin = parseServerSkin(config)
                    if (skin == null) {
                        _ui.update {
                            it.copy(
                                syncing = false,
                                serverSkin = ServerSkinState.Unsupported(
                                    "This server has no primary, accent, or background skin fields in /api/config.",
                                ),
                            )
                        }
                    } else {
                        settings.dynamicColor = false
                        ThemeOverrides.apply(skin)
                        _ui.update {
                            it.copy(
                                syncing = false,
                                serverSkin = ServerSkinState.Available(skin),
                                message = "Applied server skin: ${skin.supportedFields.sorted().joinToString()}",
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _ui.update {
                        it.copy(
                            syncing = false,
                            message = error.message ?: "Could not fetch the server skin.",
                        )
                    }
                },
            )
        }
    }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ThemeViewModel() as T
        }
    }
}

/** Extract supported color fields from either a direct config or a nested skin object. */
fun parseServerSkin(config: JsonObject): ThemeSkin? {
    val fields = linkedMapOf<String, Color>()

    fun visit(element: JsonElement) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                val normalized = key.lowercase().replace("_", "").replace("-", "")
                if (normalized in SUPPORTED_SKIN_KEYS) {
                    parseHexColor(value)?.let { fields.putIfAbsent(normalized, it) }
                }
                visit(value)
            }
            is JsonArray -> element.forEach(::visit)
            else -> Unit
        }
    }

    visit(config)
    return ThemeSkin(
        primary = fields["primary"],
        accent = fields["accent"],
        background = fields["background"],
    ).takeUnless { it.isEmpty }
}

private fun parseHexColor(element: JsonElement): Color? {
    val raw = runCatching { element.jsonPrimitive.content.trim() }.getOrNull() ?: return null
    val digits = raw.removePrefix("#").removePrefix("0x").removePrefix("0X")
    val argb = when (digits.length) {
        3 -> "FF${digits.map { "$it$it" }.joinToString("")}"
        6 -> "FF$digits"
        8 -> digits
        else -> return null
    }
    return runCatching { Color(argb.toLong(16).toInt()) }.getOrNull()
}

private val SUPPORTED_SKIN_KEYS = setOf("primary", "accent", "background")
