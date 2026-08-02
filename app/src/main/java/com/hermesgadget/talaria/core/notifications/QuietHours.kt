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

data class QuietHoursSettings(
    val enabled: Boolean,
    val startMinutes: Int,
    val endMinutes: Int,
)

/** Pure quiet-hours window evaluation, kept independent of Android time APIs for testing. */
object QuietHoursPolicy {
    const val DEFAULT_START_MINUTES = 22 * 60
    const val DEFAULT_END_MINUTES = 7 * 60
    private const val MINUTES_PER_DAY = 24 * 60

    fun isActive(settings: QuietHoursSettings, now: LocalTime = LocalTime.now()): Boolean =
        isActive(settings, now.hour * 60 + now.minute)

    fun isActive(settings: QuietHoursSettings, nowMinutes: Int): Boolean {
        if (!settings.enabled) return false

        val start = normalize(settings.startMinutes)
        val end = normalize(settings.endMinutes)
        val now = normalize(nowMinutes)

        // Equal endpoints are useful as an explicit all-day quiet schedule.
        if (start == end) return true
        return if (start < end) {
            now >= start && now < end
        } else {
            // Overnight window, for example 22:00 through 07:00.
            now >= start || now < end
        }
    }

    private fun normalize(minutes: Int): Int = Math.floorMod(minutes, MINUTES_PER_DAY)
}
