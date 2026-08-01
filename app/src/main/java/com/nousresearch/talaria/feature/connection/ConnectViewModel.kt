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


package com.nousresearch.talaria.feature.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.core.data.repo.ConnectionRepository
import com.nousresearch.talaria.core.network.WsAuthHelper
import com.nousresearch.talaria.core.network.NativeOidcLogin
import com.nousresearch.talaria.core.network.NativeOidcProvider
import com.nousresearch.talaria.domain.model.AuthMode
import com.nousresearch.talaria.domain.model.ConnectionProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConnectUiState(
    val name: String = "Home Hermes",
    // 10.0.2.2 is the emulator alias for the host loopback; 127.0.0.1 is the emulator itself.
    val baseUrl: String = "http://10.0.2.2:9119",
    val authMode: AuthMode = AuthMode.SESSION_TOKEN,
    val username: String = "",
    val password: String = "",
    val passwordProvider: String = "",
    val sessionToken: String = "",
    val bearerToken: String = "",
    val managementProfile: String = "",
    val pinSha256: String = "",
    val testing: Boolean = false,
    val diagnosing: Boolean = false,
    val error: String? = null,
    val statusLine: String? = null,
    val doctorReport: String? = null,
    val oidcSigningIn: Boolean = false,
    val oidcProviders: List<NativeOidcProvider> = emptyList(),
    val oidcProvider: String = "",
)

class ConnectViewModel(
    private val repo: ConnectionRepository = TalariaApp.instance.container.connectionRepository,
    private val nativeOidc: NativeOidcLogin = TalariaApp.instance.container.nativeOidcLogin,
) : ViewModel() {
    private var draftProfileId: String? = null
    private val _ui = MutableStateFlow(ConnectUiState())
    val ui: StateFlow<ConnectUiState> = _ui.asStateFlow()
    val profiles: StateFlow<List<ConnectionProfile>> = repo.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun update(transform: (ConnectUiState) -> ConnectUiState) {
        _ui.value = transform(_ui.value)
    }

    fun saveAndTest(onSuccess: () -> Unit) {
        val s = _ui.value
        viewModelScope.launch {
            _ui.value = s.copy(testing = true, error = null)
            try {
                val profile = repo.save(
                    name = s.name,
                    baseUrl = s.baseUrl,
                    authMode = s.authMode,
                    username = s.username.ifBlank { null },
                    authProvider = s.passwordProvider,
                    sessionToken = s.sessionToken.ifBlank { null },
                    password = s.password.ifBlank { null },
                    bearerToken = s.bearerToken.ifBlank { null },
                    managementProfile = s.managementProfile,
                    pinSha256 = s.pinSha256.ifBlank { null },
                    existingId = draftProfileId,
                )
                draftProfileId = profile.id
                repo.setActive(profile.id)
                val result = repo.testConnection()
                result.fold(
                    onSuccess = {
                        _ui.value = _ui.value.copy(
                            testing = false,
                            statusLine = "Connected · Hermes ${it.version ?: "unknown"}",
                        )
                        onSuccess()
                    },
                    onFailure = {
                        _ui.value = _ui.value.copy(testing = false, error = it.message ?: "Connection failed")
                    },
                )
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(testing = false, error = t.message)
            }
        }
    }

    fun select(id: String) = repo.setActive(id)
    fun delete(id: String) = repo.delete(id)

    /** Load public connection fields for editing; blank secret inputs preserve encrypted values. */
    fun edit(profile: ConnectionProfile) {
        draftProfileId = profile.id
        repo.setActive(profile.id)
        _ui.value = ConnectUiState(
            name = profile.name,
            baseUrl = profile.baseUrl,
            authMode = profile.authMode,
            username = profile.username.orEmpty(),
            passwordProvider = profile.authProvider,
            managementProfile = profile.managementProfile,
            pinSha256 = profile.pinSha256.orEmpty(),
            oidcProvider = profile.authProvider,
            statusLine = "Editing ${profile.name} · blank secret fields keep their saved values",
        )
    }

    /** Persist the draft profile and enter the app without a live /api/status probe. */
    fun saveAndContinue(onSuccess: () -> Unit) {
        val s = _ui.value
        viewModelScope.launch {
            _ui.value = s.copy(testing = true, error = null)
            try {
                val profile = repo.save(
                    name = s.name,
                    baseUrl = s.baseUrl,
                    authMode = s.authMode,
                    username = s.username.ifBlank { null },
                    authProvider = s.passwordProvider,
                    sessionToken = s.sessionToken.ifBlank { null },
                    password = s.password.ifBlank { null },
                    bearerToken = s.bearerToken.ifBlank { null },
                    managementProfile = s.managementProfile,
                    pinSha256 = s.pinSha256.ifBlank { null },
                    existingId = draftProfileId,
                )
                draftProfileId = profile.id
                repo.setActive(profile.id)
                _ui.value = _ui.value.copy(testing = false, statusLine = "Saved · ${profile.baseUrl}")
                onSuccess()
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(testing = false, error = t.message)
            }
        }
    }

    /** Preflight: status reachability, auth gate, WS ticket — copy-pasteable fixes. */
    fun runConnectionDoctor() {
        val s = _ui.value
        viewModelScope.launch {
            _ui.value = s.copy(diagnosing = true, doctorReport = null, error = null)
            val lines = mutableListOf<String>()
            try {
                repo.save(
                    name = s.name,
                    baseUrl = s.baseUrl,
                    authMode = s.authMode,
                    username = s.username.ifBlank { null },
                    authProvider = s.passwordProvider,
                    sessionToken = s.sessionToken.ifBlank { null },
                    password = s.password.ifBlank { null },
                    bearerToken = s.bearerToken.ifBlank { null },
                    managementProfile = s.managementProfile,
                    pinSha256 = s.pinSha256.ifBlank { null },
                    existingId = draftProfileId,
                ).also {
                    draftProfileId = it.id
                    repo.setActive(it.id)
                }

                lines += "URL: ${s.baseUrl.trimEnd('/')}"
                if (s.baseUrl.contains("127.0.0.1") || s.baseUrl.contains("localhost")) {
                    lines += "Hint: On emulator use http://10.0.2.2:9119 (host loopback), not 127.0.0.1."
                }

                val status = repo.testConnection()
                status.fold(
                    onSuccess = { st ->
                        lines += "GET /api/status · OK · Hermes ${st.version ?: "?"}"
                        lines += "auth_required=${st.auth_required} · providers=${st.auth_providers.joinToString().ifBlank { "—" }}"
                        st.gateway?.let {
                            lines += "gateway running=${it.running} pid=${it.pid} state=${it.state}"
                        }
                    },
                    onFailure = { e ->
                        lines += "GET /api/status · FAIL · ${e.message}"
                        lines += "Fix: confirm dashboard is up (`hermes dashboard`), URL reachable, and auth mode matches."
                    },
                )

                val container = TalariaApp.instance.container
                container.wsAuthHelper.invalidate()
                val authParam = container.wsAuthHelper.authQueryParam()
                when {
                    authParam.startsWith("ticket=") -> lines += "WS auth · ticket minted (gated dashboard path)"
                    authParam.startsWith("token=") -> lines += "WS auth · session token attached"
                    else -> {
                        lines += "WS auth · no ticket/token available"
                        lines += "Fix: sign in (basic/OIDC) or paste a valid session token."
                    }
                }
                lines += "Close 4401: ${WsAuthHelper.explainCloseCode(4401)}"
                lines += "Close 4403: ${WsAuthHelper.explainCloseCode(4403)}"

                // Short PTY probe
                val (pty, flow) = container.chatRepository.openPty(null, java.util.UUID.randomUUID().toString())
                var ptyResult = "timeout (3s) — check Host guards / pty extra"
                try {
                    kotlinx.coroutines.withTimeout(3_000) {
                        flow.collect { event ->
                            when (event) {
                                is com.nousresearch.talaria.core.network.PtyEvent.Connected -> {
                                    ptyResult = "Connected (channel=${event.channel})"
                                    pty.close()
                                    throw kotlinx.coroutines.CancellationException("probe-ok")
                                }
                                is com.nousresearch.talaria.core.network.PtyEvent.Failure -> {
                                    ptyResult = "FAIL · ${event.message}"
                                    throw kotlinx.coroutines.CancellationException("probe-fail")
                                }
                                is com.nousresearch.talaria.core.network.PtyEvent.Closed -> {
                                    ptyResult = "Closed before Connected"
                                    throw kotlinx.coroutines.CancellationException("probe-closed")
                                }
                                else -> Unit
                            }
                        }
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    pty.close()
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // expected early exit from probe
                } catch (t: Throwable) {
                    ptyResult = "FAIL · ${t.message}"
                    pty.close()
                }
                lines += "PTY probe · $ptyResult"
            } catch (t: Throwable) {
                lines += "Doctor error: ${t.message}"
            }
            _ui.value = _ui.value.copy(
                diagnosing = false,
                doctorReport = lines.joinToString("\n"),
            )
        }
    }

    fun applyDeepLinkProfile(profile: String?) {
        if (!profile.isNullOrBlank()) {
            update { it.copy(managementProfile = profile) }
        }
    }

    /** Run Hermes' RFC 8252 system-browser + loopback + PKCE native flow. */
    fun startOidcLogin(openBrowser: (String) -> Unit, onSuccess: () -> Unit) {
        val s = _ui.value
        viewModelScope.launch {
            _ui.value = s.copy(oidcSigningIn = true, error = null, statusLine = null)
            try {
                val profile = repo.save(
                    name = s.name,
                    baseUrl = s.baseUrl,
                    authMode = AuthMode.OIDC_BROWSER,
                    username = null,
                    authProvider = s.oidcProvider,
                    sessionToken = null,
                    password = null,
                    bearerToken = null,
                    managementProfile = s.managementProfile,
                    pinSha256 = s.pinSha256.ifBlank { null },
                    existingId = draftProfileId,
                )
                draftProfileId = profile.id
                repo.setActive(profile.id)

                val providers = nativeOidc.providers()
                _ui.value = _ui.value.copy(oidcProviders = providers)
                val provider = s.oidcProvider.takeIf { selected -> providers.any { it.name == selected } }
                    ?: providers.singleOrNull()?.name
                    ?: if (providers.isEmpty()) {
                        error("Hermes did not advertise an OAuth provider")
                    } else {
                        error("Choose an OAuth provider, then tap Sign in again")
                    }
                nativeOidc.signIn(profile.id, provider, openBrowser)
                val status = repo.testConnection().getOrThrow()
                _ui.value = _ui.value.copy(
                    oidcSigningIn = false,
                    statusLine = "Signed in · Hermes ${status.version ?: "unknown"}",
                )
                onSuccess()
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(
                    oidcSigningIn = false,
                    error = t.message ?: "Browser sign-in failed",
                )
            }
        }
    }

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ConnectViewModel() as T
        }
    }
}
