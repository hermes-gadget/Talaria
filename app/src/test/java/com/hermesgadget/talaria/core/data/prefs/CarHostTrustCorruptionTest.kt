/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.data.prefs

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for B05: an undecodable (corrupted) car host trust state
 * must publish typed corruption, fail closed, refuse to overwrite the damaged
 * record, and recovery must revalidate the stored content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CarHostTrustCorruptionTest {
    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        check(prefs.edit().clear().commit())
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    private fun identity() = CarHostIdentity.create(
        "com.example.corrupt.host",
        "a".repeat(64),
    )!!

    @Test
    fun corruptStateFailsClosedAndRefusesOverwrite() {
        // Seed an invalid state: valid JSON whose identity fields violate the
        // identity constructor (package regex + hex fingerprint), so decode
        // throws and the store must publish corruption.
        check(prefs.edit().putString(KEY_STATE, INVALID_IDENTITY_PAYLOAD).commit())

        val store = CarHostTrustStore(prefs)

        // Reads fail closed: nothing trusted, empty records.
        assertTrue(store.list().isEmpty())
        assertTrue(store.listEnrolledIdentities().isEmpty())
        assertFalse(store.isEnrolled(identity()))
        // The corruption is published, not silently masked.
        val state = runBlocking { store.state.first() }
        assertTrue(state is CarHostTrustStore.CarHostTrustStoreState.Corrupt)

        // Mutations must NOT overwrite the damaged record with fresh data.
        store.enroll(identity(), nowMillis = 1_000L)
        assertEquals(INVALID_IDENTITY_PAYLOAD, prefs.getString(KEY_STATE, null))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun retryRecoversWhenContentBecomesValidAgain() {
        check(prefs.edit().putString(KEY_STATE, CORRUPT_PAYLOAD).commit())
        val store = CarHostTrustStore(prefs)
        assertFalse(store.retry()) // still corrupt

        // Repair the stored payload (as a recovery flow that rewrites valid
        // state would): a well-formed record with an enrolled host.
        val identity = identity()
        val valid = "{\"hosts\":[{\"identity\":{\"packageName\":\"${identity.packageName}\"," +
            "\"certificateSha256\":\"${identity.certificateSha256}\"},\"enrolledAt\":1000}],\"actions\":[]}"
        check(prefs.edit().putString(KEY_STATE, valid).commit())

        assertTrue(store.retry())
        assertTrue(store.isEnrolled(identity))
    }

    @Test
    fun resetCorruptStateWipesDamagedRecordAndRestoresUsability() {
        check(prefs.edit().putString(KEY_STATE, CORRUPT_PAYLOAD).commit())
        val store = CarHostTrustStore(prefs)
        assertTrue(store.list().isEmpty())

        assertTrue(store.resetCorruptState())
        assertEquals(null, prefs.getString(KEY_STATE, null))
        // The store is usable again: enroll persists.
        store.enroll(identity(), nowMillis = 1_000L)
        assertTrue(store.isEnrolled(identity()))
    }

    @Test
    fun resetCorruptStateIsRejectedWhenHealthy() {
        val store = CarHostTrustStore(prefs)
        store.enroll(identity(), nowMillis = 1_000L)
        assertFalse(store.resetCorruptState())
        assertTrue(store.isEnrolled(identity()))
    }

    @Test
    fun emptyStoreIsHealthyAndRetryKeepsWorkingState() {
        val store = CarHostTrustStore(prefs)
        store.enroll(identity(), nowMillis = 1_000L)
        assertTrue(store.retry())
        assertTrue(store.isEnrolled(identity()))
    }

    private companion object {
        const val PREFS_NAME = "test_car_host_trust"
        const val KEY_STATE = "car_host_trust_state"

        /** Undecodable garbage. */
        const val CORRUPT_PAYLOAD = "definitely-not-json{{"

        /** Parses as JSON but violates CarHostIdentity's constructor. */
        const val INVALID_IDENTITY_PAYLOAD =
            "{\"hosts\":[{\"identity\":{\"packageName\":\"###\",\"certificateSha256\":\"zz\"}}],\"actions\":[]}"
    }
}
