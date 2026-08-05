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

package com.hermesgadget.talaria.feature.manage.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.network.HermesApi
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.domain.model.effectiveManagementProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import retrofit2.HttpException

enum class MessagingOnboardingPhase {
    Idle,
    Starting,
    Waiting,
    Ready,
    Applying,
    Applied,
    Cancelling,
    Error,
}

data class TelegramOnboardingState(
    val phase: MessagingOnboardingPhase = MessagingOnboardingPhase.Idle,
    val pairingId: String? = null,
    val deepLink: String? = null,
    val qrPayload: String? = null,
    val expiresAt: String? = null,
    val suggestedUsername: String? = null,
    val botUsername: String? = null,
    val ownerUserId: String? = null,
    val status: String? = null,
    val failureStatus: String? = null,
    val error: String? = null,
    val readyForApply: Boolean = false,
)

data class WhatsAppOnboardingState(
    val phase: MessagingOnboardingPhase = MessagingOnboardingPhase.Idle,
    val pairingId: String? = null,
    val qrPayload: String? = null,
    val expiresAt: String? = null,
    val mode: String = "bot",
    val allowedUsers: String = "",
    val status: String? = null,
    val accountId: String? = null,
    val accountName: String? = null,
    val accountPhone: String? = null,
    val failureStatus: String? = null,
    val error: String? = null,
    val readyForApply: Boolean = false,
)

data class ChannelsOnboardingUiState(
    val telegram: TelegramOnboardingState = TelegramOnboardingState(),
    val whatsapp: WhatsAppOnboardingState = WhatsAppOnboardingState(),
)

private enum class OnboardingPlatform {
    Telegram,
    WhatsApp,
}

private class InvalidOnboardingResponse : IllegalStateException()

/** Owns the short-lived guided Telegram and WhatsApp pairing sessions. */
class ChannelsViewModel(
    private val api: HermesApi = TalariaApp.instance.container.clientFactory.api(),
    private val profileProvider: () -> String? = {
        TalariaApp.instance.container.connectionStore.activeProfile()?.effectiveManagementProfile()
    },
    private val pollIntervalMs: Long = 2_000L,
) : ViewModel() {
    private val _onboarding = MutableStateFlow(ChannelsOnboardingUiState())
    val onboarding: StateFlow<ChannelsOnboardingUiState> = _onboarding.asStateFlow()

    private val pollJobs = mutableMapOf<OnboardingPlatform, Job>()

    fun startTelegram(botName: String) {
        cancelPolling(OnboardingPlatform.Telegram)
        _onboarding.update {
            it.copy(telegram = TelegramOnboardingState(phase = MessagingOnboardingPhase.Starting))
        }
        viewModelScope.launch {
            runCatching {
                api.startTelegramOnboarding(
                    buildJsonObject { put("bot_name", botName.trim()) },
                )
            }.fold(
                onSuccess = { response ->
                    runCatching { parseTelegramStart(response) }
                        .fold(
                            onSuccess = { state ->
                                _onboarding.update { it.copy(telegram = state) }
                                startTelegramPolling(state.pairingId!!)
                            },
                            onFailure = { error -> setTelegramError(error) },
                        )
                },
                onFailure = { error -> setTelegramError(error) },
            )
        }
    }

    fun startWhatsApp(mode: String, allowedUsers: String) {
        cancelPolling(OnboardingPlatform.WhatsApp)
        val normalizedMode = mode.trim().lowercase().ifBlank { "bot" }
        val normalizedAllowedUsers = allowedUsers.trim()
        _onboarding.update {
            it.copy(
                whatsapp = WhatsAppOnboardingState(
                    phase = MessagingOnboardingPhase.Starting,
                    mode = normalizedMode,
                    allowedUsers = normalizedAllowedUsers,
                ),
            )
        }
        viewModelScope.launch {
            runCatching {
                api.startWhatsAppOnboarding(
                    buildJsonObject {
                        put("mode", normalizedMode)
                        put("allowed_users", normalizedAllowedUsers)
                        profileProvider()?.takeIf(::hasNonDefaultProfile)?.let { put("profile", it) }
                    },
                )
            }.fold(
                onSuccess = { response ->
                    runCatching { parseWhatsAppStart(response, normalizedMode, normalizedAllowedUsers) }
                        .fold(
                            onSuccess = { state ->
                                _onboarding.update { it.copy(whatsapp = state) }
                                if (!state.readyForApply) {
                                    startWhatsAppPolling(state.pairingId!!)
                                }
                            },
                            onFailure = { error -> setWhatsAppError(error) },
                        )
                },
                onFailure = { error -> setWhatsAppError(error) },
            )
        }
    }

    fun applyTelegram(allowedUserText: String) {
        val current = _onboarding.value.telegram
        val pairingId = current.pairingId ?: return
        if (!current.readyForApply) return
        val allowedUsers = allowedUserText
            .split(Regex("[,\\s]+"))
            .map(String::trim)
            .filter(String::isNotBlank)
        if (allowedUsers.isEmpty()) return

        cancelPolling(OnboardingPlatform.Telegram)
        _onboarding.update {
            if (it.telegram.pairingId != pairingId) it else it.copy(
                telegram = it.telegram.copy(
                    phase = MessagingOnboardingPhase.Applying,
                    error = null,
                    failureStatus = null,
                ),
            )
        }
        viewModelScope.launch {
            runCatching {
                api.applyTelegramOnboarding(
                    pairingId,
                    buildJsonObject {
                        put("allowed_user_ids", buildJsonArray { allowedUsers.forEach { add(JsonPrimitive(it)) } })
                        profileProvider()?.takeIf(::hasNonDefaultProfile)?.let { put("profile", it) }
                    },
                )
            }.fold(
                onSuccess = {
                    _onboarding.update {
                        if (it.telegram.pairingId != pairingId) it else it.copy(
                            telegram = TelegramOnboardingState(phase = MessagingOnboardingPhase.Applied),
                        )
                    }
                },
                onFailure = { error ->
                    _onboarding.update {
                        if (it.telegram.pairingId != pairingId) it else it.copy(
                            telegram = it.telegram.copy(
                                phase = MessagingOnboardingPhase.Ready,
                                error = userFacingError(error),
                            ),
                        )
                    }
                },
            )
        }
    }

    fun applyWhatsApp(mode: String, allowedUsers: String) {
        val current = _onboarding.value.whatsapp
        val pairingId = current.pairingId ?: return
        if (!current.readyForApply) return
        val normalizedMode = mode.trim().lowercase().ifBlank { current.mode }
        val normalizedAllowedUsers = allowedUsers.trim()
        _onboarding.update {
            if (it.whatsapp.pairingId != pairingId) it else it.copy(
                whatsapp = it.whatsapp.copy(
                    phase = MessagingOnboardingPhase.Applying,
                    mode = normalizedMode,
                    allowedUsers = normalizedAllowedUsers,
                    error = null,
                    failureStatus = null,
                ),
            )
        }
        viewModelScope.launch {
            runCatching {
                api.applyWhatsAppOnboarding(
                    pairingId,
                    buildJsonObject {
                        put("mode", normalizedMode)
                        put("allowed_users", normalizedAllowedUsers)
                        profileProvider()?.takeIf(::hasNonDefaultProfile)?.let { put("profile", it) }
                    },
                )
            }.fold(
                onSuccess = {
                    _onboarding.update {
                        if (it.whatsapp.pairingId != pairingId) it else it.copy(
                            whatsapp = WhatsAppOnboardingState(phase = MessagingOnboardingPhase.Applied),
                        )
                    }
                },
                onFailure = { error ->
                    _onboarding.update {
                        if (it.whatsapp.pairingId != pairingId) it else it.copy(
                            whatsapp = it.whatsapp.copy(
                                phase = MessagingOnboardingPhase.Ready,
                                error = userFacingError(error),
                            ),
                        )
                    }
                },
            )
        }
    }

    fun cancelTelegram() {
        val current = _onboarding.value.telegram
        val pairingId = current.pairingId ?: return
        cancelPolling(OnboardingPlatform.Telegram)
        _onboarding.update {
            if (it.telegram.pairingId != pairingId) it else it.copy(
                telegram = it.telegram.copy(
                    phase = MessagingOnboardingPhase.Cancelling,
                    error = null,
                ),
            )
        }
        viewModelScope.launch {
            runCatching { api.cancelTelegramOnboarding(pairingId) }.fold(
                onSuccess = {
                    _onboarding.update {
                        if (it.telegram.pairingId != pairingId) it else it.copy(
                            telegram = TelegramOnboardingState(),
                        )
                    }
                },
                onFailure = { error ->
                    _onboarding.update {
                        if (it.telegram.pairingId != pairingId) it else it.copy(
                            telegram = it.telegram.copy(
                                phase = current.phase,
                                error = userFacingError(error),
                            ),
                        )
                    }
                },
            )
        }
    }

    fun cancelWhatsApp() {
        val current = _onboarding.value.whatsapp
        val pairingId = current.pairingId ?: return
        cancelPolling(OnboardingPlatform.WhatsApp)
        _onboarding.update {
            if (it.whatsapp.pairingId != pairingId) it else it.copy(
                whatsapp = it.whatsapp.copy(
                    phase = MessagingOnboardingPhase.Cancelling,
                    error = null,
                ),
            )
        }
        viewModelScope.launch {
            runCatching { api.cancelWhatsAppOnboarding(pairingId) }.fold(
                onSuccess = {
                    _onboarding.update {
                        if (it.whatsapp.pairingId != pairingId) it else it.copy(
                            whatsapp = WhatsAppOnboardingState(),
                        )
                    }
                },
                onFailure = { error ->
                    _onboarding.update {
                        if (it.whatsapp.pairingId != pairingId) it else it.copy(
                            whatsapp = it.whatsapp.copy(
                                phase = current.phase,
                                error = userFacingError(error),
                            ),
                        )
                    }
                },
            )
        }
    }

    private fun startTelegramPolling(pairingId: String) {
        cancelPolling(OnboardingPlatform.Telegram)
        pollJobs[OnboardingPlatform.Telegram] = viewModelScope.launch {
            while (isActive) {
                delay(pollIntervalMs.coerceAtLeast(250L))
                val shouldContinue = runCatching {
                    updateTelegramFromPoll(api.getTelegramOnboarding(pairingId), pairingId)
                }.getOrElse { error ->
                    setTelegramError(error, pairingId)
                    false
                }
                if (!shouldContinue) break
            }
        }
    }

    private fun startWhatsAppPolling(pairingId: String) {
        cancelPolling(OnboardingPlatform.WhatsApp)
        pollJobs[OnboardingPlatform.WhatsApp] = viewModelScope.launch {
            while (isActive) {
                delay(pollIntervalMs.coerceAtLeast(250L))
                val shouldContinue = runCatching {
                    updateWhatsAppFromPoll(api.getWhatsAppOnboarding(pairingId), pairingId)
                }.getOrElse { error ->
                    setWhatsAppError(error, pairingId)
                    false
                }
                if (!shouldContinue) break
            }
        }
    }

    private fun updateTelegramFromPoll(response: JsonElement, pairingId: String): Boolean {
        val payload = response.asObject()
        val status = payload.string("status")?.lowercase()
            ?: throw InvalidOnboardingResponse()
        return when (status) {
            "waiting" -> {
                _onboarding.update {
                    if (it.telegram.pairingId != pairingId) it else it.copy(
                        telegram = it.telegram.copy(
                            phase = MessagingOnboardingPhase.Waiting,
                            status = status,
                            error = null,
                            failureStatus = null,
                        ),
                    )
                }
                true
            }
            "ready" -> {
                _onboarding.update {
                    if (it.telegram.pairingId != pairingId) it else it.copy(
                        telegram = it.telegram.copy(
                            phase = MessagingOnboardingPhase.Ready,
                            status = status,
                            botUsername = payload.string("bot_username"),
                            ownerUserId = payload.string("owner_user_id"),
                            expiresAt = payload.string("expires_at") ?: it.telegram.expiresAt,
                            error = null,
                            failureStatus = null,
                            readyForApply = true,
                        ),
                    )
                }
                false
            }
            else -> {
                setTelegramFailure(pairingId, status)
                false
            }
        }
    }

    private fun updateWhatsAppFromPoll(response: JsonElement, pairingId: String): Boolean {
        val payload = response.asObject()
        val status = payload.string("status")?.lowercase()
            ?: throw InvalidOnboardingResponse()
        val qrPayload = payload.string("qr_payload")
        return when (status) {
            "connected" -> {
                _onboarding.update {
                    if (it.whatsapp.pairingId != pairingId) it else it.copy(
                        whatsapp = it.whatsapp.copy(
                            phase = MessagingOnboardingPhase.Ready,
                            status = status,
                            qrPayload = qrPayload ?: it.whatsapp.qrPayload,
                            expiresAt = payload.string("expires_at") ?: it.whatsapp.expiresAt,
                            accountId = payload.string("account_id"),
                            accountName = payload.string("account_name"),
                            accountPhone = payload.string("account_phone"),
                            error = null,
                            failureStatus = null,
                            readyForApply = true,
                        ),
                    )
                }
                false
            }
            "error", "expired", "cancelled" -> {
                _onboarding.update {
                    if (it.whatsapp.pairingId != pairingId) it else it.copy(
                        whatsapp = it.whatsapp.copy(
                            phase = MessagingOnboardingPhase.Error,
                            status = status,
                            qrPayload = qrPayload ?: it.whatsapp.qrPayload,
                            error = payload.string("error"),
                            failureStatus = status,
                            readyForApply = false,
                        ),
                    )
                }
                false
            }
            else -> {
                _onboarding.update {
                    if (it.whatsapp.pairingId != pairingId) it else it.copy(
                        whatsapp = it.whatsapp.copy(
                            phase = MessagingOnboardingPhase.Waiting,
                            status = status,
                            qrPayload = qrPayload ?: it.whatsapp.qrPayload,
                            expiresAt = payload.string("expires_at") ?: it.whatsapp.expiresAt,
                            error = null,
                            failureStatus = null,
                        ),
                    )
                }
                true
            }
        }
    }

    private fun parseTelegramStart(response: JsonElement): TelegramOnboardingState {
        val payload = response.asObject()
        val pairingId = payload.requiredString("pairing_id")
        val deepLink = payload.requiredString("deep_link")
        return TelegramOnboardingState(
            phase = MessagingOnboardingPhase.Waiting,
            pairingId = pairingId,
            deepLink = deepLink,
            qrPayload = payload.string("qr_payload") ?: deepLink,
            expiresAt = payload.string("expires_at"),
            suggestedUsername = payload.string("suggested_username"),
            status = "waiting",
        )
    }

    private fun parseWhatsAppStart(
        response: JsonElement,
        mode: String,
        allowedUsers: String,
    ): WhatsAppOnboardingState {
        val payload = response.asObject()
        val pairingId = payload.requiredString("pairing_id")
        val status = payload.string("status")?.lowercase() ?: "starting"
        val ready = status == "connected"
        return WhatsAppOnboardingState(
            phase = when {
                ready -> MessagingOnboardingPhase.Ready
                status == "error" || status == "expired" || status == "cancelled" ->
                    MessagingOnboardingPhase.Error
                else -> MessagingOnboardingPhase.Waiting
            },
            pairingId = pairingId,
            qrPayload = payload.string("qr_payload"),
            expiresAt = payload.string("expires_at"),
            mode = mode,
            allowedUsers = allowedUsers,
            status = status,
            accountId = payload.string("account_id"),
            accountName = payload.string("account_name"),
            accountPhone = payload.string("account_phone"),
            failureStatus = status.takeIf {
                it == "error" || it == "expired" || it == "cancelled"
            },
            error = payload.string("error"),
            readyForApply = ready,
        )
    }

    private fun setTelegramError(error: Throwable, pairingId: String? = null) {
        _onboarding.update {
            if (pairingId != null && it.telegram.pairingId != pairingId) it else it.copy(
                telegram = it.telegram.copy(
                    phase = MessagingOnboardingPhase.Error,
                    error = userFacingError(error),
                    failureStatus = null,
                    readyForApply = false,
                ),
            )
        }
    }

    private fun setWhatsAppError(error: Throwable, pairingId: String? = null) {
        _onboarding.update {
            if (pairingId != null && it.whatsapp.pairingId != pairingId) it else it.copy(
                whatsapp = it.whatsapp.copy(
                    phase = MessagingOnboardingPhase.Error,
                    error = userFacingError(error),
                    failureStatus = null,
                    readyForApply = false,
                ),
            )
        }
    }

    private fun setTelegramFailure(pairingId: String, status: String) {
        _onboarding.update {
            if (it.telegram.pairingId != pairingId) it else it.copy(
                telegram = it.telegram.copy(
                    phase = MessagingOnboardingPhase.Error,
                    status = status,
                    failureStatus = status,
                    error = null,
                    readyForApply = false,
                ),
            )
        }
    }

    private fun cancelPolling(platform: OnboardingPlatform) {
        pollJobs.remove(platform)?.cancel()
    }

    private fun userFacingError(error: Throwable): String {
        if (error is HttpException) {
            val raw = error.response()?.errorBody()?.string().orEmpty()
            val detail = runCatching {
                (JsonConfig.json.parseToJsonElement(raw) as? JsonObject)
                    ?.string("detail")
            }.getOrNull()
            if (!detail.isNullOrBlank()) return detail
        }
        return error.message.orEmpty()
    }

    override fun onCleared() {
        pollJobs.values.forEach { it.cancel() }
        pollJobs.clear()
            }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChannelsViewModel() as T
        }
    }
}

private fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: throw InvalidOnboardingResponse()

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonObject.requiredString(key: String): String =
    string(key) ?: throw InvalidOnboardingResponse()

private fun hasNonDefaultProfile(value: String): Boolean =
    value.isNotBlank() && !value.equals("default", ignoreCase = true)
