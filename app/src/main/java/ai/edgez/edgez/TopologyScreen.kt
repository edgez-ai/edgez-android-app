package ai.edgez.edgez

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val TOPOLOGY_REFRESH_MS = 30_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopologyScreen(
    edgeZDatabase: EdgeZDatabase,
    users: Map<Long, HaLowUser>,
    modifier: Modifier = Modifier,
) {
    var observations by remember { mutableStateOf<List<MeshTopologyObservation>>(emptyList()) }

    LaunchedEffect(edgeZDatabase) {
        while (true) {
            observations = withContext(Dispatchers.IO) {
                edgeZDatabase.getRecentTopology()
            }
            delay(TOPOLOGY_REFRESH_MS)
        }
    }

    val links = remember(observations) { collapseTopologyLinks(observations) }
    val nodeIds = remember(links) {
        links.flatMap { listOf(it.reporterNodeNum, it.peerNodeNum) }.distinct().sorted()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Mesh topology", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Graph from beacons heard in the last 5 minutes",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            TopologySummary(nodeIds.size, links.size)
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                if (links.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No recent mesh links", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "The graph appears when remote beacons report peers.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    TopologyGraph(
                        links = links,
                        nodeIds = nodeIds,
                        users = users,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TopologySummary(nodeCount: Int, linkCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TopologyMetric("Nodes", nodeCount.toString())
            TopologyMetric("Links", linkCount.toString())
            TopologyMetric("Window", "5 min")
        }
    }
}

@Composable
private fun TopologyMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TopologyGraph(
    links: List<MeshTopologyObservation>,
    nodeIds: List<Long>,
    users: Map<Long, HaLowUser>,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val nodeTextColor = MaterialTheme.colorScheme.onPrimary
    val unknownColor = MaterialTheme.colorScheme.outline
    val labelBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    val labelTextColor = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val graphRadius = min(size.width, size.height) * if (nodeIds.size <= 2) 0.28f else 0.37f
        val nodeRadius = min(size.width, size.height) * 0.055f
        val positions = nodeIds.mapIndexed { index, nodeId ->
            val angle = -PI / 2.0 + (2.0 * PI * index / nodeIds.size.coerceAtLeast(1))
            nodeId to Offset(
                center.x + graphRadius * cos(angle).toFloat(),
                center.y + graphRadius * sin(angle).toFloat(),
            )
        }.toMap()

        links.forEach { link ->
            val start = positions[link.reporterNodeNum] ?: return@forEach
            val end = positions[link.peerNodeNum] ?: return@forEach
            val rssi = link.rssiDbm
            val lineColor = when {
                rssi == null -> unknownColor
                rssi >= -65 -> Color(0xFF2E7D32)
                rssi >= -85 -> Color(0xFFF9A825)
                else -> Color(0xFFC62828)
            }
            drawLine(lineColor, start, end, strokeWidth = 5f)

            val label = rssi?.let { "$it dBm" } ?: "RSSI unknown"
            val mid = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
            val textPaint = Paint().apply {
                color = labelTextColor.toArgb()
                textSize = 28f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val width = textPaint.measureText(label)
            drawContext.canvas.nativeCanvas.drawRoundRect(
                RectF(mid.x - width / 2f - 12f, mid.y - 28f, mid.x + width / 2f + 12f, mid.y + 10f),
                10f,
                10f,
                Paint().apply { color = labelBackground.toArgb(); isAntiAlias = true },
            )
            drawContext.canvas.nativeCanvas.drawText(label, mid.x, mid.y, textPaint)
        }

        positions.forEach { (nodeId, position) ->
            drawCircle(primary, nodeRadius, position)
            val label = topologyNodeLabel(nodeId, users)
            val paint = Paint().apply {
                color = nodeTextColor.toArgb()
                textSize = 26f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            drawContext.canvas.nativeCanvas.drawText(
                label,
                position.x,
                position.y - (paint.ascent() + paint.descent()) / 2f,
                paint,
            )
        }
    }
}

private fun collapseTopologyLinks(
    observations: List<MeshTopologyObservation>,
): List<MeshTopologyObservation> {
    return observations
        .sortedByDescending { it.lastSeenMs }
        .distinctBy {
            val low = minOf(it.reporterNodeNum, it.peerNodeNum)
            val high = maxOf(it.reporterNodeNum, it.peerNodeNum)
            low to high
        }
}

private fun topologyNodeLabel(nodeNum: Long, users: Map<Long, HaLowUser>): String {
    users[nodeNum]?.displayName?.takeIf { it.isNotBlank() }?.let { return it.take(10) }
    return "%02x:%02x".format((nodeNum shr 8) and 0xffL, nodeNum and 0xffL)
}
