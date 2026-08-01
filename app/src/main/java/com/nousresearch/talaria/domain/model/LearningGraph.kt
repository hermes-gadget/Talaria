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
package com.nousresearch.talaria.domain.model

import kotlinx.serialization.Serializable

/** A learned skill/memory node from `/api/learning/graph`. */
@Serializable
data class LearningNode(
    val id: String = "",
    val label: String = "",
    val kind: String? = null,
    val category: String? = null,
    val useCount: Int = 0,
    val state: String? = null,
    val createdBy: String? = null,
    val pinned: Boolean = false,
)

@Serializable
data class LearningCluster(
    val category: String = "",
    val count: Int = 0,
)

/** Scalar rollups from the graph `stats` object (extra keys ignored). */
@Serializable
data class LearningStats(
    val nodes: Int = 0,
    val linked_nodes: Int = 0,
    val isolated_pct: Double = 0.0,
    val categories: Int = 0,
    val learned_skills: Int = 0,
    val used: Int = 0,
    val agent_created: Int = 0,
)

@Serializable
data class LearningGraph(
    val nodes: List<LearningNode> = emptyList(),
    val clusters: List<LearningCluster> = emptyList(),
    val stats: LearningStats = LearningStats(),
)

@Serializable
data class LearningNodeDetail(
    val ok: Boolean = false,
    val content: String = "",
    val kind: String = "",
    val label: String = "",
)
