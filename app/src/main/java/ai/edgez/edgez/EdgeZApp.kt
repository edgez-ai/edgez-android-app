package ai.edgez.edgez

import android.os.Handler
import android.os.Looper
import android.content.SharedPreferences
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.ble.EdgezBleClient
import ai.edgez.edgez.usb.EdgezUsbClient
import ai.edgez.edgez.usb.HaLowInterfaceStatus
import ai.edgez.edgez.usb.NETWORK_OPERATION_ACK
import ai.edgez.edgez.usb.PacketMime
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val RECONNECT_DELAY_MS = 2_000L
private const val TAG_USERS = "EdgeZUsers"
private const val HALOW_BROADCAST_NODE_48 = 0xffffffffffffL
private const val HALOW_BROADCAST_NODE_32 = 0xffffffffL
private const val RELIABLE_ACK_TIMEOUT_MS = 800L
private const val RELIABLE_MAX_ATTEMPTS = 5
private data class MessageUuid(
    val high: Long,
    val low: Long,
    val text: String,
)

private data class ReliablePacketKey(
    val messageIdHigh: Long,
    val messageIdLow: Long,
    val sequence: Int,
)

private data class PendingReliablePacket(
    val key: ReliablePacketKey,
    val conversationKey: String,
    val messageUuid: String,
    val send: () -> Result<String>,
    val attempts: AtomicInteger = AtomicInteger(0),
    val failed: AtomicBoolean = AtomicBoolean(false),
)

private fun formatMessageUuid(high: Long, low: Long): String {
    if (high == 0L && low == 0L) return ""
    val highText = "%016x".format(high)
    val lowText = "%016x".format(low)
    return "${highText.substring(0, 8)}-${highText.substring(8, 12)}-${highText.substring(12, 16)}-" +
        "${lowText.substring(0, 4)}-${lowText.substring(4, 16)}"
}

private fun parseMessageUuid(uuid: String): MessageUuid? {
    val parsed = runCatching { UUID.fromString(uuid) }.getOrNull() ?: return null
    return MessageUuid(
        high = parsed.mostSignificantBits,
        low = parsed.leastSignificantBits,
        text = parsed.toString(),
    )
}

private fun newMessageUuid(): MessageUuid {
    val uuid = UUID.randomUUID()
    return MessageUuid(
        high = uuid.mostSignificantBits,
        low = uuid.leastSignificantBits,
        text = uuid.toString(),
    )
}

private fun conversationKey(user: HaLowUser): String = user.userUuid.ifBlank { user.nodeNum.toString() }

private fun packetUserUuid(high: Long, low: Long): String = formatMessageUuid(high, low)

private data class PendingVoiceMessage(
    val durationMs: Long,
    val codec: Int,
    val chunks: Array<ByteArray?>,
) {
    fun put(index: Int, bytes: ByteArray) {
        if (index in chunks.indices) {
            chunks[index] = bytes
        }
    }

    fun complete(): Boolean = chunks.all { it != null }

    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        chunks.forEach { out.write(it ?: ByteArray(0)) }
        return out.toByteArray()
    }
}

@PreviewScreenSizes
@Composable
fun EdgeZApp() {
    val context = LocalContext.current
    val usbClient = remember { EdgezUsbClient(context.applicationContext) }
    val bleClient = remember { EdgezBleClient(context.applicationContext) }
    val lastConnectionPreferences = remember { LastConnectionPreferences(context.applicationContext) }
    val connectionPrefs = remember { context.applicationContext.getSharedPreferences("edgez_connection", Context.MODE_PRIVATE) }
    var mapCursorMarker by remember { mutableStateOf(lastConnectionPreferences.getUserMarker()) }
    val edgeZDatabase = remember { EdgeZDatabase(context.applicationContext) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val haLowInitExecutor = remember { Executors.newSingleThreadExecutor() }
    val reconnectExecutor = remember { Executors.newSingleThreadExecutor() }
    val beaconExecutor = remember { Executors.newSingleThreadExecutor() }
    val reliableExecutor = remember { Executors.newSingleThreadScheduledExecutor() }
    val pendingHaLowInitKey = remember { AtomicReference<String?>(null) }
    val pendingVoiceMessages = remember { mutableMapOf<String, PendingVoiceMessage>() }
    val pendingReliablePackets = remember { ConcurrentHashMap<ReliablePacketKey, PendingReliablePacket>() }
    val receivedConversationPackets = remember {
        Collections.newSetFromMap(ConcurrentHashMap<ReliablePacketKey, Boolean>())
    }
    val reconnectRequested = remember { AtomicReference<ActiveConnection?>(null) }
    val reconnectAttemptRunning = remember { AtomicBoolean(false) }
    val shuttingDown = remember { AtomicBoolean(false) }
    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    var mapCameraLatitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var mapCameraLongitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var mapCameraZoom by rememberSaveable { mutableStateOf<Int?>(null) }
    var activeConnection by rememberSaveable { mutableStateOf(ActiveConnection.NONE) }
    var haLowStatus by remember { mutableStateOf<HaLowInterfaceStatus?>(null) }
    var haLowUsers by remember { mutableStateOf(edgeZDatabase.getUsers()) }
    var selectedConversationUser by remember { mutableStateOf<HaLowUser?>(null) }
    var conversations by remember { mutableStateOf(edgeZDatabase.getMessages()) }
    var shareLocation by rememberSaveable { mutableStateOf(lastConnectionPreferences.getShareLocation()) }

    fun resetHaLowInitTrigger() {
        pendingHaLowInitKey.set(null)
    }

    fun clearReconnect() {
        reconnectRequested.set(null)
        reconnectAttemptRunning.set(false)
    }

    fun updateOutgoingMessageStatus(conversationKey: String, messageUuid: String, status: String) {
        if (messageUuid.isBlank()) return
        edgeZDatabase.updateMessageStatusByUuid(conversationKey, messageUuid, status)
        conversations = conversations + (
            conversationKey to ((conversations[conversationKey] ?: emptyList()).map {
                if (it.mine && it.messageUuid == messageUuid) {
                    it.copy(status = status)
                } else {
                    it
                }
            })
            )
    }

    fun pendingReliableMessageKeys(messageIdHigh: Long, messageIdLow: Long): List<ReliablePacketKey> {
        return pendingReliablePackets.keys.filter {
            it.messageIdHigh == messageIdHigh && it.messageIdLow == messageIdLow
        }
    }

    fun failReliableMessage(packet: PendingReliablePacket, reason: String) {
        if (!packet.failed.compareAndSet(false, true)) return
        pendingReliableMessageKeys(packet.key.messageIdHigh, packet.key.messageIdLow).forEach {
            pendingReliablePackets.remove(it)
        }
        mainHandler.post {
            updateOutgoingMessageStatus(packet.conversationKey, packet.messageUuid, "Delivery failed: $reason")
        }
    }

    fun transmitReliablePacket(key: ReliablePacketKey): Result<String> {
        val packet = pendingReliablePackets[key] ?: return Result.success("Already acknowledged")
        val attempt = packet.attempts.incrementAndGet()
        val result = packet.send()
        if (result.isFailure) {
            Log.w(TAG_USERS, "conversation send attempt failed messageId=${packet.messageUuid} seq=${key.sequence} attempt=$attempt", result.exceptionOrNull())
        } else {
            Log.d(TAG_USERS, "conversation send attempt queued messageId=${packet.messageUuid} seq=${key.sequence} attempt=$attempt")
        }
        reliableExecutor.schedule({
            val pending = pendingReliablePackets[key] ?: return@schedule
            if (pending.attempts.get() >= RELIABLE_MAX_ATTEMPTS) {
                failReliableMessage(pending, "no ACK after ${RELIABLE_MAX_ATTEMPTS} attempts")
            } else {
                transmitReliablePacket(key)
            }
        }, RELIABLE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result
    }

    fun sendReliableConversationPacket(
        key: ReliablePacketKey,
        conversationKey: String,
        messageUuid: String,
        send: () -> Result<String>,
    ): Result<String> {
        pendingReliablePackets[key] = PendingReliablePacket(
            key = key,
            conversationKey = conversationKey,
            messageUuid = messageUuid,
            send = send,
        )
        return transmitReliablePacket(key)
    }

    fun clearReliablePackets() {
        pendingReliablePackets.clear()
        receivedConversationPackets.clear()
    }

    fun markTransportConnected(connection: ActiveConnection) {
        clearReconnect()
        clearReliablePackets()
        activeConnection = connection
        haLowStatus = null
        selectedConversationUser = null
        resetHaLowInitTrigger()
        lastConnectionPreferences.setLastSuccessfulConnection(connection)
        EdgeZBeaconRunner.setActiveConnection(connection)
        EdgeZBeaconRunner.setHaLowStatus(null)
        when (connection) {
            ActiveConnection.USB -> {
                bleClient.close()
            }
            ActiveConnection.BLE -> usbClient.close()
            ActiveConnection.NONE -> Unit
        }
        if (connection != ActiveConnection.NONE) {
            BleForegroundService.start(context.applicationContext)
        }
    }

    fun scheduleReconnect(connection: ActiveConnection) {
        if (connection == ActiveConnection.NONE || shuttingDown.get()) return
        reconnectRequested.set(connection)
        mainHandler.postDelayed({
            if (shuttingDown.get() || reconnectRequested.get() != connection || activeConnection != ActiveConnection.NONE) {
                return@postDelayed
            }
            if (!reconnectAttemptRunning.compareAndSet(false, true)) {
                return@postDelayed
            }

            reconnectExecutor.execute {
                val connected = when (connection) {
                    ActiveConnection.USB -> {
                        usbClient.scan()
                            .firstOrNull { usbClient.hasPermission(it.device) }
                            ?.let { candidate ->
                                usbClient.connect(candidate).startsWith("Connected")
                            } ?: false
                    }
                    ActiveConnection.BLE -> {
                        if (!bleClient.hasPermissions()) {
                            false
                        } else {
                            val didStartConnect = AtomicBoolean(false)
                            val scanStarted = bleClient.startScan { candidate ->
                                if (didStartConnect.compareAndSet(false, true)) {
                                    bleClient.stopScan()
                                    bleClient.connect(candidate)
                                }
                            }.isSuccess
                            scanStarted
                        }
                    }
                    ActiveConnection.NONE -> false
                }

                reconnectAttemptRunning.set(false)
                mainHandler.post {
                    if (shuttingDown.get() || reconnectRequested.get() != connection || activeConnection != ActiveConnection.NONE) {
                        return@post
                    }
                    if (connection == ActiveConnection.USB && connected) {
                        markTransportConnected(ActiveConnection.USB)
                    } else if (connection == ActiveConnection.BLE && connected) {
                        return@post
                    } else {
                        scheduleReconnect(connection)
                    }
                }
            }
        }, RECONNECT_DELAY_MS)
    }

    fun setTransportConnected(connection: ActiveConnection, connected: Boolean) {
        if (connected && connection != ActiveConnection.NONE) {
            markTransportConnected(connection)
        } else if (activeConnection == connection) {
            DeviceModeState.enabled = false
            clearReliablePackets()
            activeConnection = ActiveConnection.NONE
            haLowStatus = null
            selectedConversationUser = null
            resetHaLowInitTrigger()
            EdgeZBeaconRunner.setActiveConnection(ActiveConnection.NONE)
            BleForegroundService.stop(context.applicationContext)
            scheduleReconnect(connection)
        }
    }

    fun disconnectTransport(connection: ActiveConnection) {
        clearReconnect()
        when (connection) {
            ActiveConnection.USB -> usbClient.close()
            ActiveConnection.BLE -> bleClient.close()
            ActiveConnection.NONE -> Unit
        }
        if (activeConnection == connection || connection == ActiveConnection.NONE) {
            DeviceModeState.enabled = false
            clearReliablePackets()
            activeConnection = ActiveConnection.NONE
            haLowStatus = null
            selectedConversationUser = null
            resetHaLowInitTrigger()
            EdgeZBeaconRunner.setActiveConnection(ActiveConnection.NONE)
            BleForegroundService.stop(context.applicationContext)
        }
    }

    val preferenceListener = remember {
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "user_marker") {
                mapCursorMarker = lastConnectionPreferences.getUserMarker()
            }
        }
    }

    val currentSetTransportConnected by rememberUpdatedState<(ActiveConnection, Boolean) -> Unit> { connection, connected ->
        setTransportConnected(connection, connected)
    }
    val currentActiveConnection by rememberUpdatedState(activeConnection)
    DisposableEffect(Unit) {
        connectionPrefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        EdgeZBeaconRunner.attach(context.applicationContext, usbClient, bleClient)

        fun triggerHaLowInitIfNeeded(source: ActiveConnection, status: HaLowInterfaceStatus) {
            if (status.stackInitialized) {
                resetHaLowInitTrigger()
                return
            }
            if (DeviceModeState.enabled) return

            val meshId = lastConnectionPreferences.getMeshId()
            if (meshId.isBlank()) return

            val country = lastConnectionPreferences.getMeshCountry()
            val passphrase = lastConnectionPreferences.getMeshPassphrase()
            val maxHop = lastConnectionPreferences.getMeshMaxHop()
            val userIdentity = lastConnectionPreferences.getOrCreateUserIdentity()
            val initKey = "${source.name}|$country|$meshId|$passphrase|$maxHop|${userIdentity.userUuid}|${userIdentity.name}|${userIdentity.publicKey.contentHashCode()}"
            while (true) {
                val previousKey = pendingHaLowInitKey.get()
                if (previousKey == initKey) return
                if (pendingHaLowInitKey.compareAndSet(previousKey, initKey)) break
            }

            haLowInitExecutor.execute {
                val result = when (source) {
                    ActiveConnection.USB -> usbClient.sendHaLowInit(
                        country,
                        meshId,
                        passphrase,
                        userIdentity.userIdHigh,
                        userIdentity.userIdLow,
                        userIdentity.name,
                        userIdentity.publicKey,
                        maxHop,
                    )
                    ActiveConnection.BLE -> bleClient.sendHaLowInit(
                        country,
                        meshId,
                        passphrase,
                        userIdentity.userIdHigh,
                        userIdentity.userIdLow,
                        userIdentity.name,
                        userIdentity.publicKey,
                        maxHop,
                    )
                    ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                }
                if (result.isFailure) {
                    pendingHaLowInitKey.compareAndSet(initKey, null)
                }
            }
        }

        fun handleTransportFrame(source: ActiveConnection, frame: ByteArray) {
            if (source != currentActiveConnection) return
            val meshPassphrase = lastConnectionPreferences.getMeshPassphrase()
            val message = decodeHaLowSyncFrame(frame, meshPassphrase)
            val status = message?.halowStatus ?: decodeHaLowStatusFrame(frame, meshPassphrase)
            val user = message?.toHaLowUser(source.name)
            val conversationMessage = message?.conversationMessage
            val isConversationAck = message?.operation == NETWORK_OPERATION_ACK &&
                (message.messageIdHigh != 0L || message.messageIdLow != 0L)
            if (status == null && user == null && conversationMessage == null && !isConversationAck) return
            if (status != null) {
                triggerHaLowInitIfNeeded(source, status)
            }
            mainHandler.post {
                if (source == currentActiveConnection) {
                    if (status != null) {
                        haLowStatus = status
                        EdgeZBeaconRunner.setHaLowStatus(status)
                    }
                    if (user != null) {
                        val userKey = conversationKey(user)
                        val previousUser = haLowUsers.values.firstOrNull { conversationKey(it) == userKey }
                        val updatedUser = user.withFallbackLocationAndMarker(previousUser)
                        val updatedUserKey = conversationKey(updatedUser)
                        Log.d(
                            TAG_USERS,
                            "update user source=$source node=${updatedUser.nodeId} " +
                                "previousLastSeen=${previousUser?.lastSeenMs} newLastSeen=${updatedUser.lastSeenMs} " +
                                "previousLat=${previousUser?.latitude} previousLon=${previousUser?.longitude} " +
                                "newLat=${updatedUser.latitude} newLon=${updatedUser.longitude} locTs=${updatedUser.locationTimestampMs} " +
                                "previousMarker=${previousUser?.marker} beaconMarker=${user.marker} marker=${updatedUser.marker} " +
                                "selected=${selectedConversationUser?.let { conversationKey(it) } == updatedUserKey}",
                        )
                        edgeZDatabase.upsertUser(updatedUser)
                        haLowUsers = (if (previousUser != null && previousUser.nodeNum != updatedUser.nodeNum) {
                            haLowUsers - previousUser.nodeNum
                        } else {
                            haLowUsers
                        }) + (updatedUser.nodeNum to updatedUser)
                        if (selectedConversationUser?.let { conversationKey(it) } == updatedUserKey) {
                            selectedConversationUser = updatedUser
                            Log.d(TAG_USERS, "refreshed selected conversation user node=${updatedUser.nodeId}")
                        }
                    }
                    if (message != null && isConversationAck) {
                        val ackUserUuid = packetUserUuid(message.userHigh, message.userLow)
                        val ackUser = if (ackUserUuid.isNotBlank()) {
                            haLowUsers.values.firstOrNull { it.userUuid == ackUserUuid }
                                ?: user?.takeIf { it.userUuid == ackUserUuid }
                        } else {
                            haLowUsers[message.from] ?: user?.takeIf { it.nodeNum == message.from }
                        }
                        if (ackUser == null) {
                            Log.w(
                                TAG_USERS,
                                "conversation ack sender missing user=$ackUserUuid from=0x%012x messageId=%s"
                                    .format(message.from, formatMessageUuid(message.messageIdHigh, message.messageIdLow)),
                            )
                        } else {
                            val senderKey = conversationKey(ackUser)
                            val messageUuid = formatMessageUuid(message.messageIdHigh, message.messageIdLow)
                            val ackKey = ReliablePacketKey(message.messageIdHigh, message.messageIdLow, message.sequence)
                            val matchedPacket = pendingReliablePackets.remove(ackKey)
                                ?: if (message.sequence == 0) {
                                    pendingReliableMessageKeys(message.messageIdHigh, message.messageIdLow)
                                        .firstNotNullOfOrNull { pendingReliablePackets.remove(it) }
                                } else {
                                    null
                                }
                            if (message.sequence == 0) {
                                pendingReliableMessageKeys(message.messageIdHigh, message.messageIdLow).forEach {
                                    pendingReliablePackets.remove(it)
                                }
                            }
                            if (matchedPacket != null) {
                                Log.d(TAG_USERS, "conversation ack received messageId=$messageUuid seq=${message.sequence}")
                            }
                            if (matchedPacket == null || pendingReliableMessageKeys(message.messageIdHigh, message.messageIdLow).isEmpty()) {
                                updateOutgoingMessageStatus(
                                    matchedPacket?.conversationKey ?: senderKey,
                                    matchedPacket?.messageUuid ?: messageUuid,
                                    "Delivered",
                                )
                            }
                        }
                    }
                    if (message != null && conversationMessage != null && !isConversationAck) {
                        val identity = lastConnectionPreferences.getOrCreateUserIdentity()
                        val senderUserUuid = packetUserUuid(message.userHigh, message.userLow)
                        val senderUser = if (senderUserUuid.isNotBlank()) {
                            haLowUsers.values.firstOrNull { it.userUuid == senderUserUuid }
                                ?: user?.takeIf { it.userUuid == senderUserUuid }
                        } else {
                            haLowUsers[message.from] ?: user?.takeIf { it.nodeNum == message.from }
                        }
                        if (senderUser == null) {
                            Log.w(
                                TAG_USERS,
                                "conversation sender missing user=$senderUserUuid from=0x%012x to=0x%012x known=${haLowUsers.size}"
                                    .format(message.from, message.to),
                            )
                        } else {
                            val senderNodeNum = senderUser.nodeNum
                            val receivedKey = ReliablePacketKey(message.messageIdHigh, message.messageIdLow, message.sequence)
                            fun sendAckForPacket() {
                                val localNode = haLowStatus?.macAddress?.takeIf { it != 0L }
                                val directToLocal = message.to != 0L &&
                                    message.to != HALOW_BROADCAST_NODE_48 &&
                                    message.to != HALOW_BROADCAST_NODE_32
                                if (localNode != null &&
                                    directToLocal &&
                                    (message.messageIdHigh != 0L || message.messageIdLow != 0L)
                                ) {
                                    val maxHop = lastConnectionPreferences.getMeshMaxHop()
                                    beaconExecutor.execute {
                                        when (source) {
                                            ActiveConnection.USB -> usbClient.sendConversationAck(
                                                message.messageIdHigh,
                                                message.messageIdLow,
                                                localNode,
                                                message.from,
                                                maxHop,
                                                message.sequence,
                                                identity.userIdHigh,
                                                identity.userIdLow,
                                            )
                                            ActiveConnection.BLE -> bleClient.sendConversationAck(
                                                message.messageIdHigh,
                                                message.messageIdLow,
                                                localNode,
                                                message.from,
                                                maxHop,
                                                message.sequence,
                                                identity.userIdHigh,
                                                identity.userIdLow,
                                            )
                                            ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                                        }.onFailure {
                                            Log.w(TAG_USERS, "conversation ack send failed messageId=${formatMessageUuid(message.messageIdHigh, message.messageIdLow)} seq=${message.sequence}", it)
                                        }
                                    }
                                }
                            }
                            if (!receivedConversationPackets.add(receivedKey)) {
                                Log.d(TAG_USERS, "duplicate conversation packet messageId=${formatMessageUuid(message.messageIdHigh, message.messageIdLow)} seq=${message.sequence}; ack again")
                                sendAckForPacket()
                            } else {
                                reliableExecutor.schedule({
                                    receivedConversationPackets.remove(receivedKey)
                                }, 10, TimeUnit.MINUTES)
                                var shouldAck = false
                                val entry = runCatching {
                                    when (message.mime) {
                                        PacketMime.VOICE -> {
                                            val payload = decryptConversationPayload(identity, senderUser, message)
                                            val chunk = requireNotNull(decodeVoiceChunk(payload)) { "Voice chunk is malformed" }
                                            val key = "${senderNodeNum}:${chunk.groupId}"
                                            val pending = pendingVoiceMessages.getOrPut(key) {
                                                PendingVoiceMessage(
                                                    durationMs = chunk.durationMs,
                                                    codec = chunk.codec,
                                                    chunks = arrayOfNulls(chunk.totalChunks),
                                                )
                                            }
                                            pending.put(chunk.index, chunk.audio)
                                            shouldAck = true
                                            if (!pending.complete()) {
                                                null
                                            } else {
                                                pendingVoiceMessages.remove(key)
                                                val path = saveVoiceMessage(context.applicationContext, pending.bytes(), pending.codec)
                                                if (lastConnectionPreferences.getAutoReplayReceivedVoice()) {
                                                    VoiceMessagePlayer.play(path).onFailure {
                                                        Log.w(TAG_USERS, "auto replay received voice failed path=$path", it)
                                                    }
                                                }
                                                ConversationEntry(
                                                    text = "Voice message",
                                                    mine = false,
                                                    timestampMs = System.currentTimeMillis(),
                                                    mime = PacketMime.VOICE,
                                                    audioPath = path,
                                                    durationMs = pending.durationMs,
                                                    messageUuid = formatMessageUuid(message.messageIdHigh, message.messageIdLow),
                                                )
                                            }
                                        }
                                        else -> {
                                            val text = decryptConversationText(identity, senderUser, message)
                                            shouldAck = true
                                            ConversationEntry(
                                                text = text,
                                                mine = false,
                                                timestampMs = System.currentTimeMillis(),
                                                messageUuid = formatMessageUuid(message.messageIdHigh, message.messageIdLow),
                                            )
                                        }
                                    }
                                }.getOrElse {
                                    receivedConversationPackets.remove(receivedKey)
                                    Log.w(
                                        TAG_USERS,
                                        "conversation decrypt failed mime=${message.mime} from=0x%012x to=0x%012x seq=${message.sequence} payload=${message.payload.size} nonce=${conversationMessage.nonce.size} cipher=${conversationMessage.ciphertext.size}",
                                        it,
                                    )
                                    ConversationEntry(
                                        text = "Unable to decrypt message",
                                        mine = false,
                                        timestampMs = System.currentTimeMillis(),
                                        status = it.message.orEmpty(),
                                        messageUuid = formatMessageUuid(message.messageIdHigh, message.messageIdLow),
                                    )
                                }
                                if (shouldAck) {
                                    sendAckForPacket()
                                }
                                if (entry != null) {
                                    val senderKey = conversationKey(senderUser)
                                    edgeZDatabase.insertMessage(senderKey, entry)
                                    conversations = conversations + (
                                        senderKey to ((conversations[senderKey] ?: emptyList()) + entry)
                                        )
                                }
                            }
                        }
                    }
                }
            }
        }

        val removeUsbFrameListener = usbClient.addFrameListener { frame ->
            handleTransportFrame(ActiveConnection.USB, frame)
        }
        val removeBleFrameListener = bleClient.addFrameListener { frame ->
            handleTransportFrame(ActiveConnection.BLE, frame)
        }
        val removeUsbDebugListener = usbClient.addDebugListener { _ -> }
        val removeBleDebugListener = bleClient.addDebugListener { line ->
            if (line == "SERVICE ready") {
                mainHandler.post {
                    currentSetTransportConnected(ActiveConnection.BLE, true)
                }
            } else if ((line.startsWith("CONN") && line.contains("state=0")) || line == "CLOSE") {
                mainHandler.post {
                    currentSetTransportConnected(ActiveConnection.BLE, false)
                    if (currentActiveConnection == ActiveConnection.NONE && reconnectRequested.get() == ActiveConnection.BLE) {
                        scheduleReconnect(ActiveConnection.BLE)
                    }
                }
            }
        }
        onDispose {
            shuttingDown.set(true)
            clearReconnect()
            connectionPrefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            removeUsbFrameListener()
            removeBleFrameListener()
            removeUsbDebugListener()
            removeBleDebugListener()
            EdgeZBeaconRunner.setActiveConnection(ActiveConnection.NONE)
            BleForegroundService.stop(context.applicationContext)
            usbClient.close()
            bleClient.close()
            edgeZDatabase.close()
            haLowInitExecutor.shutdownNow()
            reconnectExecutor.shutdownNow()
            beaconExecutor.shutdownNow()
            reliableExecutor.shutdownNow()
        }
    }

    LaunchedEffect(Unit) {
        when (lastConnectionPreferences.getLastSuccessfulConnection()) {
            ActiveConnection.USB -> {
                usbClient.scan()
                    .firstOrNull { usbClient.hasPermission(it.device) }
                    ?.let { candidate ->
                        if (usbClient.connect(candidate).startsWith("Connected")) {
                            setTransportConnected(ActiveConnection.USB, true)
                        }
                    }
            }
            ActiveConnection.BLE -> {
                if (bleClient.hasPermissions()) {
                    val didStartConnect = AtomicBoolean(false)
                    bleClient.startScan { candidate ->
                        if (didStartConnect.compareAndSet(false, true)) {
                            bleClient.stopScan()
                            bleClient.connect(candidate)
                        }
                    }
                }
            }
            ActiveConnection.NONE -> Unit
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestination.entries.forEach { destination ->
                item(
                    icon = {
                        Icon(
                            painter = painterResource(destination.icon),
                            contentDescription = destination.label,
                        )
                    },
                    label = { Text(destination.label) },
                    selected = destination == currentDestination,
                    onClick = { currentDestination = destination },
                )
            }
        },
    ) {
        when (currentDestination) {
            AppDestination.HOME -> MapScreen(
                users = haLowUsers.values.sortedByDescending { it.lastSeenMs },
                gpsCursorMarker = mapCursorMarker,
                savedCamera = if (mapCameraLatitude != null && mapCameraLongitude != null && mapCameraZoom != null) {
                    EdgeZMapCamera(
                        latitude = mapCameraLatitude ?: 0.0,
                        longitude = mapCameraLongitude ?: 0.0,
                        zoom = mapCameraZoom ?: 0,
                    )
                } else {
                    null
                },
                onCameraChanged = { camera ->
                    mapCameraLatitude = camera.latitude
                    mapCameraLongitude = camera.longitude
                    mapCameraZoom = camera.zoom
                },
            )
            AppDestination.NODES -> {
                val conversationUser = selectedConversationUser
                if (conversationUser != null) {
                    val conversationUserKey = conversationKey(conversationUser)
                    ConversationScreen(
                        activeConnection = activeConnection,
                        user = conversationUser,
                        messages = conversations[conversationUserKey] ?: emptyList(),
                        onBack = { selectedConversationUser = null },
                        onSendMessage = { text ->
                            val identity = lastConnectionPreferences.getOrCreateUserIdentity()
                            val fromNode = haLowStatus?.macAddress?.takeIf { it != 0L }
                            if (fromNode == null) {
                                Result.failure(IllegalStateException("Local HaLow node id unavailable"))
                            } else {
                                val toNode = conversationUser.nodeNum
                                runCatching {
                                    encryptConversationText(identity, conversationUser, text, fromNode)
                                }.fold(
                                    onSuccess = { encryptedMessage ->
                                        val maxHop = lastConnectionPreferences.getMeshMaxHop()
                                        val messageUuid = newMessageUuid()
                                        val sendConnection = activeConnection
                                        val result = sendReliableConversationPacket(
                                            key = ReliablePacketKey(messageUuid.high, messageUuid.low, 0),
                                            conversationKey = conversationUserKey,
                                            messageUuid = messageUuid.text,
                                        ) {
                                            when (sendConnection) {
                                                ActiveConnection.USB -> usbClient.sendConversationMessage(encryptedMessage, fromNode, toNode, PacketMime.TEXT, maxHop, messageIdHigh = messageUuid.high, messageIdLow = messageUuid.low, userIdHigh = identity.userIdHigh, userIdLow = identity.userIdLow)
                                                ActiveConnection.BLE -> bleClient.sendConversationMessage(encryptedMessage, fromNode, toNode, PacketMime.TEXT, maxHop, messageIdHigh = messageUuid.high, messageIdLow = messageUuid.low, userIdHigh = identity.userIdHigh, userIdLow = identity.userIdLow)
                                                ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                                            }
                                        }
                                        result.onSuccess {
                                            val entry = ConversationEntry(
                                                text = text,
                                                mine = true,
                                                timestampMs = System.currentTimeMillis(),
                                                status = "Sent via ${sendConnection.name}",
                                                messageUuid = messageUuid.text,
                                            )
                                            edgeZDatabase.insertMessage(conversationUserKey, entry)
                                            conversations = conversations + (
                                                conversationUserKey to ((conversations[conversationUserKey] ?: emptyList()) + entry)
                                                )
                                        }
                                    },
                                    onFailure = { Result.failure(it) },
                                )
                            }
                        },
                        onSendVoiceMessage = { voiceBytes, durationMs, localPath, codec ->
                            val identity = lastConnectionPreferences.getOrCreateUserIdentity()
                            val fromNode = haLowStatus?.macAddress?.takeIf { it != 0L }
                            val timestampMs = System.currentTimeMillis()
                            val messageUuid = newMessageUuid()
                            val entry = ConversationEntry(
                                text = "Voice message",
                                mine = true,
                                timestampMs = timestampMs,
                                status = "Sending voice...",
                                mime = PacketMime.VOICE,
                                audioPath = localPath,
                                durationMs = durationMs,
                                messageUuid = messageUuid.text,
                            )
                            edgeZDatabase.insertMessage(conversationUserKey, entry)
                            conversations = conversations + (
                                conversationUserKey to ((conversations[conversationUserKey] ?: emptyList()) + entry)
                                )

                            fun updateVoiceStatus(nextStatus: String) {
                                edgeZDatabase.updateMessageStatus(conversationUserKey, timestampMs, localPath, nextStatus)
                                conversations = conversations + (
                                    conversationUserKey to ((conversations[conversationUserKey] ?: emptyList()).map {
                                        if (it.timestampMs == timestampMs && it.audioPath == localPath) {
                                            it.copy(status = nextStatus)
                                        } else {
                                            it
                                        }
                                    })
                                    )
                            }

                            if (fromNode == null) {
                                val error = "Voice failed: local HaLow node id unavailable"
                                updateVoiceStatus(error)
                                Result.failure(IllegalStateException(error))
                            } else {
                                val result = runCatching {
                                    val toNode = conversationUser.nodeNum
                                    val maxHop = lastConnectionPreferences.getMeshMaxHop()
                                    val groupId = timestampMs
                                    val chunks = voiceBytes.asList().chunked(VOICE_CHUNK_AUDIO_BYTES)
                                    val sendConnection = activeConnection
                                    chunks.forEachIndexed { index, chunkBytes ->
                                            val voicePayload = encodeVoiceChunk(
                                                VoiceChunk(
                                                    groupId = groupId,
                                                    durationMs = durationMs,
                                                    totalChunks = chunks.size,
                                                    index = index,
                                                    codec = codec,
                                                    audio = chunkBytes.toByteArray(),
                                                ),
                                            )
                                            val encrypted = encryptConversationPayload(identity, conversationUser, voicePayload, fromNode)
                                            val sequence = index + 1
                                            val sendResult = sendReliableConversationPacket(
                                                key = ReliablePacketKey(messageUuid.high, messageUuid.low, sequence),
                                                conversationKey = conversationUserKey,
                                                messageUuid = messageUuid.text,
                                            ) {
                                                when (sendConnection) {
                                                    ActiveConnection.USB -> usbClient.sendConversationMessage(encrypted, fromNode, toNode, PacketMime.VOICE, maxHop, sequence, messageUuid.high, messageUuid.low, identity.userIdHigh, identity.userIdLow)
                                                    ActiveConnection.BLE -> bleClient.sendConversationMessage(encrypted, fromNode, toNode, PacketMime.VOICE, maxHop, sequence, messageUuid.high, messageUuid.low, identity.userIdHigh, identity.userIdLow)
                                                    ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                                                }
                                            }
                                            sendResult.getOrThrow()
                                    }
                                }
                                result.fold(
                                    onSuccess = {
                                        updateVoiceStatus("Voice sent via ${activeConnection.name}")
                                        Result.success("Voice sent")
                                    },
                                    onFailure = {
                                        val error = "Voice failed: ${it.message ?: "send timeout"}"
                                        updateVoiceStatus(error)
                                        Result.failure(IllegalStateException(error, it))
                                    },
                                )
                            }
                        },
                        onResendVoiceMessage = { entry ->
                            val identity = lastConnectionPreferences.getOrCreateUserIdentity()
                            val fromNode = haLowStatus?.macAddress?.takeIf { it != 0L }

                            fun updateVoiceStatus(nextStatus: String) {
                                edgeZDatabase.updateMessageStatus(conversationUserKey, entry.timestampMs, entry.audioPath, nextStatus)
                                conversations = conversations + (
                                    conversationUserKey to ((conversations[conversationUserKey] ?: emptyList()).map {
                                        if (it.timestampMs == entry.timestampMs && it.audioPath == entry.audioPath) {
                                            it.copy(status = nextStatus)
                                        } else {
                                            it
                                        }
                                    })
                                    )
                            }

                            val voiceFile = File(entry.audioPath)
                            if (!voiceFile.exists()) {
                                val error = "Voice failed: local audio file missing"
                                updateVoiceStatus(error)
                                Result.failure(IllegalStateException(error))
                            } else if (fromNode == null) {
                                val error = "Voice failed: local HaLow node id unavailable"
                                updateVoiceStatus(error)
                                Result.failure(IllegalStateException(error))
                            } else {
                                updateVoiceStatus("Resending voice...")
                                val result = runCatching {
                                    val toNode = conversationUser.nodeNum
                                    val maxHop = lastConnectionPreferences.getMeshMaxHop()
                                    val voiceBytes = voiceFile.readBytes()
                                    val codec = voiceCodecFromPath(entry.audioPath)
                                    val resendMessageUuid = parseMessageUuid(entry.messageUuid) ?: newMessageUuid()
                                    val chunks = voiceBytes.asList().chunked(VOICE_CHUNK_AUDIO_BYTES)
                                    val sendConnection = activeConnection
                                    chunks.forEachIndexed { index, chunkBytes ->
                                        val voicePayload = encodeVoiceChunk(
                                            VoiceChunk(
                                                groupId = entry.timestampMs,
                                                durationMs = entry.durationMs,
                                                totalChunks = chunks.size,
                                                index = index,
                                                codec = codec,
                                                audio = chunkBytes.toByteArray(),
                                            ),
                                        )
                                        val encrypted = encryptConversationPayload(identity, conversationUser, voicePayload, fromNode)
                                        val sequence = index + 1
                                        val sendResult = sendReliableConversationPacket(
                                            key = ReliablePacketKey(resendMessageUuid.high, resendMessageUuid.low, sequence),
                                            conversationKey = conversationUserKey,
                                            messageUuid = resendMessageUuid.text,
                                        ) {
                                            when (sendConnection) {
                                                ActiveConnection.USB -> usbClient.sendConversationMessage(encrypted, fromNode, toNode, PacketMime.VOICE, maxHop, sequence, resendMessageUuid.high, resendMessageUuid.low, identity.userIdHigh, identity.userIdLow)
                                                ActiveConnection.BLE -> bleClient.sendConversationMessage(encrypted, fromNode, toNode, PacketMime.VOICE, maxHop, sequence, resendMessageUuid.high, resendMessageUuid.low, identity.userIdHigh, identity.userIdLow)
                                                ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                                            }
                                        }
                                        sendResult.getOrThrow()
                                    }
                                }
                                result.fold(
                                    onSuccess = {
                                        updateVoiceStatus("Voice sent via ${activeConnection.name}")
                                        Result.success("Voice resent")
                                    },
                                    onFailure = {
                                        val error = "Voice failed: ${it.message ?: "send timeout"}"
                                        updateVoiceStatus(error)
                                        Result.failure(IllegalStateException(error, it))
                                    },
                                )
                            }
                        },
                    )
                } else {
                    NodesScreen(
                        activeConnection = activeConnection,
                        haLowStatus = haLowStatus,
                        users = haLowUsers.values.sortedByDescending { it.lastSeenMs },
                        onRemoveNode = { user ->
                            val userKey = conversationKey(user)
                            edgeZDatabase.deleteUser(userKey)
                            haLowUsers = haLowUsers - user.nodeNum
                            conversations = conversations - userKey
                            if (selectedConversationUser?.let { conversationKey(it) } == userKey) {
                                selectedConversationUser = null
                            }
                        },
                        onOpenConversation = { user ->
                            selectedConversationUser = user
                        },
                    )
                }
            }
            AppDestination.PROFILE -> PlaceholderScreen("Profile")
            AppDestination.SETTINGS -> SettingsScreen(
                client = usbClient,
                bleClient = bleClient,
                activeConnection = activeConnection,
                shareLocation = shareLocation,
                onShareLocationChange = { enabled ->
                    shareLocation = enabled
                    lastConnectionPreferences.setShareLocation(enabled)
                },
                onTransportConnectionChange = { connection, connected ->
                    setTransportConnected(connection, connected)
                },
                onTransportDisconnect = { connection ->
                    disconnectTransport(connection)
                },
            )
        }
    }
}

private enum class AppDestination(
    val label: String,
    val icon: Int,
) {
    HOME("Home", R.drawable.ic_map),
    NODES("Nodes", R.drawable.ic_halow_mesh),
    PROFILE("Profile", R.drawable.ic_account_box),
    SETTINGS("Settings", R.drawable.ic_usb),
}

@Composable
private fun PlaceholderScreen(title: String) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Text(
            text = title,
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
