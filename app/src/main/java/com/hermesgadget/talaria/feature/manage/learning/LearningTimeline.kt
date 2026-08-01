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
package com.hermesgadget.talaria.feature.manage.learning

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LearningTimelineMarker(
    val label: String,
    val reveal: Float,
)

data class LearningTimeline(
    val timed: Boolean,
    val minTimestamp: Long?,
    val maxTimestamp: Long?,
    val markers: List<LearningTimelineMarker>,
)

fun buildLearningTimeline(nodes: List<LearningMapNode>): LearningTimeline {
    val recency = learningRecency(nodes)
    val markers = if (recency.timed) {
        listOf(0.25f, 0.5f, 0.75f, 1f).map { reveal ->
            LearningTimelineMarker(
                label = timelineDateAt(recency, reveal) ?: "${(reveal * 100).toInt()}%",
                reveal = reveal,
            )
        }
    } else {
        // With no timestamps, the ordinal is still useful. These chips give old gateways a
        // predictable set of date-group-like buckets instead of pretending dates are known.
        listOf(0.25f, 0.5f, 0.75f, 1f).mapIndexed { index, reveal ->
            LearningTimelineMarker(label = "Group ${index + 1}", reveal = reveal)
        }
    }
    return LearningTimeline(
        timed = recency.timed,
        minTimestamp = recency.minTimestamp,
        maxTimestamp = recency.maxTimestamp,
        markers = markers,
    )
}

fun timelineDateAt(recency: LearningRecency, reveal: Float): String? {
    val min = recency.minTimestamp ?: return null
    val max = recency.maxTimestamp ?: return null
    if (!recency.timed) return null
    val timestamp = min + ((max - min) * reveal.coerceIn(0f, 1f)).toLong()
    return formatLearningTimestamp(timestamp)
}

fun formatLearningTimestamp(timestamp: Long?): String? {
    timestamp ?: return null
    val format = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return format.format(Date(timestamp * 1_000L))
}
