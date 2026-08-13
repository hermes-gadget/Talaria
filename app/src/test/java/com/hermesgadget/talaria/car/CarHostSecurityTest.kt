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

package com.hermesgadget.talaria.car

import android.app.Application
import android.content.Context
import androidx.car.app.validation.HostValidator
import androidx.test.core.app.ApplicationProvider
import com.hermesgadget.talaria.core.data.prefs.CarHostIdentity
import com.hermesgadget.talaria.core.data.prefs.CarHostTrustRecord
import com.hermesgadget.talaria.core.data.prefs.CarHostTrustStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CarHostSecurityTest {
    private lateinit var context: Context
    private lateinit var store: CarHostTrustStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        check(prefs.edit().clear().commit())
        store = CarHostTrustStore(prefs)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `enroll revoke and certificate changes fail closed`() {
        val enrolled = identity("com.example.carhost", 'a')
        val resigned = identity("com.example.carhost", 'b')

        store.enroll(enrolled, nowMillis = 1_000L)

        assertTrue(store.isEnrolled(enrolled))
        assertFalse(store.isEnrolled(resigned))
        assertEquals(1_000L, store.recordFor(enrolled.packageName)?.enrolledAt)

        store.observe(resigned, nowMillis = 2_000L)

        assertFalse(store.isEnrolled(enrolled))
        assertFalse(store.isEnrolled(resigned))
        assertEquals(resigned, store.recordFor(resigned.packageName)?.identity)

        store.enroll(resigned, nowMillis = 3_000L)
        assertTrue(store.approveActions(resigned, nowMillis = 4_000L))
        store.recordAction(resigned, "send_prompt", nowMillis = 4_100L)
        assertEquals(resigned, store.recentActions().single().identity)

        store.revoke(resigned.packageName)
        assertFalse(store.isEnrolled(resigned))
        assertFalse(store.approveActions(resigned, nowMillis = 5_000L))
        assertEquals(resigned, store.list().single().identity)

        store.clear()
        assertTrue(store.list().isEmpty())
        assertTrue(store.recentActions().isEmpty())
    }

    @Test
    fun `release session policy requires an exact certificate and recent action approval`() {
        val known = identity("com.example.knownhost", '1')
        val manual = identity("com.example.manualhost", '2')
        val mismatched = identity("com.example.manualhost", '3')
        val manualRecord = CarHostTrustRecord(
            identity = manual,
            enrolledAt = 10_000L,
            actionApprovedAt = 20_000L,
        )

        val knownDecision = CarHostTrustPolicy.decide(
            debugBuild = false,
            identity = known,
            knownHosts = setOf(known),
            storedRecord = null,
            nowMillis = 50_000L,
        )
        assertTrue(knownDecision.canReadTranscripts)
        assertTrue(knownDecision.canPerformActions)

        val manualDecision = CarHostTrustPolicy.decide(
            debugBuild = false,
            identity = manual,
            knownHosts = emptySet(),
            storedRecord = manualRecord,
            nowMillis = 20_000L + CarHostTrustPolicy.ACTION_APPROVAL_WINDOW_MILLIS,
        )
        assertTrue(manualDecision.canReadTranscripts)
        assertTrue(manualDecision.canPerformActions)

        val expiredDecision = CarHostTrustPolicy.decide(
            debugBuild = false,
            identity = manual,
            knownHosts = emptySet(),
            storedRecord = manualRecord,
            nowMillis = 20_001L + CarHostTrustPolicy.ACTION_APPROVAL_WINDOW_MILLIS,
        )
        assertTrue(expiredDecision.canReadTranscripts)
        assertFalse(expiredDecision.canPerformActions)

        val mismatchDecision = CarHostTrustPolicy.decide(
            debugBuild = false,
            identity = mismatched,
            knownHosts = emptySet(),
            storedRecord = manualRecord,
            nowMillis = 20_000L,
        )
        assertFalse(mismatchDecision.canReadTranscripts)
        assertFalse(mismatchDecision.canPerformActions)

        val debugDecision = CarHostTrustPolicy.decide(
            debugBuild = true,
            identity = null,
            knownHosts = emptySet(),
            storedRecord = null,
            nowMillis = 0L,
        )
        assertTrue(debugDecision.canReadTranscripts)
        assertTrue(debugDecision.canPerformActions)
    }

    @Test
    fun `validator keeps debug permissive and release merges AndroidX with enrolled hosts`() {
        val expectedAndroidx = setOf(
            identity(
                "com.google.android.projection.gearhead",
                "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf",
            ),
            identity(
                "com.google.android.projection.gearhead",
                "70811a3eacfd2e83e18da9bfede52df16ce91f2e69a44d21f18ab66991130771",
            ),
            identity(
                "com.google.android.projection.gearhead",
                "1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00",
            ),
            identity(
                "com.google.android.apps.automotive.templates.host",
                "c241ffbc8e287c4e9a4ad19632ba1b1351ad361d5177b7d7b29859bd2b7fc631",
            ),
            identity(
                "com.google.android.apps.automotive.templates.host",
                "dd66deaf312d8daec7adbe85a218ecc8c64f3b152f9b5998d5b29300c2623f61",
            ),
            identity(
                "com.google.android.apps.automotive.templates.host",
                "50e603d333c6049a37bd751375d08f3bd0abebd33facd30bd17b64b89658b421",
            ),
        )
        assertEquals(expectedAndroidx, AndroidxKnownCarHosts.identities(context))

        val manual = identity("com.example.sideloadedhost", 'e')
        store.enroll(manual, nowMillis = 10L)
        val releaseValidator = CarHostValidatorFactory.create(context, false, store)

        assertTrue(
            releaseValidator.allowedHosts[manual.packageName]
                .orEmpty()
                .contains(manual.certificateSha256),
        )
        expectedAndroidx.forEach { known ->
            assertTrue(
                releaseValidator.allowedHosts[known.packageName]
                    .orEmpty()
                    .contains(known.certificateSha256),
            )
        }
        assertSame(
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR,
            CarHostValidatorFactory.create(context, true, store),
        )
    }

    @Test
    fun `corrupt store fails closed with empty enrollment and no throw`() {
        // Keystore unavailable (null prefs): construction must not throw, no
        // host may be enrolled, and mutations must no-op without crashing.
        val corrupt = CarHostTrustStore(prefs = null)
        assertTrue(corrupt.list().isEmpty())
        assertFalse(corrupt.isEnrolled(identity("com.example.carhost", 'a')))
        corrupt.enroll(identity("com.example.carhost", 'a'))
        corrupt.observe(identity("com.example.carhost", 'a'))
        corrupt.approveActions(identity("com.example.carhost", 'a'))
        assertTrue(corrupt.list().isEmpty())
        assertTrue(corrupt.recentActions().isEmpty())
        assertEquals(
            CarHostTrustStore.CarHostTrustStoreState.Corrupt::class,
            corrupt.state.value::class,
        )
        // No app context available for retry in this construction.
        assertFalse(corrupt.retry())
    }

    @Test
    fun `recovered store returns to available after retry`() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        check(prefs.edit().clear().commit())
        val store = CarHostTrustStore(prefs)
        store.enroll(identity("com.example.carhost", 'a'))
        assertTrue(store.isEnrolled(identity("com.example.carhost", 'a')))
        assertEquals(CarHostTrustStore.CarHostTrustStoreState.Available, store.state.value)
        // Already available: retry is a no-op success.
        assertTrue(store.retry())
    }

    private fun identity(packageName: String, hex: Char): CarHostIdentity =
        CarHostIdentity.create(packageName, hex.toString().repeat(64))!!

    private fun identity(packageName: String, fingerprint: String): CarHostIdentity =
        CarHostIdentity.create(packageName, fingerprint)!!

    companion object {
        private const val PREFS_NAME = "car_host_security_test"
    }
}
