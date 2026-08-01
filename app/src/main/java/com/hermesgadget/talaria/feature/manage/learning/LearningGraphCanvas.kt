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

import android.graphics.Paint
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningStarmapCard(
    graph: LearningGraphSnapshot,
    visibleNodes: List<LearningMapNode>,
    timeline: LearningTimeline,
    reveal: Float,
    onRevealChange: (Float) -> Unit,
    onNodeClick: (LearningMapNode) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Starmap", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${visibleNodes.size}/${graph.nodes.size} visible",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LearningLegend()
            LearningGraphCanvas(
                nodes = graph.nodes,
                edges = graph.edges,
                visibleNodes = visibleNodes,
                onNodeClick = onNodeClick,
                modifier = Modifier.fillMaxWidth().height(340.dp),
            )
            LearningTimelineControls(
                timeline = timeline,
                reveal = reveal,
                totalNodes = graph.nodes.size,
                visibleNodes = visibleNodes.size,
                onRevealChange = onRevealChange,
            )
            Text(
                "Drag to pan · pinch to zoom · tap a node for details",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LearningLegend() {
    val spacing = Arrangement.spacedBy(12.dp)
    Row(horizontalArrangement = spacing) {
        LegendDot(MaterialTheme.colorScheme.primary, "Skills")
        LegendDot(MaterialTheme.colorScheme.tertiary, "Memories")
        Text("lines = related", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("●", color = color, style = MaterialTheme.typography.labelSmall)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LearningGraphCanvas(
    nodes: List<LearningMapNode>,
    edges: List<LearningMapEdge>,
    visibleNodes: List<LearningMapNode>,
    onNodeClick: (LearningMapNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(nodes, canvasSize) {
        layoutLearningNodes(nodes, canvasSize.width.toFloat(), canvasSize.height.toFloat())
    }
    val visibleIds = remember(visibleNodes) { visibleNodes.mapTo(hashSetOf()) { it.id } }
    val tapHandler by rememberUpdatedState(onNodeClick)
    val skillColor = MaterialTheme.colorScheme.primary
    val memoryColor = MaterialTheme.colorScheme.tertiary
    val edgeColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val ringColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val labelColor = MaterialTheme.colorScheme.onSurface
    val centerColor = MaterialTheme.colorScheme.secondary
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }

    LaunchedEffect(nodes) {
        zoom = 1f
        pan = Offset.Zero
    }

    Canvas(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val oldZoom = zoom
                    val nextZoom = (oldZoom * zoomChange).coerceIn(0.65f, 3.5f)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val fromWorldCenter = centroid - center - pan
                    pan += panChange + fromWorldCenter * (1f - nextZoom / oldZoom)
                    zoom = nextZoom
                }
            }
            .pointerInput(layout, visibleIds) {
                detectTapGestures { offset ->
                    if (layout.positions.isEmpty()) return@detectTapGestures
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val world = (offset - center - pan) / zoom + center
                    val hit = layout.positions
                        .asSequence()
                        .filter { it.node.id in visibleIds }
                        .map { position ->
                            val dx = position.point.x - world.x
                            val dy = position.point.y - world.y
                            position to (dx * dx + dy * dy)
                        }
                        .filter { (position, distanceSquared) ->
                            distanceSquared <= (position.radius + 14f / zoom) * (position.radius + 14f / zoom)
                        }
                        .minByOrNull { it.second }
                        ?.first
                    hit?.let { tapHandler(it.node) }
                }
            },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val visible = layout.positions.filter { it.node.id in visibleIds }
        val edgesToDraw = cullLearningEdges(edges, visibleIds)
        val byId = layout.byId

        withTransform({
            translate(center.x + pan.x, center.y + pan.y)
            scale(zoom, zoom)
        }) {
            val ringStep = minOf(size.width, size.height) * 0.13f
            for (ring in 1..3) {
                drawCircle(
                    color = ringColor,
                    radius = ringStep * ring,
                    center = Offset.Zero,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f / zoom),
                )
            }
            edgesToDraw.forEach { edge ->
                val source = byId[edge.source]?.point ?: return@forEach
                val target = byId[edge.target]?.point ?: return@forEach
                drawLine(
                    color = edgeColor,
                    start = Offset(source.x - center.x, source.y - center.y),
                    end = Offset(target.x - center.x, target.y - center.y),
                    strokeWidth = 1.2f / zoom,
                )
            }
            drawCircle(
                color = centerColor.copy(alpha = 0.8f),
                radius = 5f,
                center = Offset.Zero,
            )
            visible.forEach { position ->
                val point = Offset(position.point.x - center.x, position.point.y - center.y)
                val color = if (position.node.kind == "memory") memoryColor else skillColor
                drawCircle(color = color, radius = position.radius, center = point)
                drawCircle(
                    color = Color.White.copy(alpha = 0.35f),
                    radius = position.radius * 0.32f,
                    center = point - Offset(position.radius * 0.25f, position.radius * 0.25f),
                )
                labelPaint.color = labelColor.toArgb()
                labelPaint.textSize = 10.dp.toPx() / zoom
                labelPaint.typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.NORMAL,
                )
                drawContext.canvas.nativeCanvas.drawText(
                    position.node.label.ifBlank { position.node.id }.take(26),
                    point.x + position.radius + 4f,
                    point.y + 4f,
                    labelPaint,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LearningTimelineControls(
    timeline: LearningTimeline,
    reveal: Float,
    totalNodes: Int,
    visibleNodes: Int,
    onRevealChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Timeline", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        if (timeline.timed) {
            Slider(
                value = reveal,
                onValueChange = onRevealChange,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            timeline.markers.forEach { marker ->
                FilterChip(
                    selected = kotlin.math.abs(reveal - marker.reveal) < 0.02f,
                    onClick = { onRevealChange(marker.reveal) },
                    label = { Text(marker.label) },
                )
            }
        }
        val revealLabel = if (timeline.timed) {
            timelineDateAt(
                LearningRecency(
                    byId = emptyMap(),
                    timed = true,
                    minTimestamp = timeline.minTimestamp,
                    maxTimestamp = timeline.maxTimestamp,
                ),
                reveal,
            ) ?: "current"
        } else {
            "group ${((reveal.coerceIn(0f, 1f) * 4f).toInt() + 1).coerceAtMost(4)}"
        }
        Text(
            "Showing $visibleNodes of $totalNodes · through $revealLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
