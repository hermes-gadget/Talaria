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
package com.hermesgadget.talaria.network

import com.hermesgadget.talaria.core.util.AnsiStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AnsiStripperTest {
    @Test
    fun stripsCsiSequences() {
        val real = "\u001B[31mHello\u001B[0m world"
        val out = AnsiStripper.strip(real)
        assertEquals("Hello world", out)
        assertFalse(out.contains("\u001B"))
    }

    @Test
    fun stripsCarriageReturns() {
        assertEquals("line", AnsiStripper.strip("line\r"))
    }
}
