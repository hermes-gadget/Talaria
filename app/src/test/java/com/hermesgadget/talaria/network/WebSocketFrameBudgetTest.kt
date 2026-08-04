/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hermesgadget.talaria.network

import com.hermesgadget.talaria.core.network.WebSocketFrameBudget
import okio.ByteString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketFrameBudgetTest {
    @Test
    fun `text boundary is measured in UTF-8 bytes`() {
        assertTrue(WebSocketFrameBudget.textWithinLimit("12345678", maxBytes = 8))
        assertFalse(WebSocketFrameBudget.textWithinLimit("123456789", maxBytes = 8))
        assertTrue(WebSocketFrameBudget.textWithinLimit("éééé", maxBytes = 8))
        assertFalse(WebSocketFrameBudget.textWithinLimit("ééééé", maxBytes = 8))
    }

    @Test
    fun `binary boundary is inclusive and rejects the first byte over`() {
        assertTrue(WebSocketFrameBudget.binaryWithinLimit(ByteString.of(*ByteArray(8)), maxBytes = 8))
        assertFalse(WebSocketFrameBudget.binaryWithinLimit(ByteString.of(*ByteArray(9)), maxBytes = 8))
    }
}
