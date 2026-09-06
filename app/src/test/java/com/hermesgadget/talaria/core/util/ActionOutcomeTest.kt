/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.hermesgadget.talaria.core.util

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Q05: {ok:false} bodies are application rejections even on HTTP 2xx. */
class ActionOutcomeTest {

    @Test
    fun `ok false with detail throws with the detail message`() {
        val body = buildJsonObject {
            put("ok", false)
            put("detail", "provider locked")
        }
        val thrown = assertThrows(ActionOutcome.ActionRejected::class.java) {
            ActionOutcome.requireOk(body)
        }
        assertEquals("provider locked", thrown.message)
    }

    @Test
    fun `ok false without detail uses the fallback message`() {
        val body = buildJsonObject { put("ok", false) }
        val thrown = assertThrows(ActionOutcome.ActionRejected::class.java) {
            ActionOutcome.requireOk(body, "rejected")
        }
        assertEquals("rejected", thrown.message)
    }

    @Test
    fun `ok true and bodies without ok field pass`() {
        ActionOutcome.requireOk(buildJsonObject { put("ok", true) })
        // No ok field: unknown schema — not a rejection.
        ActionOutcome.requireOk(buildJsonObject { put("detail", "anything") })
        ActionOutcome.requireOk(buildJsonObject { })
    }

    @Test
    fun `rejectionReason mirrors requireOk without throwing`() {
        assertNull(ActionOutcome.rejectionReason(buildJsonObject { put("ok", true) }))
        assertEquals(
            "nope",
            ActionOutcome.rejectionReason(buildJsonObject { put("ok", false); put("error", "nope") }),
        )
        assertNotNull(ActionOutcome.rejectionReason(buildJsonObject { put("ok", false) }))
    }
}
