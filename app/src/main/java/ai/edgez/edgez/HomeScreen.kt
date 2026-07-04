package ai.edgez.edgez

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.ui.theme.EdgeZTheme
import ai.edgez.edgez.usb.HaLowInterfaceStatus
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun NodesScreen(
    activeConnection: ActiveConnection,
    haLowStatus: HaLowInterfaceStatus?,
    users: List<HaLowUser>,
    onRemoveNode: (HaLowUser) -> Unit,
    onOpenConversation: (HaLowUser) -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Nodes", style = MaterialTheme.typography.headlineMedium)
                    HaLowMeshStatusIcon(haLowStatus)
                }
                Spacer(Modifier.height(6.dp))
                Text("Interface: ${activeConnection.name}", style = MaterialTheme.typography.bodyMedium)
            }

            item {
                Text("Users / Nodes", style = MaterialTheme.typography.titleMedium)
            }

            if (users.isEmpty()) {
                item {
                    Text("No HaLow users seen yet", style = MaterialTheme.typography.bodyMedium)
                }
            }

            items(users, key = { user -> user.userUuid.ifBlank { user.nodeNum.toString() } }) { user ->
                SwipeToRemoveNodeCard(
                    user = user,
                    onRemove = { onRemoveNode(user) },
                    onOpenConversation = { onOpenConversation(user) },
                )
            }
        }
    }
}

@Composable
private fun HaLowMeshStatusIcon(status: HaLowInterfaceStatus?) {
    val color = when {
        status == null -> MaterialTheme.colorScheme.outline
        !status.supported -> MaterialTheme.colorScheme.error
        status.isUsable -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    val description = when {
        status == null -> "HaLow mesh status unknown"
        !status.supported -> "HaLow mesh unsupported"
        status.isUsable -> "HaLow mesh ready"
        status.linkUp -> "HaLow mesh link up"
        status.stackInitialized -> "HaLow mesh initializing"
        else -> "HaLow mesh not ready"
    }

    Icon(
        painter = painterResource(R.drawable.ic_halow_mesh),
        contentDescription = description,
        tint = color,
    )
}

@Composable
private fun SwipeToRemoveNodeCard(
    user: HaLowUser,
    onRemove: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    val actionWidth = 96.dp
    val actionWidthPx = with(LocalDensity.current) { actionWidth.toPx() }
    var targetOffsetPx by remember(user.nodeNum) { mutableFloatStateOf(0f) }
    val offsetPx by animateFloatAsState(targetValue = targetOffsetPx, label = "node-card-offset")

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.matchParentSize(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Button(
                modifier = Modifier
                    .width(actionWidth)
                    .fillMaxHeight(),
                onClick = onRemove,
            ) {
                Text("Delete")
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .clickable(onClick = onOpenConversation)
                .pointerInput(user.nodeNum) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            targetOffsetPx = if (targetOffsetPx < -actionWidthPx / 2f) {
                                -actionWidthPx
                            } else {
                                0f
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            targetOffsetPx = (targetOffsetPx + dragAmount).coerceIn(-actionWidthPx, 0f)
                        },
                    )
                },
        ) {
            NodeCard(user)
        }
    }
}

@Composable
private fun NodeCard(user: HaLowUser) {
    val context = LocalContext.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMs = System.currentTimeMillis()
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = user.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = user.markerTintColor() ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Node ${user.nodeId}", style = MaterialTheme.typography.bodyMedium)
                    Text("User ${user.userIdText}", style = MaterialTheme.typography.bodySmall)
                    Text("Type ${user.deviceType.label}", style = MaterialTheme.typography.bodySmall)
                    user.geoFence?.let {
                        Text("Geofence ${it.name}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (user.sleeping) {
                        Text(
                            text = "Sleeping",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "Last seen ${formatLastSeenAge(user.lastSeenMs, nowMs)}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (user.hasLocation()) {
                        IconButton(onClick = { context.openUserLocationInMap(user) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_location),
                                contentDescription = "Open location in map",
                                tint = user.markerTintColor() ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(user.shortName.ifBlank { "User" }, style = MaterialTheme.typography.bodyMedium)
                Text(user.route, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun formatLastSeenAge(lastSeenMs: Long, nowMs: Long): String {
    if (lastSeenMs <= 0L) return "unknown"
    val elapsedSeconds = ((nowMs - lastSeenMs).coerceAtLeast(0L)) / 1_000L
    if (elapsedSeconds < 60L) return "just now"

    val elapsedMinutes = elapsedSeconds / 60L
    if (elapsedMinutes < 60L) return "${elapsedMinutes}min"

    val elapsedHours = elapsedMinutes / 60L
    if (elapsedHours < 24L) return "${elapsedHours}hour"

    val elapsedDays = elapsedHours / 24L
    if (elapsedDays < 7L) return "${elapsedDays}day"

    val elapsedWeeks = elapsedDays / 7L
    if (elapsedDays < 30L) return "${elapsedWeeks}week"

    val elapsedMonths = elapsedDays / 30L
    if (elapsedDays < 365L) return "${elapsedMonths}month"

    return "${elapsedDays / 365L}year"
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    EdgeZTheme {
        NodesScreen(
            ActiveConnection.NONE,
            null,
            listOf(
                HaLowUser(
                    nodeNum = 0x1f7e6325,
                    shortName = "Sams",
                    longName = "Samsung",
                    route = "BLE",
                    lastSeenMs = 0,
                ),
            ),
            onRemoveNode = {},
            onOpenConversation = {},
        )
    }
}
