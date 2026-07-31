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

package com.nousresearch.talaria.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared spacing/density scale. Screens read these tokens instead of hardcoding
 * dp literals so density can be tuned globally in one place.
 *
 * Scale is a 4dp base grid; semantic aliases name the common layout roles.
 */
data class Spacing(
    // Raw scale
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    // Semantic aliases
    val screenH: Dp = 16.dp,
    val screenV: Dp = 6.dp,
    val cardPad: Dp = 12.dp,
    val itemGap: Dp = 6.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }
