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

import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.domain.model.LearningGraph
import com.hermesgadget.talaria.domain.model.ModelOptionsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes the real v0.19.0 model-options and learning-graph shapes (extra keys ignored). */
class Phase15ModelDecodeTest {
    private val json = JsonConfig.json

    @Test
    fun `decodes model providers`() {
        val res = json.decodeFromString(
            ModelOptionsResponse.serializer(),
            """{"providers":[
                {"slug":"anthropic","name":"Anthropic","is_current":true,"models":["claude-fable-5","claude-sonnet-5"],"total_models":2,"authenticated":true},
                {"slug":"moa","name":"Mixture of Agents","is_current":false,"models":["default"],"total_models":1,"source":"virtual","warning":"Aggregator acts as the selected model."}
            ]}""",
        )
        assertEquals(2, res.providers.size)
        assertTrue(res.providers[0].is_current)
        assertEquals(listOf("claude-fable-5", "claude-sonnet-5"), res.providers[0].models)
        assertEquals("virtual", res.providers[1].source)
    }

    @Test
    fun `decodes learning graph with stats and clusters`() {
        val graph = json.decodeFromString(
            LearningGraph.serializer(),
            """{"nodes":[{"id":"n1","label":"n1","kind":"skill","timestamp":1,"category":"software-development","useCount":3,"state":"stale","createdBy":"agent","pinned":false}],
                "edges":[],
                "clusters":[{"category":"software-development","count":1}],
                "memory":[],
                "stats":{"nodes":1,"related_edges":0,"edges_per_node":0.0,"linked_nodes":0,"isolated_pct":100.0,"categories":1,"agent_created":1,"used":0,"top_categories":[["software-development",1]],"learned_skills":1}}""",
        )
        assertEquals(1, graph.nodes.size)
        assertEquals("skill", graph.nodes[0].kind)
        assertEquals(3, graph.nodes[0].useCount)
        assertEquals(1, graph.clusters.size)
        assertEquals(1, graph.stats.learned_skills)
        assertEquals(100.0, graph.stats.isolated_pct, 0.001)
    }
}
