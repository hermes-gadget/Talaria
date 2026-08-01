/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.nousresearch.talaria.network

import com.nousresearch.talaria.domain.model.ConnectionProfile
import com.nousresearch.talaria.domain.model.effectiveManagementProfile
import com.nousresearch.talaria.domain.model.scopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionProfileScopeTest {
    @Test
    fun `blank management selection explicitly targets Hermes default`() {
        val profile = ConnectionProfile(id = "one", name = "Home", baseUrl = "https://example.test")

        assertEquals("default", profile.effectiveManagementProfile())
        assertEquals("one", profile.scopeId())
    }

    @Test
    fun `named homes have isolated storage-safe scope ids`() {
        val first = ConnectionProfile(
            id = "one",
            name = "Home",
            baseUrl = "https://example.test",
            managementProfile = "work/profile",
        )
        val second = first.copy(managementProfile = "personal")

        assertNotEquals(first.scopeId(), second.scopeId())
        assertFalse(first.scopeId().contains('/'))
    }
}
