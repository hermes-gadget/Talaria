/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.connection

import com.hermesgadget.talaria.domain.model.CredentialPoolEntry
import com.hermesgadget.talaria.domain.model.CredentialPoolProvider
import com.hermesgadget.talaria.domain.model.CustomEndpoint
import com.hermesgadget.talaria.domain.model.ProviderOAuth
import com.hermesgadget.talaria.domain.model.ProviderSummary

data class ProviderOnboardingContent(
    val providers: List<ProviderSummary> = emptyList(),
    val activeProvider: String? = null,
    val activeModel: String? = null,
    val customEndpoints: List<CustomEndpoint> = emptyList(),
    val currentEndpointProvider: String? = null,
    val currentEndpointModel: String? = null,
    val currentEndpointBaseUrl: String? = null,
    val credentialPools: List<CredentialPoolProvider> = emptyList(),
    val oauthProviders: List<ProviderOAuth> = emptyList(),
    val notices: List<String> = emptyList(),
)

sealed interface ProviderSectionState {
    data object Idle : ProviderSectionState
    data object Loading : ProviderSectionState
    data class Content(val value: ProviderOnboardingContent) : ProviderSectionState
    data class Failure(
        val message: String,
        val previous: ProviderOnboardingContent? = null,
    ) : ProviderSectionState
}

data class ProviderDraft(
    val selectedProvider: String = "",
    val endpointId: String = "",
    val endpointName: String = "",
    val endpointBaseUrl: String = "",
    val endpointModel: String = "",
    val endpointApiKey: String = "",
    val endpointContextLength: String = "",
    val endpointDiscoverModels: Boolean = true,
    val endpointMakeDefault: Boolean = false,
    val credentialProvider: String = "",
    val credentialEnvKey: String = "",
    val credentialLabel: String = "",
    val credentialApiKey: String = "",
    val editingCredential: CredentialPoolEntry? = null,
    val oauthProviderId: String = "",
    val oauthCode: String = "",
)

enum class ProviderBusyAction {
    LOAD,
    VALIDATE_PROVIDER,
    VALIDATE_ENDPOINT,
    SAVE_ENDPOINT,
    ACTIVATE_ENDPOINT,
    DELETE_ENDPOINT,
    SAVE_CREDENTIAL,
    DELETE_CREDENTIAL,
    START_OAUTH,
    SUBMIT_OAUTH,
    POLL_OAUTH,
}

sealed interface ProviderConfirmation {
    data class SaveCredential(
        val provider: String,
        val replacing: CredentialPoolEntry? = null,
    ) : ProviderConfirmation

    data class RemoveCredential(
        val provider: String,
        val entry: CredentialPoolEntry,
    ) : ProviderConfirmation

    data class RemoveEndpoint(val endpoint: CustomEndpoint) : ProviderConfirmation
}

data class ProviderValidationUi(
    val ok: Boolean,
    val reachable: Boolean,
    val message: String,
)

data class ProviderOAuthSession(
    val providerId: String,
    val sessionId: String? = null,
    val authUrl: String? = null,
    val verificationUri: String? = null,
    val verificationUriComplete: String? = null,
    val userCode: String? = null,
    val status: String? = null,
    val message: String? = null,
)
