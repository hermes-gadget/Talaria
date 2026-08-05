/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.core.data.prefs

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.security.InvalidKeyException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureConnectionStoreRecoveryTest {
    @Test
    fun healthyReleasedFormatSurvivesUnchanged() {
        val data = releasedFixture()
        val storage = FakeStorage { preferences(data) }

        val store = SecureConnectionStore(storage)

        assertEquals(SecureConnectionStoreState.Available(1), store.state.value)
        assertEquals("released", store.activeProfile()?.id)
        assertEquals("fixture-session-secret", store.activeSnapshot()?.secrets?.sessionToken)
        assertEquals(RELEASED_PROFILES, data[SecureConnectionStore.KEY_PROFILES])
        assertEquals(RELEASED_SECRETS, data[SecureConnectionStore.secretKeyForTest("released")])
        assertEquals(0, storage.resetCount)
    }

    @Test
    fun corruptProfileJsonIsTypedAndRawStateIsPreservedAcrossRestart() {
        val data = releasedFixture().apply { put(SecureConnectionStore.KEY_PROFILES, "{not-json") }
        val storage = FakeStorage { preferences(data) }

        val first = SecureConnectionStore(storage)
        val restarted = SecureConnectionStore(storage)

        assertTrue(first.state.value is SecureConnectionStoreState.RecoverableCorruption)
        assertTrue(restarted.state.value is SecureConnectionStoreState.RecoverableCorruption)
        assertEquals("{not-json", data[SecureConnectionStore.KEY_PROFILES])
        assertEquals(RELEASED_SECRETS, data[SecureConnectionStore.secretKeyForTest("released")])
        assertEquals(0, storage.resetCount)
    }

    @Test
    fun corruptSecretNeverSubstitutesEmptyCredentials() {
        val data = releasedFixture().apply {
            put(SecureConnectionStore.secretKeyForTest("released"), "{broken-secret")
        }
        val store = SecureConnectionStore(FakeStorage { preferences(data) })

        assertTrue(store.state.value is SecureConnectionStoreState.RecoverableCorruption)
        assertNull(store.snapshotFor("released"))
        assertNull(store.secretsFor("released"))
        assertEquals("{broken-secret", data[SecureConnectionStore.secretKeyForTest("released")])
    }

    @Test
    fun secretCiphertextAuthenticationFailurePreservesEncryptedState() {
        val data = releasedFixture()
        val storage = FakeStorage {
            val prefs = preferences(data)
            every { prefs.all } throws AEADBadTagException("ciphertext fixture")
            prefs
        }

        val store = SecureConnectionStore(storage)

        assertTrue(store.state.value is SecureConnectionStoreState.RecoverableCorruption)
        assertNull(store.activeSnapshot())
        assertEquals(RELEASED_PROFILES, data[SecureConnectionStore.KEY_PROFILES])
        assertEquals(RELEASED_SECRETS, data[SecureConnectionStore.secretKeyForTest("released")])
        assertEquals(0, storage.resetCount)
    }

    @Test
    fun keystoreInvalidationDoesNotEscapeConstructionAndIsPermanent() {
        val storage = FakeStorage {
            throw SecureStoreAccessException(
                SecureStoreFailureKind.PERMANENT,
                InvalidKeyException("fixture-session-secret must never enter diagnostics"),
            )
        }

        val store = SecureConnectionStore(storage)
        val state = store.state.value as SecureConnectionStoreState.PermanentKeystoreLoss

        assertEquals("KEYSTORE_LOST", state.diagnostics.code)
        assertFalse(state.diagnostics.copyText().contains("fixture-session-secret"))
        assertNull(store.activeSnapshot())
        assertEquals(0, storage.resetCount)
    }

    @Test
    fun retryReopensWithoutClearingRawState() {
        val data = releasedFixture()
        var attempts = 0
        val storage = FakeStorage {
            attempts += 1
            if (attempts == 1) throw IllegalStateException("temporarily locked")
            preferences(data)
        }
        val store = SecureConnectionStore(storage)

        assertTrue(store.state.value is SecureConnectionStoreState.RecoverableCorruption)
        assertEquals(SecureConnectionStoreState.Available(1), store.retry())
        assertEquals(RELEASED_PROFILES, data[SecureConnectionStore.KEY_PROFILES])
        assertEquals(0, storage.resetCount)
    }

    @Test
    fun confirmedResetIsScopedToConnectionStorage() {
        val storage = ResetJournalStorage(releasedFixture())
        val store = SecureConnectionStore(storage)

        assertEquals(SecureConnectionStoreState.Available(0), store.confirmedReset())
        assertTrue(storage.connectionData.isEmpty())
        assertEquals("keep-car-host-trust", storage.unrelatedEncryptedData)
        assertEquals(1, storage.resetCount)
    }

    @Test
    fun interruptedConfirmedResetCompletesOnRestart() {
        val storage = ResetJournalStorage(releasedFixture(), interruptFirstReset = true)
        val first = SecureConnectionStore(storage)

        val failed = first.confirmedReset()
        assertTrue(failed is SecureConnectionStoreState.RecoverableCorruption)
        assertEquals("RESET_INTERRUPTED", (failed as SecureConnectionStoreState.RecoverableCorruption).diagnostics.code)

        val restarted = SecureConnectionStore(storage)
        assertEquals(SecureConnectionStoreState.Available(0), restarted.state.value)
        assertTrue(storage.connectionData.isEmpty())
        assertEquals("keep-car-host-trust", storage.unrelatedEncryptedData)
    }

    private class FakeStorage(private val opener: () -> SharedPreferences) : SecureConnectionStorage {
        var resetCount = 0
        override fun open(): SharedPreferences = opener()
        override fun confirmedReset(): Boolean {
            resetCount += 1
            return true
        }
    }

    private class ResetJournalStorage(
        val connectionData: MutableMap<String, Any?>,
        private var interruptFirstReset: Boolean = false,
    ) : SecureConnectionStorage {
        var resetCount = 0
        var unrelatedEncryptedData = "keep-car-host-trust"
        private var confirmedResetInProgress = false

        override fun open(): SharedPreferences {
            if (confirmedResetInProgress) {
                connectionData.clear()
                confirmedResetInProgress = false
            }
            return preferences(connectionData)
        }

        override fun confirmedReset(): Boolean {
            resetCount += 1
            confirmedResetInProgress = true
            if (interruptFirstReset) {
                interruptFirstReset = false
                return false
            }
            connectionData.clear()
            confirmedResetInProgress = false
            return true
        }
    }

    companion object {
        private const val RELEASED_PROFILES =
            "[{\"id\":\"released\",\"name\":\"Home\",\"baseUrl\":\"https://hermes.example\",\"authMode\":\"SESSION_TOKEN\",\"hasSessionToken\":true,\"createdAt\":123}]"
        private const val RELEASED_SECRETS = "{\"sessionToken\":\"fixture-session-secret\"}"

        private fun releasedFixture(): MutableMap<String, Any?> = mutableMapOf(
            SecureConnectionStore.KEY_PROFILES to RELEASED_PROFILES,
            SecureConnectionStore.KEY_ACTIVE to "released",
            SecureConnectionStore.KEY_CLEARTEXT_CONSENT_VERSION to CleartextConsentMigration.CURRENT_VERSION,
            SecureConnectionStore.secretKeyForTest("released") to RELEASED_SECRETS,
        )

        private fun preferences(data: MutableMap<String, Any?>): SharedPreferences {
            val prefs = mockk<SharedPreferences>()
            val editor = mockk<SharedPreferences.Editor>()
            val pending = mutableListOf<() -> Unit>()
            every { prefs.all } answers { data.toMap() }
            every { prefs.getString(any(), any()) } answers {
                data[firstArg<String>()] as? String ?: secondArg()
            }
            every { prefs.getInt(any(), any()) } answers {
                data[firstArg<String>()] as? Int ?: secondArg()
            }
            every { prefs.edit() } answers {
                pending.clear()
                editor
            }
            every { editor.putString(any(), any()) } answers {
                val key = firstArg<String>()
                val value = secondArg<String?>()
                pending += { data[key] = value }
                editor
            }
            every { editor.putInt(any(), any()) } answers {
                val key = firstArg<String>()
                val value = secondArg<Int>()
                pending += { data[key] = value }
                editor
            }
            every { editor.remove(any()) } answers {
                val key = firstArg<String>()
                pending += { data.remove(key) }
                editor
            }
            every { editor.commit() } answers {
                pending.forEach { it() }
                pending.clear()
                true
            }
            return prefs
        }
    }
}
