package ai.edgez.edgez

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ai.edgez.edgez.ui.theme.EdgeZTheme
import ai.edgez.edgez.usb.PacketMime

@Composable
fun ConversationScreen(
    activeConnection: ActiveConnection,
    canSendOverMesh: Boolean,
    user: HaLowUser,
    messages: List<ConversationEntry>,
    onBack: () -> Unit,
    onSendMessage: (String) -> Result<String>,
    onSendVoiceMessage: (ByteArray, Long, String, Int) -> Result<String>,
    onResendVoiceMessage: (ConversationEntry) -> Result<String>,
    onLoadOlderMessages: () -> Unit,
) {
    val userKey = user.userUuid.ifBlank { user.nodeNum.toString() }
    var draft by rememberSaveable(userKey) { mutableStateOf("") }
    var status by rememberSaveable(userKey) { mutableStateOf("") }
    var recording by rememberSaveable(userKey) { mutableStateOf(false) }
    val context = LocalContext.current
    val recorder = androidx.compose.runtime.remember(userKey) { VoiceMessageRecorder(context.applicationContext) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        status = if (granted) "Hold voice to record" else "Microphone permission denied"
    }
    val hasConversationKey = user.publicKey.size == 32
    val canSend = canSendOverMesh && hasConversationKey && draft.isNotBlank()
    val canSendVoice = canSendOverMesh && hasConversationKey
    val listState = rememberLazyListState()
    var lastAutoScrolledMessageKey by rememberSaveable(userKey) { mutableStateOf("") }
    var lastOlderLoadMessageKey by rememberSaveable(userKey) { mutableStateOf("") }
    var canLoadOlder by rememberSaveable(userKey) { mutableStateOf(false) }
    val encryptionLabel = if (hasConversationKey) {
        if (user.deviceType == EdgeZDeviceType.GROUP) "Group PSK" else "ECDH"
    } else {
        "No key"
    }

    val lastMessageKey = messages.lastOrNull()?.let { "${it.timestampMs}:${it.messageUuid}:${it.mine}" }.orEmpty()
    LaunchedEffect(lastMessageKey) {
        if (lastMessageKey.isNotBlank() && lastMessageKey != lastAutoScrolledMessageKey) {
            listState.animateScrollToItem(messages.lastIndex)
            lastAutoScrolledMessageKey = lastMessageKey
            canLoadOlder = true
        }
    }

    val firstMessageKey = messages.firstOrNull()?.let { "${it.timestampMs}:${it.messageUuid}:${it.mine}" }.orEmpty()
    LaunchedEffect(firstMessageKey, listState.firstVisibleItemIndex) {
        if (canLoadOlder &&
            firstMessageKey.isNotBlank() &&
            listState.firstVisibleItemIndex == 0 &&
            firstMessageKey != lastOlderLoadMessageKey
        ) {
            lastOlderLoadMessageKey = firstMessageKey
            onLoadOlderMessages()
        }
    }

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
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConversationAvatar(user)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(user.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${user.deviceType.label} · $encryptionLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasConversationKey) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    if (user.hasLocation()) {
                        IconButton(onClick = { context.openUserLocationInMap(user) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_location),
                                contentDescription = "Open location in map",
                                tint = user.markerTintColor() ?: MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            if (!hasConversationKey) {
                Text(
                    text = if (user.deviceType == EdgeZDeviceType.GROUP) {
                        "Waiting for this group's PSK"
                    } else {
                        "Waiting for this user's public key"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            LazyColumn(
                state = listState,
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
                    ConversationBubble(
                        message = message,
                        onResendVoiceMessage = {
                            val result = onResendVoiceMessage(message)
                            status = result.exceptionOrNull()?.message ?: result.getOrNull().orEmpty()
                        },
                    )
                }
            }

            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }

            if (recording) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(
                        text = "Recording",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
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
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(canSendVoice) {
                        detectTapGestures(
                            onPress = press@{
                                if (!canSendVoice) return@press
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasPermission) {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@press
                                }
                                val started = recorder.start()
                                if (started.isFailure) {
                                    status = started.exceptionOrNull()?.message ?: "Voice record failed"
                                    return@press
                                }
                                recording = true
                                status = "Recording"
                                val released = tryAwaitRelease()
                                recording = false
                                val voice = recorder.stop(delete = !released)
                                if (released && voice != null) {
                                    val result = onSendVoiceMessage(voice.bytes, voice.durationMs, voice.path, voice.codec)
                                    status = result.exceptionOrNull()?.message ?: result.getOrNull().orEmpty()
                                } else {
                                    status = "Voice canceled"
                                }
                            },
                        )
                    },
                shape = MaterialTheme.shapes.small,
                color = when {
                    recording -> MaterialTheme.colorScheme.error
                    canSendVoice -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = when {
                    recording -> MaterialTheme.colorScheme.onError
                    canSendVoice -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Text(
                    text = when {
                        recording -> "Recording"
                        canSendVoice -> "Hold to Talk"
                        !canSendOverMesh -> "Connect to send voice"
                        else -> "Missing encryption key"
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ConversationAvatar(user: HaLowUser) {
    val markerColor = user.markerTintColor() ?: MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = markerColor,
        contentColor = Color.White,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = user.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun ConversationBubble(
    message: ConversationEntry,
    onResendVoiceMessage: () -> Unit,
) {
    val isVoice = message.mime == PacketMime.VOICE
    val isBinary = message.mime == PacketMime.BINARY
    val canResend = message.mine && isVoice && message.status.startsWith("Voice failed")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 292.dp)
                .clickable(
                    enabled = isVoice && message.audioPath.isNotBlank(),
                    onClick = { VoiceMessagePlayer.play(message.audioPath) },
                ),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (message.mine) 18.dp else 4.dp,
                bottomEnd = if (message.mine) 4.dp else 18.dp,
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (message.mine) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (message.mine) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isVoice) {
                    Text("Voice message ${formatDuration(message.durationMs)}", style = MaterialTheme.typography.bodyMedium)
                } else if (isBinary) {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium)
                }
                if (isBinary && message.audioPath.isNotBlank()) {
                    Text(
                        "Saved: ${message.audioPath.substringAfterLast('/')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (message.status.isNotBlank()) {
                    Text(
                        if (message.status == "Delivered") "Delivered" else message.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.status == "Delivered") {
                            if (message.mine) MaterialTheme.colorScheme.onPrimary else Color(0xFF16803C)
                        } else {
                            if (message.mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (canResend) {
                    TextButton(onClick = onResendVoiceMessage) {
                        Text("Resend")
                    }
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000L).coerceAtLeast(1L)
    return "$seconds\""
}

@Preview(showBackground = true)
@Composable
private fun ConversationPreview() {
    EdgeZTheme {
        ConversationScreen(
            activeConnection = ActiveConnection.USB,
            canSendOverMesh = true,
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
                ConversationEntry("Voice", mine = true, timestampMs = 0, status = "Sent", mime = PacketMime.VOICE, durationMs = 1800),
            ),
            onBack = {},
            onSendMessage = { Result.success("Sent") },
            onSendVoiceMessage = { _, _, _, _ -> Result.success("Voice sent") },
            onResendVoiceMessage = { Result.success("Voice resent") },
            onLoadOlderMessages = {},
        )
    }
}
