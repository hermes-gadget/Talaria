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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderParsingTest {
    @Test
    fun providerListParsingFindsCurrentProviderAndModels() {
        val root = JsonConfig.json.parseToJsonElement(
            """
            {
              "providers": [
                {"slug":"anthropic","name":"Anthropic","models":["claude-sonnet"],"authenticated":true},
                {"slug":"opencode-go","name":"OpenCode Go","is_current":true,"models":["deepseek"]}
              ],
              "provider":"opencode-go",
              "model":"deepseek"
            }
            """.trimIndent(),
        )

        val parsed = parseProviderCatalog(root)

        assertEquals("opencode-go", parsed.activeProvider)
        assertEquals("deepseek", parsed.activeModel)
        assertEquals(listOf("claude-sonnet"), parsed.providers.first().models)
        assertTrue(parsed.providers.last().isCurrent)
    }

    @Test
    fun providerListParsingAcceptsArrayAndUsesSlugAsDisplayFallback() {
        val root = JsonConfig.json.parseToJsonElement(
            """[{"id":"custom","slug":"custom","display_name":"Custom"}]""",
        )

        val parsed = parseProviderCatalog(root)

        assertEquals("custom", parsed.providers.single().id)
        assertEquals("Custom", parsed.providers.single().name)
    }

    @Test
    fun customEndpointValidationAcceptsHttpsBaseUrl() {
        assertNull(validateCustomEndpointInput("Local", "https://api.example.com/v1", "model-a"))
    }

    @Test
    fun customEndpointValidationRejectsMissingFieldsAndEmbeddedCredentials() {
        assertEquals("Endpoint name is required", validateCustomEndpointInput(" ", "https://api.example.com", "model"))
        assertEquals("Model is required", validateCustomEndpointInput("Local", "https://api.example.com", " "))
        assertEquals(
            "Base URL must be a valid http:// or https:// URL",
            validateCustomEndpointInput("Local", "ftp://api.example.com", "model"),
        )
        assertEquals(
            "Put endpoint credentials in the API key field",
            validateCustomEndpointInput("Local", "https://user:secret@api.example.com", "model"),
        )
        assertFalse(validateCustomEndpointInput("Local", "https://api.example.com?x=1", "model").isNullOrBlank())
        assertTrue(validateCustomEndpointInput("Local", "https://api.example.com", "model") == null)
    }
}
