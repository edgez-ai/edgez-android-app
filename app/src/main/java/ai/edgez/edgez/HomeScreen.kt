package ai.edgez.edgez

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.ui.theme.EdgeZTheme
import ai.edgez.edgez.usb.HaLowInterfaceStatus

@Composable
fun HomeScreen(
    activeConnection: ActiveConnection,
    haLowStatus: HaLowInterfaceStatus?,
    users: List<HaLowUser>,
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
                    Text("Home", style = MaterialTheme.typography.headlineMedium)
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

            items(users, key = { it.nodeNum }) { user ->
                NodeCard(user)
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
private fun NodeCard(user: HaLowUser) {
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
                    Text(user.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(user.nodeId, style = MaterialTheme.typography.bodyMedium)
                }
                Text("Seen", style = MaterialTheme.typography.labelLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(user.shortName.ifBlank { "User" }, style = MaterialTheme.typography.bodyMedium)
                Text(user.route, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    EdgeZTheme {
        HomeScreen(
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
        )
    }
}
