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


package com.hermesgadget.talaria.feature.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.repo.ConnectionRepository
import com.hermesgadget.talaria.core.network.CleartextPolicy
import com.hermesgadget.talaria.core.network.ConnectionOrigin
import com.hermesgadget.talaria.core.network.WsAuthHelper
import com.hermesgadget.talaria.core.network.NativeOidcLogin
import com.hermesgadget.talaria.core.network.NativeOidcProvider
import com.hermesgadget.talaria.core.network.SnapshotAuthGuard
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.CredentialPoolEntry
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CleartextConsentRequest(
    val host: String,
    val baseUrl: String,
    val origin: String,
)

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
    val providerSection: ProviderSectionState = ProviderSectionState.Idle,
    val providerDraft: ProviderDraft = ProviderDraft(),
    val providerBusy: ProviderBusyAction? = null,
    val providerNotice: String? = null,
    val providerValidation: ProviderValidationUi? = null,
    val customEndpointValidation: ProviderValidationUi? = null,
    val providerOAuthSession: ProviderOAuthSession? = null,
    val providerConfirmation: ProviderConfirmation? = null,
    /** Pending explicit cleartext consent for a private/LAN http destination. */
    val cleartextConsentRequest: CleartextConsentRequest? = null,
    /** Set by confirmCleartextConsent(); passed through to the next save. */
    val cleartextConsentApproved: Boolean = false,
    /** Exact normalized origin approved by the user in this draft. */
    val cleartextConsentOrigin: String? = null,
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
        val before = _ui.value
        val next = transform(before)
        val originChanged = before.baseUrl != next.baseUrl &&
            ConnectionOrigin.normalize(before.baseUrl) != ConnectionOrigin.normalize(next.baseUrl)
        _ui.value = if (originChanged) {
            next.copy(
                cleartextConsentApproved = false,
                cleartextConsentOrigin = null,
                cleartextConsentRequest = null,
            )
        } else {
            next
        }
    }

    fun updateProviderDraft(transform: (ProviderDraft) -> ProviderDraft) {
        _ui.value = _ui.value.copy(providerDraft = transform(_ui.value.providerDraft))
    }

    /** Load provider settings for an already-saved connection, without changing the connection flow. */
    fun loadProviderOnboarding() {
        if (repo.active() == null) {
            _ui.value = _ui.value.copy(
                providerSection = ProviderSectionState.Failure(
                    "Save a connection before loading provider settings",
                ),
                providerBusy = null,
            )
            return
        }
        viewModelScope.launch {
            val previous = providerContent(_ui.value.providerSection)
            _ui.value = _ui.value.copy(
                providerSection = ProviderSectionState.Loading,
                providerBusy = ProviderBusyAction.LOAD,
                providerNotice = null,
            )
            runCatching { loadProviderData() }
                .onSuccess { content ->
                    _ui.value = _ui.value.copy(
                        providerSection = ProviderSectionState.Content(content),
                        providerBusy = null,
                    )
                }
                .onFailure { failure ->
                    _ui.value = _ui.value.copy(
                        providerSection = ProviderSectionState.Failure(
                            failure.message ?: "Could not load provider settings",
                            previous,
                        ),
                        providerBusy = null,
                    )
                }
        }
    }

    /** Persist the connection draft but stay on Connect so the provider step can continue. */
    fun saveConnectionAndLoadProviders() {
        val snapshot = _ui.value
        viewModelScope.launch {
            if (maybeRequestCleartextConsent(snapshot)) return@launch
            _ui.value = snapshot.copy(
                providerSection = ProviderSectionState.Loading,
                providerBusy = ProviderBusyAction.LOAD,
                providerNotice = null,
                error = null,
            )
            try {
                val profile = saveConnectionDraft(snapshot)
                draftProfileId = profile.id
                repo.setActive(profile.id)
                val content = loadProviderData()
                _ui.value = _ui.value.copy(
                    providerSection = ProviderSectionState.Content(content),
                    providerBusy = null,
                    statusLine = "Saved · provider settings ready",
                )
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(
                    providerSection = ProviderSectionState.Failure(
                        t.message ?: "Could not prepare provider settings",
                        providerContent(_ui.value.providerSection),
                    ),
                    providerBusy = null,
                    error = t.message,
                )
            }
        }
    }

    fun validateProviderCredential() {
        val draft = _ui.value.providerDraft
        val provider = draft.credentialProvider.ifBlank { draft.selectedProvider }.trim()
        val key = draft.credentialEnvKey.trim().ifBlank { providerEnvKey(provider) }
        val apiKey = draft.credentialApiKey.trim()
        if (provider.isBlank()) {
            _ui.value = _ui.value.copy(
                providerValidation = ProviderValidationUi(false, false, "Choose a provider first"),
            )
            return
        }
        if (apiKey.isBlank()) {
            _ui.value = _ui.value.copy(
                providerValidation = ProviderValidationUi(false, false, "Enter an API key to validate"),
            )
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                providerBusy = ProviderBusyAction.VALIDATE_PROVIDER,
                providerValidation = null,
            )
            try {
                val response = providerApi().validateProvider(buildJsonObject {
                    put("key", key)
                    put("value", apiKey)
                    put("api_key", apiKey)
                })
                val result = parseProviderValidation(response)
                _ui.value = _ui.value.copy(
                    providerBusy = null,
                    providerValidation = ProviderValidationUi(result.ok, result.reachable, validationMessage(result)),
                )
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(
                    providerBusy = null,
                    providerValidation = ProviderValidationUi(
                        ok = false,
                        reachable = false,
                        message = "Provider validation is unavailable on this Hermes version; enter the key to save it. (${t.message ?: "request failed"})",
                    ),
                )
            }
        }
    }

    fun validateCustomEndpoint() {
        val draft = _ui.value.providerDraft
        val inputError = validateCustomEndpointInput(draft.endpointName, draft.endpointBaseUrl, draft.endpointModel)
        if (inputError != null) {
            _ui.value = _ui.value.copy(
                customEndpointValidation = ProviderValidationUi(false, false, inputError),
            )
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                providerBusy = ProviderBusyAction.VALIDATE_ENDPOINT,
                customEndpointValidation = null,
            )
            try {
                val response = providerApi().validateCustomEndpoint(customEndpointBody(draft))
                val result = parseProviderValidation(response)
                _ui.value = _ui.value.copy(
                    providerBusy = null,
                    customEndpointValidation = ProviderValidationUi(result.ok, result.reachable, validationMessage(result)),
                )
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(
                    providerBusy = null,
                    customEndpointValidation = ProviderValidationUi(
                        false,
                        false,
                        "Endpoint validation failed: ${t.message ?: "request failed"}",
                    ),
                )
            }
        }
    }

    fun saveCustomEndpoint() {
        val draft = _ui.value.providerDraft
        val inputError = validateCustomEndpointInput(draft.endpointName, draft.endpointBaseUrl, draft.endpointModel)
        if (inputError != null) {
            _ui.value = _ui.value.copy(
                customEndpointValidation = ProviderValidationUi(false, false, inputError),
            )
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(providerBusy = ProviderBusyAction.SAVE_ENDPOINT)
            try {
                providerApi().upsertCustomEndpoint(customEndpointBody(draft))
                refreshProviderContent("Custom endpoint saved")
                clearEndpointDraft()
            } catch (t: Throwable) {
                providerFailure(t)
            }
        }
    }

    fun editCustomEndpoint(endpoint: com.hermesgadget.talaria.domain.model.CustomEndpoint) {
        updateProviderDraft {
            it.copy(
                endpointId = endpoint.id,
                endpointName = endpoint.name,
                endpointBaseUrl = endpoint.baseUrl,
                endpointModel = endpoint.model,
                endpointApiKey = "",
                endpointContextLength = endpoint.contextLength?.toString().orEmpty(),
                endpointDiscoverModels = endpoint.discoverModels,
                endpointMakeDefault = endpoint.makeDefault,
            )
        }
        _ui.value = _ui.value.copy(customEndpointValidation = null, providerNotice = "Editing ${endpoint.name}")
    }

    fun activateCustomEndpoint(endpointId: String) {
        if (endpointId.isBlank()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(providerBusy = ProviderBusyAction.ACTIVATE_ENDPOINT)
            try {
                providerApi().activateCustomEndpoint(endpointId)
                refreshProviderContent("Custom endpoint activated")
            } catch (t: Throwable) {
                providerFailure(t)
            }
        }
    }

    fun requestRemoveCustomEndpoint(endpoint: com.hermesgadget.talaria.domain.model.CustomEndpoint) {
        _ui.value = _ui.value.copy(providerConfirmation = ProviderConfirmation.RemoveEndpoint(endpoint))
    }

    fun editCredential(provider: String, entry: CredentialPoolEntry) {
        updateProviderDraft {
            it.copy(
                credentialProvider = provider,
                selectedProvider = provider,
                credentialEnvKey = "",
                credentialLabel = entry.label,
                credentialApiKey = "",
                editingCredential = entry,
            )
        }
        _ui.value = _ui.value.copy(providerNotice = "Enter a new key to replace ${entry.label.ifBlank { "this credential" }}")
    }

    fun clearCredentialDraft() {
        updateProviderDraft {
            it.copy(
                credentialProvider = "",
                credentialEnvKey = "",
                credentialLabel = "",
                credentialApiKey = "",
                editingCredential = null,
            )
        }
    }

    fun requestSaveCredential() {
        val draft = _ui.value.providerDraft
        val provider = draft.credentialProvider.ifBlank { draft.selectedProvider }.trim()
        if (provider.isBlank()) {
            _ui.value = _ui.value.copy(providerNotice = "Choose a provider before saving a credential")
            return
        }
        if (draft.credentialApiKey.isBlank()) {
            _ui.value = _ui.value.copy(providerNotice = "Enter an API key before saving a credential")
            return
        }
        _ui.value = _ui.value.copy(
            providerConfirmation = ProviderConfirmation.SaveCredential(provider, draft.editingCredential),
        )
    }

    fun requestRemoveCredential(provider: String, entry: CredentialPoolEntry) {
        _ui.value = _ui.value.copy(providerConfirmation = ProviderConfirmation.RemoveCredential(provider, entry))
    }

    fun cancelProviderConfirmation() {
        _ui.value = _ui.value.copy(providerConfirmation = null)
    }

    fun confirmProviderAction() {
        val action = _ui.value.providerConfirmation ?: return
        _ui.value = _ui.value.copy(providerConfirmation = null)
        viewModelScope.launch {
            try {
                when (action) {
                    is ProviderConfirmation.SaveCredential -> saveCredentialNow(action.provider, action.replacing)
                    is ProviderConfirmation.RemoveCredential -> {
                        _ui.value = _ui.value.copy(providerBusy = ProviderBusyAction.DELETE_CREDENTIAL)
                        providerApi().deleteCredentialPoolEntry(action.provider, action.entry.index)
                        refreshProviderContent("Credential removed")
                    }
                    is ProviderConfirmation.RemoveEndpoint -> {
                        _ui.value = _ui.value.copy(providerBusy = ProviderBusyAction.DELETE_ENDPOINT)
                        providerApi().deleteCustomEndpoint(action.endpoint.id)
                        refreshProviderContent("Custom endpoint removed")
                    }
                }
            } catch (t: Throwable) {
                providerFailure(t)
            }
        }
    }

    fun startProviderOAuth(providerId: String, openBrowser: (String) -> Unit) {
        if (providerId.isBlank()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                providerBusy = ProviderBusyAction.START_OAUTH,
                providerNotice = null,
            )
            try {
                val response = parseProviderOAuthStart(providerApi().startProviderOAuth(providerId))
                val url = response.verificationUriComplete
                    ?: response.authUrl
                    ?: response.authorizationUrl
                    ?: response.verificationUri
                if (url != null) openBrowser(url)
                _ui.value = _ui.value.copy(
                    providerBusy = null,
                    providerOAuthSession = ProviderOAuthSession(
                        providerId = providerId,
                        sessionId = response.sessionId,
                        authUrl = response.authUrl ?: response.authorizationUrl,
                        verificationUri = response.verificationUri,
                        verificationUriComplete = response.verificationUriComplete,
                        userCode = response.userCode,
                        status = response.status,
                        message = response.message
                            ?: if (url == null) "Hermes did not return an authorization URL" else null,
                    ),
                    providerDraft = _ui.value.providerDraft.copy(
                        oauthProviderId = providerId,
                        oauthCode = "",
                    ),
                )
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(
                    providerBusy = null,
                    providerNotice = "OAuth is unavailable on this Hermes version; enter an API key below. (${t.message ?: "request failed"})",
                )
            }
        }
    }

    fun submitProviderOAuth() {
        val session = _ui.value.providerOAuthSession
        val code = _ui.value.providerDraft.oauthCode.trim()
        val sessionId = session?.sessionId
        if (session == null || sessionId.isNullOrBlank()) {
            _ui.value = _ui.value.copy(providerNotice = "Start the provider OAuth flow before submitting a code")
            return
        }
        if (code.isBlank()) {
            _ui.value = _ui.value.copy(providerNotice = "Paste the authorization code first")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(providerBusy = ProviderBusyAction.SUBMIT_OAUTH, providerNotice = null)
            try {
                val response = parseProviderOAuthPoll(
                    providerApi().submitProviderOAuth(
                        session.providerId,
                        buildJsonObject {
                            put("session_id", sessionId)
                            put("code", code)
                        },
                    ),
                )
                _ui.value = _ui.value.copy(
                    providerBusy = null,
                    providerOAuthSession = session.copy(status = response.status, message = response.message ?: response.detail),
                    providerNotice = response.message ?: response.detail,
                )
                if (oauthCompleted(response.status, response.loggedIn)) refreshProviderContent("Provider OAuth connected")
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(
                    providerBusy = null,
                    providerNotice = "OAuth code submission failed: ${t.message ?: "request failed"}",
                )
            }
        }
    }

    fun pollProviderOAuth() {
        val session = _ui.value.providerOAuthSession
        val sessionId = session?.sessionId
        if (session == null || sessionId.isNullOrBlank()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(providerBusy = ProviderBusyAction.POLL_OAUTH, providerNotice = null)
            try {
                val response = parseProviderOAuthPoll(
                    providerApi().pollProviderOAuth(session.providerId, sessionId),
                )
                _ui.value = _ui.value.copy(
                    providerBusy = null,
                    providerOAuthSession = session.copy(status = response.status, message = response.message ?: response.detail),
                    providerNotice = response.message ?: response.detail,
                )
                if (oauthCompleted(response.status, response.loggedIn)) refreshProviderContent("Provider OAuth connected")
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(
                    providerBusy = null,
                    providerNotice = "OAuth status check failed: ${t.message ?: "request failed"}",
                )
            }
        }
    }

    fun clearProviderOAuthSession() {
        _ui.value = _ui.value.copy(providerOAuthSession = null)
    }

    private fun providerApi() = TalariaApp.instance.container.clientFactory.api()

    private suspend fun saveConnectionDraft(s: ConnectUiState): ConnectionProfile = repo.save(
        name = s.name,
        baseUrl = s.baseUrl,
        authMode = s.authMode,
        username = s.username.ifBlank { null },
        authProvider = if (s.authMode == AuthMode.OIDC_BROWSER) s.oidcProvider else s.passwordProvider,
        sessionToken = s.sessionToken.ifBlank { null },
        password = s.password.ifBlank { null },
        bearerToken = s.bearerToken.ifBlank { null },
        managementProfile = s.managementProfile,
        pinSha256 = s.pinSha256.ifBlank { null },
        existingId = draftProfileId,
        allowCleartext = if (hasConsentForCurrentOrigin(s)) true else null,
        cleartextConsentRecorded = if (hasConsentForCurrentOrigin(s)) true else null,
        cleartextConsentOrigin = s.cleartextConsentOrigin,
    )

    /**
     * Raises an explicit consent prompt when the draft targets a private/LAN
     * destination over plain http and consent has not been recorded for this
     * exact URL. Returns true when the caller must abort the save (prompt shown).
     */
    private fun maybeRequestCleartextConsent(s: ConnectUiState): Boolean {
        if (s.cleartextConsentRequest != null) return true
        val url = s.baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return false
        if (url.isHttps) return false
        if (!CleartextPolicy.isVerifiedDestination(url.host)) return false
        val origin = ConnectionOrigin.normalize(url)
        if (s.cleartextConsentApproved && s.cleartextConsentOrigin == origin) return false
        val existing = draftProfileId?.let { id ->
            TalariaApp.instance.container.connectionStore.snapshotFor(id)?.profile
        }
        val alreadyConsented = existing
            ?.let {
                it.cleartextConsentRecorded == true && it.cleartextConsentOrigin == origin
            }
        if (alreadyConsented == true) return false
        _ui.value = _ui.value.copy(
            cleartextConsentRequest = CleartextConsentRequest(url.host, s.baseUrl, origin),
        )
        return true
    }

    fun confirmCleartextConsent() {
        val origin = ConnectionOrigin.normalize(_ui.value.baseUrl)
        if (origin == null) {
            declineCleartextConsent()
            return
        }
        _ui.value = _ui.value.copy(
            cleartextConsentApproved = true,
            cleartextConsentOrigin = origin,
            cleartextConsentRequest = null,
        )
    }

    fun declineCleartextConsent() {
        _ui.value = _ui.value.copy(
            cleartextConsentApproved = false,
            cleartextConsentOrigin = null,
            cleartextConsentRequest = null,
            error = "Cleartext to this host was not confirmed — use https:// or a different destination",
        )
    }

    private fun hasConsentForCurrentOrigin(s: ConnectUiState): Boolean {
        val origin = ConnectionOrigin.normalize(s.baseUrl) ?: return false
        return s.cleartextConsentApproved && s.cleartextConsentOrigin == origin
    }

    private suspend fun loadProviderData(): ProviderOnboardingContent {
        val api = providerApi()
        val notices = mutableListOf<String>()
        val directCatalog = runCatching { api.getProviders() }
        val catalog = if (directCatalog.isSuccess) {
            val parsed = parseProviderCatalog(directCatalog.getOrThrow())
            if (parsed.providers.isNotEmpty()) {
                parsed
            } else {
                notices += "This Hermes version returned no /api/providers entries; showing /api/model/options."
                parseProviderCatalog(api.getModelOptions())
            }
        } else {
            notices += "This Hermes version does not expose /api/providers; showing /api/model/options."
            runCatching { parseProviderCatalog(api.getModelOptions()) }.getOrElse { fallbackFailure ->
                throw IllegalStateException(
                    "Provider catalog unavailable: ${fallbackFailure.message ?: directCatalog.exceptionOrNull()?.message}",
                    fallbackFailure,
                )
            }
        }

        val endpoints = runCatching { parseCustomEndpoints(api.getCustomEndpoints()) }
            .getOrElse {
                notices += "Custom endpoints are unavailable on this Hermes version."
                CustomEndpointSnapshot()
            }
        val credentials = runCatching { parseCredentialPool(api.getCredentialPool()) }
            .getOrElse {
                notices += "Credential pools are unavailable on this Hermes version."
                CredentialPoolSnapshot()
            }
        val oauth = runCatching { parseOAuthProviders(api.getProviderOAuth()) }
            .getOrElse {
                notices += "Provider OAuth is unavailable; use an API key instead."
                emptyList()
            }
        return ProviderOnboardingContent(
            providers = catalog.providers,
            activeProvider = catalog.activeProvider,
            activeModel = catalog.activeModel,
            customEndpoints = endpoints.endpoints,
            currentEndpointProvider = endpoints.current?.provider,
            currentEndpointModel = endpoints.current?.model,
            currentEndpointBaseUrl = endpoints.current?.baseUrl,
            credentialPools = credentials.providers,
            oauthProviders = oauth,
            notices = notices,
        )
    }

    private suspend fun refreshProviderContent(message: String) {
        val content = loadProviderData()
        _ui.value = _ui.value.copy(
            providerSection = ProviderSectionState.Content(content),
            providerBusy = null,
            providerNotice = message,
            providerValidation = null,
            customEndpointValidation = null,
        )
    }

    private fun providerFailure(failure: Throwable) {
        _ui.value = _ui.value.copy(
            providerSection = ProviderSectionState.Failure(
                failure.message ?: "Provider operation failed",
                providerContent(_ui.value.providerSection),
            ),
            providerBusy = null,
        )
    }

    private fun clearEndpointDraft() {
        updateProviderDraft {
            it.copy(
                endpointId = "",
                endpointName = "",
                endpointBaseUrl = "",
                endpointModel = "",
                endpointApiKey = "",
                endpointContextLength = "",
                endpointDiscoverModels = true,
                endpointMakeDefault = false,
            )
        }
    }

    private fun customEndpointBody(draft: ProviderDraft) = buildJsonObject {
        draft.endpointId.trim().takeIf { it.isNotBlank() }?.let { put("id", it) }
        put("name", draft.endpointName.trim())
        put("base_url", draft.endpointBaseUrl.trim())
        put("model", draft.endpointModel.trim())
        draft.endpointApiKey.trim().takeIf { it.isNotBlank() }?.let { put("api_key", it) }
        draft.endpointContextLength.toIntOrNull()?.takeIf { it > 0 }?.let { put("context_length", it) }
        put("discover_models", draft.endpointDiscoverModels)
        put("make_default", draft.endpointMakeDefault)
    }

    private suspend fun saveCredentialNow(provider: String, replacing: CredentialPoolEntry?) {
        val draft = _ui.value.providerDraft
        _ui.value = _ui.value.copy(providerBusy = ProviderBusyAction.SAVE_CREDENTIAL)
        providerApi().addCredentialPoolEntry(buildJsonObject {
            put("provider", provider)
            put("api_key", draft.credentialApiKey.trim())
            draft.credentialLabel.trim().takeIf { it.isNotBlank() }?.let { put("label", it) }
        })
        // The live API has no edit verb. Add first so a failed replacement leaves
        // the old credential usable, then remove the confirmed old row.
        replacing?.let { providerApi().deleteCredentialPoolEntry(provider, it.index) }
        refreshProviderContent(if (replacing == null) "Credential added" else "Credential replaced")
        clearCredentialDraft()
    }

    private fun providerEnvKey(provider: String): String = provider
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_')
        .uppercase()
        .let { if (it.isBlank()) "PROVIDER_API_KEY" else "${it}_API_KEY" }

    private fun validationMessage(result: com.hermesgadget.talaria.domain.model.ProviderValidationResponse): String =
        result.message.ifBlank {
            when {
                result.ok -> "Provider accepted the credential"
                result.reachable -> "Provider rejected the credential"
                else -> "Provider could not be reached"
            }
        }

    private fun oauthCompleted(status: String?, loggedIn: Boolean?): Boolean =
        loggedIn == true || status?.lowercase() in setOf(
            "complete",
            "completed",
            "success",
            "succeeded",
            "authenticated",
            "logged_in",
        )

    private fun providerContent(state: ProviderSectionState): ProviderOnboardingContent? = when (state) {
        ProviderSectionState.Idle,
        ProviderSectionState.Loading -> null
        is ProviderSectionState.Content -> state.value
        is ProviderSectionState.Failure -> state.previous
    }

    fun saveAndTest(onSuccess: () -> Unit) {
        val s = _ui.value
        viewModelScope.launch {
            if (maybeRequestCleartextConsent(s)) return@launch
            _ui.value = s.copy(testing = true, error = null)
            try {
                val profile = saveConnectionDraft(s)
                draftProfileId = profile.id
                repo.setActive(profile.id)
                val snapshot = TalariaApp.instance.container.clientFactory.snapshotFor(
                    profile.id,
                    profile.managementProfile,
                ) ?: error(SnapshotAuthGuard.CHANGED_MESSAGE)
                val result = repo.testConnection(snapshot)
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

    /** Revoke the saved profile's exact-origin approval under the store lock. */
    fun revokeCleartextConsent(id: String? = draftProfileId) {
        val target = id ?: repo.active()?.id ?: return
        viewModelScope.launch {
            if (!repo.revokeCleartextConsent(target)) return@launch
            if (target == draftProfileId) {
                _ui.value = _ui.value.copy(
                    cleartextConsentApproved = false,
                    cleartextConsentOrigin = null,
                    cleartextConsentRequest = null,
                    statusLine = "HTTP consent revoked · cleartext is blocked until you approve this origin again",
                )
            }
        }
    }

    /** Load public connection fields for editing; blank secret inputs preserve encrypted values. */
    fun edit(profile: ConnectionProfile) {
        draftProfileId = profile.id
        repo.setActive(profile.id)
        val origin = ConnectionOrigin.normalize(profile.baseUrl)
        val approved = profile.cleartextConsentRecorded == true &&
            profile.cleartextConsentOrigin == origin
        _ui.value = ConnectUiState(
            name = profile.name,
            baseUrl = profile.baseUrl,
            authMode = profile.authMode,
            username = profile.username.orEmpty(),
            passwordProvider = profile.authProvider,
            managementProfile = profile.managementProfile,
            pinSha256 = profile.pinSha256.orEmpty(),
            oidcProvider = profile.authProvider,
            cleartextConsentApproved = approved,
            cleartextConsentOrigin = profile.cleartextConsentOrigin.takeIf { approved },
            statusLine = "Editing ${profile.name} · blank secret fields keep their saved values",
        )
    }

    /** Persist the draft profile and enter the app without a live /api/status probe. */
    fun saveAndContinue(onSuccess: () -> Unit) {
        val s = _ui.value
        viewModelScope.launch {
            if (maybeRequestCleartextConsent(s)) return@launch
            _ui.value = s.copy(testing = true, error = null)
            try {
                val profile = saveConnectionDraft(s)
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
            if (maybeRequestCleartextConsent(s)) return@launch
            _ui.value = s.copy(diagnosing = true, doctorReport = null, error = null)
            val lines = mutableListOf<String>()
            try {
                val profile = saveConnectionDraft(s)
                draftProfileId = profile.id
                repo.setActive(profile.id)
                var snapshot = TalariaApp.instance.container.clientFactory.snapshotFor(
                    profile.id,
                    profile.managementProfile,
                ) ?: error(SnapshotAuthGuard.CHANGED_MESSAGE)

                lines += "URL: ${s.baseUrl.trimEnd('/')}"
                if (s.baseUrl.contains("127.0.0.1") || s.baseUrl.contains("localhost")) {
                    lines += "Hint: On emulator use http://10.0.2.2:9119 (host loopback), not 127.0.0.1."
                }

                val status = repo.testConnection(snapshot)
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
                if (status.isSuccess) {
                    snapshot = TalariaApp.instance.container.clientFactory.snapshotFor(
                        profile.id,
                        profile.managementProfile,
                    ) ?: error(SnapshotAuthGuard.CHANGED_MESSAGE)
                }

                val container = TalariaApp.instance.container
                container.wsAuthHelper.invalidate()
                val authParam = container.wsAuthHelper.authQueryParam(snapshot)
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
                val (pty, flow) = container.chatRepository.openPty(
                    snapshot,
                    null,
                    java.util.UUID.randomUUID().toString(),
                )
                var ptyResult = "timeout (3s) — check Host guards / pty extra"
                try {
                    kotlinx.coroutines.withTimeout(3_000) {
                        flow.collect { event ->
                            when (event) {
                                is com.hermesgadget.talaria.core.network.PtyEvent.Connected -> {
                                    ptyResult = "Connected (channel=${event.channel})"
                                    pty.close()
                                    throw kotlinx.coroutines.CancellationException("probe-ok")
                                }
                                is com.hermesgadget.talaria.core.network.PtyEvent.Failure -> {
                                    ptyResult = "FAIL · ${event.message}"
                                    throw kotlinx.coroutines.CancellationException("probe-fail")
                                }
                                is com.hermesgadget.talaria.core.network.PtyEvent.Closed -> {
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
            if (maybeRequestCleartextConsent(s)) return@launch
            _ui.value = s.copy(oidcSigningIn = true, error = null, statusLine = null)
            try {
                val profile = saveConnectionDraft(s.copy(authMode = AuthMode.OIDC_BROWSER))
                draftProfileId = profile.id
                repo.setActive(profile.id)
                val snapshot = TalariaApp.instance.container.clientFactory.snapshotFor(
                    profile.id,
                    profile.managementProfile,
                ) ?: error(SnapshotAuthGuard.OIDC_CHANGED_MESSAGE)

                val providers = nativeOidc.providers(snapshot)
                _ui.value = _ui.value.copy(oidcProviders = providers)
                val provider = s.oidcProvider.takeIf { selected -> providers.any { it.name == selected } }
                    ?: providers.singleOrNull()?.name
                    ?: if (providers.isEmpty()) {
                        error("Hermes did not advertise an OAuth provider")
                    } else {
                        error("Choose an OAuth provider, then tap Sign in again")
                    }
                nativeOidc.signIn(snapshot, provider, openBrowser)
                val completingSnapshot = TalariaApp.instance.container.clientFactory.snapshotFor(
                    profile.id,
                    profile.managementProfile,
                ) ?: error(SnapshotAuthGuard.OIDC_CHANGED_MESSAGE)
                val status = repo.testConnection(completingSnapshot).getOrThrow()
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
