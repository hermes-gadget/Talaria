/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A provider returned by the provider catalog or model-options fallback. */
@Serializable
data class ProviderSummary(
    val id: String = "",
    val slug: String = "",
    val name: String = "",
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("is_current") val isCurrent: Boolean = false,
    val active: Boolean = false,
    val authenticated: Boolean = false,
    @SerialName("auth_type") val authType: String? = null,
    val models: List<String> = emptyList(),
    @SerialName("total_models") val totalModels: Int = 0,
    val source: String? = null,
    val warning: String? = null,
)

/** Flexible envelope used by provider catalogs on different Hermes versions. */
@Serializable
data class ProviderListResponse(
    val providers: List<ProviderSummary> = emptyList(),
    @SerialName("active_provider") val activeProvider: String? = null,
    @SerialName("current_provider") val currentProvider: String? = null,
    val provider: String? = null,
    val model: String? = null,
)

@Serializable
data class CustomEndpoint(
    val id: String = "",
    val name: String = "",
    @SerialName("base_url") val baseUrl: String = "",
    val model: String = "",
    @SerialName("api_key") val apiKey: String? = null,
    @SerialName("context_length") val contextLength: Int? = null,
    @SerialName("discover_models") val discoverModels: Boolean = true,
    @SerialName("make_default") val makeDefault: Boolean = false,
    val models: List<String>? = null,
    @SerialName("is_current") val isCurrent: Boolean = false,
)

@Serializable
data class CustomEndpointCurrent(
    val provider: String? = null,
    val model: String? = null,
    @SerialName("base_url") val baseUrl: String? = null,
)

@Serializable
data class CustomEndpointsResponse(
    val endpoints: List<CustomEndpoint> = emptyList(),
    val current: CustomEndpointCurrent? = null,
)

@Serializable
data class CredentialPoolEntry(
    val index: Int = 0,
    val id: String = "",
    val label: String = "",
    @SerialName("auth_type") val authType: String = "",
    val source: String = "",
    val priority: Int = 0,
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("request_count") val requestCount: Int = 0,
    @SerialName("token_preview") val tokenPreview: String? = null,
    @SerialName("has_refresh") val hasRefresh: Boolean = false,
)

@Serializable
data class CredentialPoolProvider(
    val provider: String = "",
    val entries: List<CredentialPoolEntry> = emptyList(),
)

@Serializable
data class CredentialPoolResponse(
    val providers: List<CredentialPoolProvider> = emptyList(),
)

@Serializable
data class ProviderOAuthStatus(
    @SerialName("logged_in") val loggedIn: Boolean = false,
    val source: String? = null,
    @SerialName("source_label") val sourceLabel: String? = null,
    @SerialName("token_preview") val tokenPreview: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("has_refresh_token") val hasRefreshToken: Boolean = false,
)

@Serializable
data class ProviderOAuth(
    val id: String = "",
    val name: String = "",
    val flow: String = "",
    @SerialName("cli_command") val cliCommand: String? = null,
    @SerialName("docs_url") val docsUrl: String? = null,
    @SerialName("disconnect_hint") val disconnectHint: String? = null,
    @SerialName("disconnect_command") val disconnectCommand: String? = null,
    val disconnectable: Boolean = false,
    val status: ProviderOAuthStatus? = null,
)

@Serializable
data class ProviderOAuthStartResponse(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("auth_url") val authUrl: String? = null,
    @SerialName("authorization_url") val authorizationUrl: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    val status: String? = null,
    val message: String? = null,
)

@Serializable
data class ProviderOAuthPollResponse(
    val status: String? = null,
    val message: String? = null,
    val detail: String? = null,
    @SerialName("logged_in") val loggedIn: Boolean? = null,
)

@Serializable
data class ProviderValidationResponse(
    val ok: Boolean = false,
    val reachable: Boolean = false,
    val message: String = "",
)
