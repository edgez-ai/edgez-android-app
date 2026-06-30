package ai.edgez.edgez

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.ui.theme.EdgeZTheme

@Composable
fun ConversationScreen(
    activeConnection: ActiveConnection,
    user: HaLowUser,
    messages: List<ConversationEntry>,
    onBack: () -> Unit,
    onSendMessage: (String) -> Result<String>,
) {
    var draft by rememberSaveable(user.nodeNum) { mutableStateOf("") }
    var status by rememberSaveable(user.nodeNum) { mutableStateOf("") }
    val context = LocalContext.current
    val canSend = activeConnection != ActiveConnection.NONE && user.publicKey.size == 32 && draft.isNotBlank()

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("Back")
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (user.hasLocation()) {
                        IconButton(onClick = { context.openUserLocationInMap(user) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_location),
                                contentDescription = "Open location in map",
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(user.displayName, style = MaterialTheme.typography.titleLarge)
                        Text("Node ${user.nodeId}", style = MaterialTheme.typography.bodySmall)
                        Text("User ${user.userIdText}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text(
                text = if (user.publicKey.size == 32) {
                    "Encrypted with ECDH + AES-GCM"
                } else {
                    "Waiting for this user's public key"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (user.publicKey.size == 32) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            text = "No messages yet",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(messages) { message ->
                    ConversationBubble(message)
                }
            }

            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Message") },
                    minLines = 1,
                    maxLines = 4,
                )
                Button(
                    enabled = canSend,
                    onClick = {
                        val messageText = draft.trim()
                        val result = onSendMessage(messageText)
                        if (result.isSuccess) {
                            draft = ""
                            status = result.getOrNull().orEmpty()
                        } else {
                            status = result.exceptionOrNull()?.message ?: "Send failed"
                        }
                    },
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun ConversationBubble(message: ConversationEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.mine) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
                if (message.status.isNotBlank()) {
                    Text(message.status, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConversationPreview() {
    EdgeZTheme {
        ConversationScreen(
            activeConnection = ActiveConnection.USB,
            user = HaLowUser(
                nodeNum = 0x1f7e6325,
                shortName = "Sams",
                longName = "Samsung",
                route = "BLE",
                lastSeenMs = 0,
                publicKey = ByteArray(32),
            ),
            messages = listOf(
                ConversationEntry("Hello", mine = false, timestampMs = 0),
                ConversationEntry("Hi", mine = true, timestampMs = 0, status = "Sent"),
            ),
            onBack = {},
            onSendMessage = { Result.success("Sent") },
        )
    }
}
