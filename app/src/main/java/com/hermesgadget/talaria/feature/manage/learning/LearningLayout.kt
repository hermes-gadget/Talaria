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

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class LearningPoint(
    val x: Float,
    val y: Float,
)

data class LearningNodePosition(
    val node: LearningMapNode,
    val point: LearningPoint,
    val radius: Float,
    val recency: Float,
)

data class LearningLayout(
    val positions: List<LearningNodePosition>,
) {
    val byId: Map<String, LearningNodePosition> by lazy { positions.associateBy { it.node.id } }
}

data class LearningRecency(
    val byId: Map<String, Float>,
    val timed: Boolean,
    val minTimestamp: Long?,
    val maxTimestamp: Long?,
)

/**
 * Calculates a stable oldest-to-newest ratio. Timestamps are preferred; the id-sorted ordinal
 * is a deterministic fallback for older gateways that do not include them.
 */
fun learningRecency(nodes: List<LearningMapNode>): LearningRecency {
    val timestamps = nodes.mapNotNull { it.timestamp }
    val minTimestamp = timestamps.minOrNull()
    val maxTimestamp = timestamps.maxOrNull()
    val timed = minTimestamp != null && maxTimestamp != null && maxTimestamp > minTimestamp
    val ordered = nodes.sortedWith(
        compareBy<LearningMapNode> { it.timestamp ?: Long.MAX_VALUE }.thenBy { it.id },
    )
    val ordinal = ordered.mapIndexed { index, node ->
        node.id to if (ordered.size > 1) index.toFloat() / (ordered.size - 1).toFloat() else 0f
    }.toMap()
    val recency = nodes.associate { node ->
        val ratio = if (timed && node.timestamp != null && minTimestamp != null && maxTimestamp != null) {
            (node.timestamp - minTimestamp).toFloat() / (maxTimestamp - minTimestamp).toFloat()
        } else {
            ordinal[node.id] ?: 0f
        }
        node.id to ratio.coerceIn(0f, 1f)
    }
    return LearningRecency(recency, timed, minTimestamp, maxTimestamp)
}

/**
 * Seeded radial placement. The center is oldest and the outer ring is newest, matching the
 * desktop starmap while remaining cheap and deterministic on a phone (no force simulation).
 */
fun layoutLearningNodes(
    nodes: List<LearningMapNode>,
    width: Float,
    height: Float,
    seed: Int = DEFAULT_LAYOUT_SEED,
): LearningLayout {
    if (nodes.isEmpty() || width <= 0f || height <= 0f) return LearningLayout(emptyList())

    val recency = learningRecency(nodes)
    val center = LearningPoint(width / 2f, height / 2f)
    val extent = min(width, height)
    val innerRadius = extent * 0.075f
    val outerRadius = extent * 0.40f

    val positions = nodes.map { node ->
        val ratio = recency.byId[node.id] ?: 0f
        val distance = innerRadius + (outerRadius - innerRadius) * ratio
        val angle = seededAngle(node.id, seed)
        LearningNodePosition(
            node = node,
            point = LearningPoint(
                x = center.x + cos(angle).toFloat() * distance,
                y = center.y + sin(angle).toFloat() * distance,
            ),
            radius = learningNodeRadius(node),
            recency = ratio,
        )
    }
    return LearningLayout(positions)
}

fun learningNodeRadius(node: LearningMapNode): Float {
    val useBoost = (node.useCount.coerceAtMost(24) / 8f)
    return if (node.kind == "memory") 7f + useBoost else 8f + useBoost
}

/** Filters graph links to endpoints that are currently shown by the timeline. */
fun cullLearningEdges(
    edges: List<LearningMapEdge>,
    visibleNodeIds: Set<String>,
): List<LearningMapEdge> {
    val seen = LinkedHashSet<String>()
    return edges.filter { edge ->
        edge.source != edge.target &&
            edge.source in visibleNodeIds &&
            edge.target in visibleNodeIds &&
            seen.add("${edge.source}\u0000${edge.target}")
    }
}

fun visibleLearningNodes(
    nodes: List<LearningMapNode>,
    reveal: Float,
): List<LearningMapNode> {
    val recency = learningRecency(nodes).byId
    val threshold = reveal.coerceIn(0f, 1f)
    return nodes.filter { node -> (recency[node.id] ?: 0f) <= threshold + RECENCY_EPSILON }
}

private fun seededAngle(id: String, seed: Int): Double {
    var hash = FNV_OFFSET xor seed.toLong()
    id.forEach { character ->
        hash = (hash xor character.code.toLong()) * FNV_PRIME
    }
    val positive = hash and Long.MAX_VALUE
    return positive.rem(36000L).toDouble() / 36000.0 * (Math.PI * 2.0)
}

private const val DEFAULT_LAYOUT_SEED = 0x5A17
private const val RECENCY_EPSILON = 0.0001f
private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
