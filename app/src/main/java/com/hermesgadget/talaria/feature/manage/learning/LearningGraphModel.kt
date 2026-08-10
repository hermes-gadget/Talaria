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

import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.network.HermesClientFactory
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.JsonConfig
import com.hermesgadget.talaria.core.network.decodeJsonResponse
import com.hermesgadget.talaria.core.util.suspendResult
import com.hermesgadget.talaria.domain.model.LearningCluster
import com.hermesgadget.talaria.domain.model.LearningGraph
import com.hermesgadget.talaria.domain.model.LearningStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The fields needed by the mobile starmap, including fields omitted by the legacy shared model. */
data class LearningMapNode(
    val id: String,
    val label: String,
    val kind: String,
    val category: String?,
    val useCount: Int,
    val state: String?,
    val createdBy: String?,
    val pinned: Boolean,
    val timestamp: Long?,
)

data class LearningMapEdge(
    val source: String,
    val target: String,
)

data class LearningGraphSnapshot(
    val nodes: List<LearningMapNode> = emptyList(),
    val edges: List<LearningMapEdge> = emptyList(),
    val clusters: List<LearningCluster> = emptyList(),
    val stats: LearningStats = LearningStats(),
) {
    companion object {
        /** Keeps mutation flows useful if the enriched graph cannot be re-read after a write. */
        fun fromTyped(graph: LearningGraph): LearningGraphSnapshot = LearningGraphSnapshot(
            nodes = graph.nodes.map { node ->
                LearningMapNode(
                    id = node.id,
                    label = node.label,
                    kind = node.kind ?: "skill",
                    category = node.category,
                    useCount = node.useCount,
                    state = node.state,
                    createdBy = node.createdBy,
                    pinned = node.pinned,
                    timestamp = null,
                )
            },
            clusters = graph.clusters,
            stats = graph.stats,
        )
    }
}

/**
 * Reads the graph envelope without changing the shared API/domain files.
 *
 * The existing typed API intentionally ignores unknown JSON fields, while the v0.19 graph
 * carries the timestamps and edges required for a useful radial view. This adapter is scoped
 * to the Learning feature so the rest of the client can continue using the established model.
 */
class LearningGraphSource(
    private val clientFactory: HermesClientFactory,
    private val connectionStore: SecureConnectionStore,
) {
    private val json = JsonConfig.json

    suspend fun load(snapshot: ConnectionSnapshot? = null): Result<LearningGraphSnapshot> =
        withContext(Dispatchers.IO) {
            suspendResult {
                val requestSnapshot = snapshot ?: clientFactory.snapshot()
                val request = Request.Builder()
                    .url(graphUrl(requestSnapshot))
                    .get()
                    .build()
                clientFactory.okHttp(requestSnapshot).newCall(request).execute().use { response ->
                    check(response.isSuccessful) {
                        "Learning graph request failed (${response.code})"
                    }
                    val body = response.body ?: error("Hermes returned an empty learning graph")
                    parse(body.decodeJsonResponse<JsonObject>())
                }
            }
        }

    private fun graphUrl(snapshot: ConnectionSnapshot? = null): HttpUrl {
        val base = snapshot?.baseUrl?.trimEnd('/')
            ?: connectionStore.activeProfile()?.baseUrl?.trimEnd('/')
            ?: "http://10.0.2.2:9119"
        return base.toHttpUrl().newBuilder()
            .addPathSegments("api/learning/graph")
            .build()
    }

    private fun parse(root: JsonObject): LearningGraphSnapshot {
        val nodes = root.array("nodes").mapNotNull { element ->
            val node = element as? JsonObject ?: return@mapNotNull null
            LearningMapNode(
                id = node.string("id").orEmpty(),
                label = node.string("label").orEmpty(),
                kind = node.string("kind") ?: "skill",
                category = node.string("category"),
                useCount = node.int("useCount"),
                state = node.string("state"),
                createdBy = node.string("createdBy"),
                pinned = node.boolean("pinned"),
                timestamp = node.timestamp("timestamp"),
            ).takeUnless { it.id.isBlank() }
        }
        val edges = root.array("edges").mapNotNull { element ->
            val edge = element as? JsonObject ?: return@mapNotNull null
            LearningMapEdge(
                source = edge.string("source").orEmpty(),
                target = edge.string("target").orEmpty(),
            ).takeUnless { it.source.isBlank() || it.target.isBlank() }
        }
        val clusters = root.array("clusters").mapNotNull { element ->
            runCatching { json.decodeFromJsonElement<LearningCluster>(element) }.getOrNull()
        }
        val stats = root["stats"]?.let { element ->
            runCatching { json.decodeFromJsonElement<LearningStats>(element) }.getOrNull()
        } ?: LearningStats()

        return LearningGraphSnapshot(nodes = nodes, edges = edges, clusters = clusters, stats = stats)
    }

    private fun JsonObject.array(name: String): JsonArray =
        runCatching { this[name]?.jsonArray ?: JsonArray(emptyList()) }
            .getOrDefault(JsonArray(emptyList()))

    private fun JsonObject.string(name: String): String? =
        runCatching { this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }
            .getOrNull()

    private fun JsonObject.int(name: String): Int =
        string(name)?.toIntOrNull() ?: 0

    private fun JsonObject.boolean(name: String): Boolean =
        runCatching { this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false }
            .getOrDefault(false)

    private fun JsonObject.timestamp(name: String): Long? {
        val raw = string(name)?.let { value ->
            value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong()
        } ?: return null
        // The dashboard currently sends epoch seconds; accepting milliseconds keeps the view
        // tolerant of an API proxy that normalizes timestamps differently.
        return if (raw > 100_000_000_000L) raw / 1_000L else raw
    }
}
