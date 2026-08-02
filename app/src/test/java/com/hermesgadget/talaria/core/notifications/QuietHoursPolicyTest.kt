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

package com.hermesgadget.talaria.core.notifications

import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursPolicyTest {
    @Test
    fun disabledWindowIsNeverActive() {
        val settings = QuietHoursSettings(enabled = false, startMinutes = 22 * 60, endMinutes = 7 * 60)

        assertFalse(QuietHoursPolicy.isActive(settings, LocalTime.of(23, 0)))
        assertFalse(QuietHoursPolicy.isActive(settings, LocalTime.of(3, 0)))
    }

    @Test
    fun overnightWindowCoversBothSidesOfMidnight() {
        val settings = QuietHoursSettings(enabled = true, startMinutes = 22 * 60, endMinutes = 7 * 60)

        assertTrue(QuietHoursPolicy.isActive(settings, LocalTime.of(22, 0)))
        assertTrue(QuietHoursPolicy.isActive(settings, LocalTime.of(2, 30)))
        assertFalse(QuietHoursPolicy.isActive(settings, LocalTime.of(12, 0)))
    }

    @Test
    fun daytimeWindowUsesAnExclusiveEnd() {
        val settings = QuietHoursSettings(enabled = true, startMinutes = 9 * 60, endMinutes = 17 * 60)

        assertFalse(QuietHoursPolicy.isActive(settings, LocalTime.of(8, 59)))
        assertTrue(QuietHoursPolicy.isActive(settings, LocalTime.of(9, 0)))
        assertTrue(QuietHoursPolicy.isActive(settings, LocalTime.of(16, 59)))
        assertFalse(QuietHoursPolicy.isActive(settings, LocalTime.of(17, 0)))
    }

    @Test
    fun equalEndpointsRepresentAnAllDayWindow() {
        val settings = QuietHoursSettings(enabled = true, startMinutes = 12 * 60, endMinutes = 12 * 60)

        assertTrue(QuietHoursPolicy.isActive(settings, LocalTime.MIDNIGHT))
        assertTrue(QuietHoursPolicy.isActive(settings, LocalTime.NOON))
    }
}
