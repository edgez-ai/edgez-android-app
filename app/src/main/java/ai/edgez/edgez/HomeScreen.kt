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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.ui.theme.EdgeZTheme

private data class NodeListItem(
    val name: String,
    val role: String,
    val nodeId: String,
    val status: String,
    val route: String,
)

private val previewNodes = listOf(
    NodeListItem("Jason", "Owner", "!edgez00", "Online", "USB"),
    NodeListItem("Field Node", "Sensor", "!edgez01", "Idle", "HaLow"),
    NodeListItem("Relay", "Router", "!edgez02", "Seen recently", "Mesh"),
)

@Composable
fun HomeScreen(
    activeConnection: ActiveConnection,
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
                Text("Home", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text("Interface: ${activeConnection.name}", style = MaterialTheme.typography.bodyMedium)
            }

            item {
                Text("Users / Nodes", style = MaterialTheme.typography.titleMedium)
            }

            items(previewNodes) { node ->
                NodeCard(node)
            }
        }
    }
}

@Composable
private fun NodeCard(node: NodeListItem) {
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
                    Text(node.name, style = MaterialTheme.typography.titleMedium)
                    Text(node.nodeId, style = MaterialTheme.typography.bodyMedium)
                }
                Text(node.status, style = MaterialTheme.typography.labelLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(node.role, style = MaterialTheme.typography.bodyMedium)
                Text(node.route, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    EdgeZTheme {
        HomeScreen(ActiveConnection.NONE)
    }
}
