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

import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.domain.model.CredentialPoolEntry
import com.hermesgadget.talaria.domain.model.CredentialPoolProvider
import com.hermesgadget.talaria.domain.model.CustomEndpoint
import com.hermesgadget.talaria.domain.model.CustomEndpointCurrent
import com.hermesgadget.talaria.domain.model.ProviderOAuth
import com.hermesgadget.talaria.domain.model.ProviderOAuthPollResponse
import com.hermesgadget.talaria.domain.model.ProviderOAuthStartResponse
import com.hermesgadget.talaria.domain.model.ProviderSummary
import com.hermesgadget.talaria.domain.model.ProviderValidationResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import com.hermesgadget.talaria.core.util.suspendResult

data class ProviderCatalogSnapshot(
    val providers: List<ProviderSummary> = emptyList(),
    val activeProvider: String? = null,
    val activeModel: String? = null,
)

data class CustomEndpointSnapshot(
    val endpoints: List<CustomEndpoint> = emptyList(),
    val current: CustomEndpointCurrent? = null,
)

data class CredentialPoolSnapshot(
    val providers: List<CredentialPoolProvider> = emptyList(),
)

/** Parse both the provider catalog contract and the model-options fallback. */
fun parseProviderCatalog(root: JsonElement): ProviderCatalogSnapshot {
    val objectRoot = root as? JsonObject
    val providerElements = when (root) {
        is JsonArray -> root
        else -> objectRoot?.arrayFor("providers", "items", "results")
            ?: (objectRoot?.get("data") as? JsonObject)?.arrayFor("providers", "items")
            ?: emptyList()
    }
    val activeProvider = objectRoot?.stringFor(
        "active_provider",
        "current_provider",
        "provider",
    )?.takeIf { it.isNotBlank() }
    val activeModel = objectRoot?.stringFor("active_model", "model")?.takeIf { it.isNotBlank() }
    val parsed = providerElements.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                ProviderSummary(id = it, slug = it, name = it)
            }
            else -> runCatching { JsonConfig.json.decodeFromJsonElement<ProviderSummary>(element) }
                .getOrNull()
                ?.let { provider ->
                    val key = providerKey(provider)
                    provider.copy(
                        id = provider.id.ifBlank { key },
                        slug = provider.slug.ifBlank { key },
                        name = provider.name.ifBlank {
                            provider.displayName?.takeIf(String::isNotBlank) ?: key
                        },
                    )
                }
        }
    }
    val providers = parsed.map { provider ->
        val key = providerKey(provider)
        val current = provider.isCurrent || provider.active ||
            (activeProvider != null && key.equals(activeProvider, ignoreCase = true))
        provider.copy(isCurrent = current, active = current)
    }
    val resolvedActive = activeProvider
        ?: providers.firstOrNull { it.isCurrent }?.let(::providerKey)
    return ProviderCatalogSnapshot(providers, resolvedActive, activeModel)
}

fun parseCustomEndpoints(root: JsonElement): CustomEndpointSnapshot {
    val objectRoot = root as? JsonObject
    val endpointElements = when (root) {
        is JsonArray -> root
        else -> objectRoot?.arrayFor("endpoints", "custom_endpoints", "items", "results") ?: emptyList()
    }
    val endpoints = endpointElements.mapNotNull { element ->
        runCatching { JsonConfig.json.decodeFromJsonElement<CustomEndpoint>(element) }.getOrNull()
    }
    val current = (objectRoot?.get("current") as? JsonObject)?.let { value ->
        runCatching { JsonConfig.json.decodeFromJsonElement<CustomEndpointCurrent>(value) }.getOrNull()
            ?: CustomEndpointCurrent(
                provider = value.stringFor("provider"),
                model = value.stringFor("model"),
                baseUrl = value.stringFor("base_url", "baseUrl"),
            )
    }
    return CustomEndpointSnapshot(endpoints, current)
}

fun parseCredentialPool(root: JsonElement): CredentialPoolSnapshot {
    val objectRoot = root as? JsonObject
    val providerArray = objectRoot?.arrayFor("providers", "items", "results")
    val providers = when {
        root is JsonArray -> root.mapNotNull(::decodeCredentialProvider)
        providerArray != null -> providerArray.mapNotNull(::decodeCredentialProvider)
        else -> objectRoot.orEmpty()
            .mapNotNull { (provider, value) ->
                (value as? JsonArray)?.let { entries ->
                    CredentialPoolProvider(
                        provider = provider,
                        entries = entries.mapNotNull(::decodeCredentialEntry),
                    )
                }
            }
    }
    return CredentialPoolSnapshot(providers.filter { it.provider.isNotBlank() || it.entries.isNotEmpty() })
}

fun parseOAuthProviders(root: JsonElement): List<ProviderOAuth> {
    val objectRoot = root as? JsonObject
    val elements = when (root) {
        is JsonArray -> root
        else -> objectRoot?.arrayFor("providers", "oauth", "items", "results") ?: emptyList()
    }
    return elements.mapNotNull { element ->
        runCatching { JsonConfig.json.decodeFromJsonElement<ProviderOAuth>(element) }.getOrNull()
            ?.takeIf { it.id.isNotBlank() || it.name.isNotBlank() }
    }
}

fun parseProviderOAuthStart(root: JsonElement): ProviderOAuthStartResponse =
    runCatching { JsonConfig.json.decodeFromJsonElement<ProviderOAuthStartResponse>(root) }
        .getOrElse {
            val obj = root as? JsonObject ?: return ProviderOAuthStartResponse(message = root.toString())
            ProviderOAuthStartResponse(
                sessionId = obj.stringFor("session_id", "sessionId"),
                authUrl = obj.stringFor("auth_url", "authUrl"),
                authorizationUrl = obj.stringFor("authorization_url", "authorizationUrl"),
                verificationUri = obj.stringFor("verification_uri", "verificationUri"),
                verificationUriComplete = obj.stringFor("verification_uri_complete", "verificationUriComplete"),
                userCode = obj.stringFor("user_code", "userCode"),
                status = obj.stringFor("status"),
                message = obj.stringFor("message", "detail", "error"),
            )
        }

fun parseProviderOAuthPoll(root: JsonElement): ProviderOAuthPollResponse =
    runCatching { JsonConfig.json.decodeFromJsonElement<ProviderOAuthPollResponse>(root) }
        .getOrElse {
            val obj = root as? JsonObject ?: return ProviderOAuthPollResponse(message = root.toString())
            ProviderOAuthPollResponse(
                status = obj.stringFor("status"),
                message = obj.stringFor("message", "detail", "error"),
                detail = obj.stringFor("detail"),
                loggedIn = obj.booleanFor("logged_in", "loggedIn"),
            )
        }

fun parseProviderValidation(root: JsonElement): ProviderValidationResponse {
    val decoded = runCatching {
        JsonConfig.json.decodeFromJsonElement<ProviderValidationResponse>(root)
    }.getOrNull()
    if (decoded != null && decoded.message.isNotBlank()) return decoded
    val obj = root as? JsonObject
    return ProviderValidationResponse(
        ok = obj?.booleanFor("ok", "valid", "success") ?: false,
        reachable = obj?.booleanFor("reachable", "network_reachable") ?: false,
        message = obj?.stringFor("message", "detail", "error")
            ?: root.toString().takeUnless { it == JsonNull.toString() }.orEmpty(),
    )
}

/** Return a user-facing validation error, or null when an endpoint draft is valid. */
fun validateCustomEndpointInput(name: String, baseUrl: String, model: String): String? {
    if (name.trim().isEmpty()) return "Endpoint name is required"
    if (model.trim().isEmpty()) return "Model is required"
    val value = baseUrl.trim()
    if (value.isEmpty()) return "Base URL is required"
    val uri = runCatching { java.net.URI(value) }.getOrNull()
        ?: return "Base URL must be a valid http:// or https:// URL"
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
        return "Base URL must be a valid http:// or https:// URL"
    }
    if (uri.userInfo != null) return "Put endpoint credentials in the API key field"
    if (uri.query != null || uri.fragment != null) return "Base URL cannot contain a query or fragment"
    return null
}

fun providerKey(provider: ProviderSummary): String =
    provider.slug.ifBlank { provider.id }.ifBlank { provider.name }

private fun decodeCredentialProvider(element: JsonElement): CredentialPoolProvider? =
    runCatching { JsonConfig.json.decodeFromJsonElement<CredentialPoolProvider>(element) }.getOrNull()
        ?.let { provider ->
            provider.copy(entries = provider.entries.filter { it.index > 0 || it.id.isNotBlank() })
        }

private fun decodeCredentialEntry(element: JsonElement): CredentialPoolEntry? =
    runCatching { JsonConfig.json.decodeFromJsonElement<CredentialPoolEntry>(element) }.getOrNull()

private fun JsonObject?.orEmpty(): Set<Map.Entry<String, JsonElement>> = this?.entries.orEmpty()

private fun JsonObject.arrayFor(vararg keys: String): JsonArray? = keys.firstNotNullOfOrNull { key ->
    this[key] as? JsonArray
}

private fun JsonObject.stringFor(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

private fun JsonObject.booleanFor(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.booleanOrNull
}
