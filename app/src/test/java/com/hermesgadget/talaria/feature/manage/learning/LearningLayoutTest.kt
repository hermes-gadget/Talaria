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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningLayoutTest {
    private val nodes = listOf(
        node("old", timestamp = 1_000L),
        node("middle", timestamp = 2_000L),
        node("new", timestamp = 3_000L, kind = "memory"),
    )

    @Test
    fun `seeded layout is deterministic and keeps newer nodes farther out`() {
        val first = layoutLearningNodes(nodes, width = 600f, height = 400f, seed = 42)
        val second = layoutLearningNodes(nodes, width = 600f, height = 400f, seed = 42)

        assertEquals(first, second)
        assertEquals(0f, first.byId.getValue("old").recency, 0.0001f)
        assertEquals(1f, first.byId.getValue("new").recency, 0.0001f)
        assertTrue(distanceFromCenter(first.byId.getValue("new"), 600f, 400f) >
            distanceFromCenter(first.byId.getValue("old"), 600f, 400f))
    }

    @Test
    fun `undated nodes still receive stable ordinal positions`() {
        val undated = nodes.map { it.copy(timestamp = null) }
        val layout = layoutLearningNodes(undated, width = 400f, height = 400f, seed = 7)

        assertEquals(0f, layout.byId.getValue("middle").recency, 0.0001f)
        assertEquals(0.5f, layout.byId.getValue("new").recency, 0.0001f)
        assertEquals(1f, layout.byId.getValue("old").recency, 0.0001f)
    }

    @Test
    fun `edge culling removes missing and self links`() {
        val edges = listOf(
            LearningMapEdge("old", "middle"),
            LearningMapEdge("middle", "missing"),
            LearningMapEdge("old", "old"),
            LearningMapEdge("old", "middle"),
        )

        assertEquals(
            listOf(LearningMapEdge("old", "middle")),
            cullLearningEdges(edges, setOf("old", "middle")),
        )
    }

    @Test
    fun `timeline reveal filters nodes and keeps edge endpoint set coherent`() {
        val visible = visibleLearningNodes(nodes, reveal = 0.5f)
        val visibleIds = visible.mapTo(hashSetOf()) { it.id }

        assertEquals(setOf("old", "middle"), visibleIds)
        assertEquals(
            listOf(LearningMapEdge("old", "middle")),
            cullLearningEdges(
                listOf(
                    LearningMapEdge("old", "middle"),
                    LearningMapEdge("middle", "new"),
                ),
                visibleIds,
            ),
        )
    }

    private fun node(id: String, timestamp: Long?, kind: String = "skill") = LearningMapNode(
        id = id,
        label = id,
        kind = kind,
        category = "test",
        useCount = 0,
        state = "active",
        createdBy = null,
        pinned = false,
        timestamp = timestamp,
    )

    private fun distanceFromCenter(position: LearningNodePosition, width: Float, height: Float): Float {
        val dx = position.point.x - width / 2f
        val dy = position.point.y - height / 2f
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
