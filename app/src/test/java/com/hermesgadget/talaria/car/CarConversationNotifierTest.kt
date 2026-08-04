/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.car

import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.domain.model.ConnectionProfile
import com.hermesgadget.talaria.domain.model.ConnectionSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CarConversationNotifier pure helpers: stable ids + deep-link shape. */
class CarConversationNotifierTest {

    private val snapshot = ConnectionSnapshot(
        profile = ConnectionProfile(
            id = "conn-1",
            name = "Home",
            baseUrl = "http://192.168.2.5:9119",
            managementProfile = "hermes",
        ),
        secrets = ConnectionSecrets(),
    )

    @Test
    fun `notification id is stable per connection and session`() {
        val a = CarConversationNotifier.notificationId(snapshot, "session-1")
        val b = CarConversationNotifier.notificationId(snapshot, "session-1")
        assertEquals(a, b)

        val otherSession = CarConversationNotifier.notificationId(snapshot, "session-2")
        assertNotEquals(a, otherSession)

        val otherConnection = CarConversationNotifier.notificationId(
            snapshot.copy(profile = snapshot.profile.copy(id = "conn-2")),
            "session-1",
        )
        assertNotEquals(a, otherConnection)
    }

    @Test
    fun `deep link carries session connection and profile`() {
        val link = CarConversationNotifier.sessionDeepLink(snapshot, "session-9")
        assertTrue(link.startsWith("talaria://session/session-9"))
        assertTrue(link.contains("connection=conn-1"))
        assertTrue(link.contains("profile=hermes"))
    }

    @Test
    fun `blank profile falls back to the effective default profile`() {
        val link = CarConversationNotifier.sessionDeepLink(
            snapshot.copy(profile = snapshot.profile.copy(managementProfile = "")),
            "s",
        )
        assertTrue(link.startsWith("talaria://session/s"))
        assertTrue(link.contains("connection=conn-1"))
        // effectiveManagementProfile() normalizes blank -> "default", matching
        // the notification-reply deep links built by TalariaNotifier.
        assertTrue(link.contains("profile=default"))
    }
}
