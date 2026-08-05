/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.feature.capture

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareIntakeStoreTest {
    @Test
    fun `sending draft is recovered for review after process death`() {
        val scope = "share-test-${UUID.randomUUID()}"
        val store = ShareIntakeStore(
            context = ApplicationProvider.getApplicationContext(),
            nowMillis = { 1L },
        )
        val draft = ShareIntakeDraft(
            scopeId = scope,
            connectionId = "connection",
            managementProfile = "default",
            createdAt = 1L,
            updatedAt = 1L,
            deliveryState = ShareDraftDeliveryState.SENDING,
        )

        store.save(draft)
        val recovered = store.load(scope)

        assertNotNull(recovered)
        assertEquals(ShareDraftDeliveryState.DRAFT, recovered?.deliveryState)
        assertEquals(
            "Delivery status is unknown; review this draft before retrying.",
            recovered?.deliveryMessage,
        )
        recovered?.let(store::remove)
    }
}
