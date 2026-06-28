package ai.edgez.edgez

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.usb.UsbCandidate

@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
fun DeviceList(
    candidates: List<UsbCandidate>,
    selected: UsbCandidate?,
    onSelect: (UsbCandidate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("USB devices", style = MaterialTheme.typography.titleSmall)
        if (candidates.isEmpty()) {
            Text("No compatible USB interfaces found.")
        } else {
            candidates.forEach { candidate ->
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelect(candidate) },
                ) {
                    Text(if (candidate == selected) "Selected: ${candidate.label}" else candidate.label)
                }
            }
        }
    }
}

@Composable
fun ResponseCard(response: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Response", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(response.ifBlank { "-" }, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun LogCard(log: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Log", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            LazyColumn(modifier = Modifier.height(140.dp)) {
                items(log) { line -> Text(line, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
