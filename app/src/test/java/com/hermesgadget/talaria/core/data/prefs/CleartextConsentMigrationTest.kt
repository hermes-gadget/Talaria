/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.core.data.prefs

import com.hermesgadget.talaria.core.network.ConnectionOrigin
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class CleartextConsentMigrationTest {
    @Test
    fun legacyMissingOrBareApprovalBecomesUndecided() {
        val legacy = ConnectionProfile(
            id = "legacy",
            name = "Legacy",
            baseUrl = "http://192.168.1.5:9119",
            allowCleartext = true,
            cleartextConsentRecorded = null,
            cleartextConsentOrigin = null,
        )
        val legacyImplicitlyApproved = legacy.copy(cleartextConsentRecorded = true)
        val migrated = CleartextConsentMigration.migrate(
            listOf(legacy, legacyImplicitlyApproved),
            0,
        )
        migrated.forEach {
            assertFalse(it.allowCleartext)
            assertFalse(it.cleartextConsentRecorded == true)
            assertNull(it.cleartextConsentOrigin)
        }
    }

    @Test
    fun currentVersionKeepsOnlyAnExactOriginApproval() {
        val url = "http://192.168.1.5:9119".toHttpUrl()
        val approved = ConnectionProfile(
            id = "approved",
            name = "Approved",
            baseUrl = url.toString(),
            allowCleartext = false,
            cleartextConsentRecorded = true,
            cleartextConsentOrigin = ConnectionOrigin.normalize(url),
        )
        val normalized = CleartextConsentMigration.migrate(
            listOf(approved),
            CleartextConsentMigration.CURRENT_VERSION,
        ).single()
        assertTrue(normalized.allowCleartext)
        assertEquals(true, normalized.cleartextConsentRecorded)
        assertEquals(ConnectionOrigin.normalize(url), normalized.cleartextConsentOrigin)
    }

    @Test
    fun newProfileDefaultsToUndecided() {
        val profile = ConnectionProfile(
            id = "new",
            name = "New",
            baseUrl = "http://10.0.0.5:9119",
        )
        assertFalse(profile.allowCleartext)
        assertFalse(profile.cleartextConsentRecorded == true)
        assertNull(profile.cleartextConsentOrigin)
    }
}
