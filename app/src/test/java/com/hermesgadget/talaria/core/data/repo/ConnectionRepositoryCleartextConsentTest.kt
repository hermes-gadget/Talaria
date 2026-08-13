/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.core.data.repo

import com.hermesgadget.talaria.core.data.db.TalariaDatabase
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.data.prefs.SettingsStore
import com.hermesgadget.talaria.core.network.ConnectionOrigin
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.HermesClientFactory
import com.hermesgadget.talaria.core.network.WsAuthHelper
import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v0.8.4 fail-closed cleartext migration wipes consent for every
 * pre-existing profile. This is deliberate (audit A-03), but the recovery
 * half — re-approving the exact origin without re-entering the whole
 * connection — must work. These tests pin that recovery path.
 */
class ConnectionRepositoryCleartextConsentTest {

    private fun migratedProfile(baseUrl: String) = ConnectionProfile(
        id = "migrated",
        name = "LAN",
        baseUrl = baseUrl,
        authMode = AuthMode.SESSION_TOKEN,
        allowCleartext = false,
        cleartextConsentRecorded = false,
        cleartextConsentOrigin = null,
    )

    private fun repo(store: SecureConnectionStore = mockk()): ConnectionRepository {
        every { store.profiles } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        every { store.activeId } returns kotlinx.coroutines.flow.MutableStateFlow(null)
        every { store.state } returns kotlinx.coroutines.flow.MutableStateFlow(
            com.hermesgadget.talaria.core.data.prefs.SecureConnectionStoreState.Available(0),
        )
        val clientFactory = mockk<HermesClientFactory>(relaxed = true)
        val wsAuth = mockk<WsAuthHelper>(relaxed = true)
        val database = mockk<TalariaDatabase>(relaxed = true)
        val settings = mockk<SettingsStore>(relaxed = true)
        val hermesRepository = mockk<HermesRepository>(relaxed = true)
        return ConnectionRepository(store, clientFactory, wsAuth, database, settings, hermesRepository)
    }

    @Test
    fun recordCleartextConsentReApprovesTheExactOrigin() = runTest {
        val url = "http://192.168.2.5:9119"
        val profile = migratedProfile(url)
        val store = mockk<SecureConnectionStore>()
        every { store.snapshotFor("migrated") } returns ConnectionSnapshot(profile, ConnectionSecrets(sessionToken = "s"))
        every { store.upsert(any(), any()) } just runs
        val repository = repo(store)

        val recorded = repository.recordCleartextConsent("migrated")

        assertTrue(recorded)
        coVerify(exactly = 1) {
            store.upsert(
                match {
                    it.allowCleartext &&
                        it.cleartextConsentRecorded == true &&
                        it.cleartextConsentOrigin == ConnectionOrigin.normalize(url)
                },
                any(),
            )
        }
    }

    @Test
    fun recordCleartextConsentRejectsHttpsProfiles() = runTest {
        val profile = migratedProfile("https://192.168.2.5:9443")
        val store = mockk<SecureConnectionStore>()
        every { store.snapshotFor("migrated") } returns ConnectionSnapshot(profile, ConnectionSecrets())
        val repository = repo(store)

        val recorded = repository.recordCleartextConsent("migrated")

        assertFalse(recorded)
        coVerify(exactly = 0) { store.upsert(any(), any()) }
    }

    @Test
    fun recordCleartextConsentRejectsPublicHosts() = runTest {
        val profile = migratedProfile("http://example.com:9119")
        val store = mockk<SecureConnectionStore>()
        every { store.snapshotFor("migrated") } returns ConnectionSnapshot(profile, ConnectionSecrets())
        val repository = repo(store)

        val recorded = repository.recordCleartextConsent("migrated")

        assertFalse(recorded)
        coVerify(exactly = 0) { store.upsert(any(), any()) }
    }

    @Test
    fun recordCleartextConsentRejectsMissingProfiles() = runTest {
        val store = mockk<SecureConnectionStore>()
        every { store.snapshotFor("gone") } returns null
        val repository = repo(store)

        val recorded = repository.recordCleartextConsent("gone")

        assertFalse(recorded)
    }

    @Test
    fun recordCleartextConsentInvalidatesTransportClients() = runTest {
        val url = "http://10.0.0.5:9119"
        val profile = migratedProfile(url)
        val store = mockk<SecureConnectionStore>()
        every { store.snapshotFor("migrated") } returns ConnectionSnapshot(profile, ConnectionSecrets())
        every { store.upsert(any(), any()) } just runs
        val clientFactory = mockk<HermesClientFactory>(relaxed = true)
        val wsAuth = mockk<WsAuthHelper>(relaxed = true)
        every { store.profiles } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        every { store.activeId } returns kotlinx.coroutines.flow.MutableStateFlow(null)
        every { store.state } returns kotlinx.coroutines.flow.MutableStateFlow(
            com.hermesgadget.talaria.core.data.prefs.SecureConnectionStoreState.Available(0),
        )
        val repository = ConnectionRepository(
            store,
            clientFactory,
            wsAuth,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )

        repository.recordCleartextConsent("migrated")

        coVerify(exactly = 1) { clientFactory.invalidate() }
        coVerify(exactly = 1) { wsAuth.invalidate() }
    }
}
