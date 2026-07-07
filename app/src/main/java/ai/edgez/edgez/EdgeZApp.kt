package ai.edgez.edgez

import android.os.Handler
import android.os.Looper
import android.content.SharedPreferences
import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.ble.EdgezBleClient
import ai.edgez.edgez.usb.EdgezUsbClient
import ai.edgez.edgez.usb.HaLowInterfaceStatus
import ai.edgez.edgez.usb.PacketMime
import ai.edgez.halow.UsbControl
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val RECONNECT_DELAY_MS = 2_000L
private const val TAG_USERS = "EdgeZUsers"
private const val HALOW_BROADCAST_NODE_48 = 0xffffffffffffL
private const val HALOW_BROADCAST_NODE_32 = 0xffffffffL
private const val VOICE_CHUNK_SEND_SPACING_MS = 120L

private fun paceVoiceChunkSend(index: Int, totalChunks: Int) {
    if (index >= totalChunks - 1) return
    try {
        Thread.sleep(VOICE_CHUNK_SEND_SPACING_MS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

private data class MessageUuid(
    val high: Long,
    val low: Long,
    val text: String,
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

private fun sortNodesByName(users: Collection<HaLowUser>): List<HaLowUser> {
    return users.sortedWith(
        compareBy<HaLowUser> { it.displayName.lowercase() }
            .thenBy { it.nodeId },
    )
}

private fun isSameConversationUser(first: HaLowUser, second: HaLowUser): Boolean {
    return first.nodeNum == second.nodeNum ||
        (first.userUuid.isNotBlank() && first.userUuid == second.userUuid)
}

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
    val messageAckExecutor = remember { Executors.newSingleThreadExecutor() }
    val pendingHaLowInitKey = remember { AtomicReference<String?>(null) }
    val pendingVoiceMessages = remember { mutableMapOf<String, PendingVoiceMessage>() }
    val reconnectRequested = remember { AtomicReference<ActiveConnection?>(null) }
    val reconnectAttemptRunning = remember { AtomicBoolean(false) }
    val shuttingDown = remember { AtomicBoolean(false) }
    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.PROFILE) }
    var mapCameraLatitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var mapCameraLongitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var mapCameraZoom by rememberSaveable { mutableStateOf<Int?>(null) }
    var activeConnection by rememberSaveable { mutableStateOf(ActiveConnection.NONE) }
    var haLowStatus by remember { mutableStateOf<HaLowInterfaceStatus?>(null) }
    var haLowUsers by remember { mutableStateOf(edgeZDatabase.getUsers()) }
    var selectedConversationUser by remember { mutableStateOf<HaLowUser?>(null) }
    var selectedNodeListFilter by rememberSaveable { mutableStateOf(NodeListFilter.USERS) }
    var provisionMode by rememberSaveable { mutableStateOf(false) }
    var conversations by remember { mutableStateOf(edgeZDatabase.getMessages()) }
    var shareLocation by rememberSaveable { mutableStateOf(lastConnectionPreferences.getShareLocation()) }
    var dashboardDeviceDisplays by remember { mutableStateOf(edgeZDatabase.getDashboardDeviceDisplays()) }

    fun resetHaLowInitTrigger() {
        pendingHaLowInitKey.set(null)
    }

    fun clearReconnect() {
        reconnectRequested.set(null)
        reconnectAttemptRunning.set(false)
    }

    fun connectSelectedBleFromPreferences(): Boolean {
        if (!lastConnectionPreferences.getBleAutoConnect() || !bleClient.hasPermissions()) return false
        val selectedAddress = lastConnectionPreferences.getSelectedBleAddress()
        if (selectedAddress.isBlank()) return false
        val didStartConnect = AtomicBoolean(false)
        return bleClient.startScan { candidate ->
            if (candidate.device.address == selectedAddress && didStartConnect.compareAndSet(false, true)) {
                bleClient.stopScan()
                bleClient.connect(candidate)
            }
        }.isSuccess
    }

    fun markTransportConnected(connection: ActiveConnection) {
        val wasActiveConnection = activeConnection == connection
        clearReconnect()
        activeConnection = connection
        if (!wasActiveConnection) {
            haLowStatus = null
            selectedConversationUser = null
            resetHaLowInitTrigger()
            EdgeZBeaconRunner.setHaLowStatus(null)
        }
        lastConnectionPreferences.setLastSuccessfulConnection(connection)
        EdgeZBeaconRunner.setActiveConnection(connection)
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
        if (connection == ActiveConnection.BLE && !lastConnectionPreferences.getBleAutoConnect()) return
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
                        connectSelectedBleFromPreferences()
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
            activeConnection = ActiveConnection.NONE
            haLowStatus = null
            selectedConversationUser = null
            resetHaLowInitTrigger()
            EdgeZBeaconRunner.setActiveConnection(ActiveConnection.NONE)
            BleForegroundService.stop(context.applicationContext)
        }
    }

    fun openDeviceProvisioning() {
        disconnectTransport(activeConnection)
        provisionMode = true
        DeviceModeState.enabled = true
        currentDestination = AppDestination.NODES
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

        fun markConversationDelivered(message: ai.edgez.edgez.usb.NetworkPacket) {
            val messageUuid = formatMessageUuid(message.messageIdHigh, message.messageIdLow)
            if (messageUuid.isBlank()) return
            val ackSenderUuid = packetUserUuid(message.userHigh, message.userLow)
            val ackSenderUser = if (ackSenderUuid.isNotBlank()) {
                haLowUsers.values.firstOrNull { it.userUuid == ackSenderUuid }
            } else {
                haLowUsers[message.from]
            }
            val conversationUserKey = ackSenderUser?.let(::conversationKey)
                ?: ackSenderUuid.takeIf { it.isNotBlank() }
                ?: message.from.takeIf { it != 0L }?.toString()
                ?: return
            edgeZDatabase.updateMessageStatusByUuid(conversationUserKey, messageUuid, "Delivered")
            conversations = conversations + (
                conversationUserKey to ((conversations[conversationUserKey] ?: emptyList()).map {
                    if (it.mine && it.messageUuid == messageUuid) {
                        it.copy(status = "Delivered")
                    } else {
                        it
                    }
                })
                )
        }

        fun sendConversationAck(source: ActiveConnection, message: ai.edgez.edgez.usb.NetworkPacket) {
            if (message.messageIdHigh == 0L && message.messageIdLow == 0L) return
            val identity = lastConnectionPreferences.getOrCreateUserIdentity()
            val fromNode = haLowStatus?.macAddress?.takeIf { it != 0L } ?: message.to.takeIf { it != 0L } ?: return
            val toNode = message.from
            if (toNode == 0L || toNode == HALOW_BROADCAST_NODE_48 || toNode == HALOW_BROADCAST_NODE_32) return
            val maxHop = lastConnectionPreferences.getMeshMaxHop()
            messageAckExecutor.execute {
                val result = when (source) {
                    ActiveConnection.USB -> usbClient.sendConversationAck(message.messageIdHigh, message.messageIdLow, fromNode, toNode, identity.userIdHigh, identity.userIdLow, maxHop)
                    ActiveConnection.BLE -> bleClient.sendConversationAck(message.messageIdHigh, message.messageIdLow, fromNode, toNode, identity.userIdHigh, identity.userIdLow, maxHop)
                    ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                }
                result.onFailure {
                    Log.w(TAG_USERS, "conversation ACK send failed messageId=${formatMessageUuid(message.messageIdHigh, message.messageIdLow)}", it)
                }
            }
        }

        fun handleTransportFrame(source: ActiveConnection, frame: ByteArray) {
            if (source != currentActiveConnection) return
            val meshPassphrase = lastConnectionPreferences.getMeshPassphrase()
            val message = decodeHaLowSyncFrame(frame, meshPassphrase)
            val status = message?.halowStatus ?: decodeHaLowStatusFrame(frame, meshPassphrase)
            val user = message?.toHaLowUser(source.name)
            val sensorData = message?.beaconSensorData()
            val conversationMessage = message?.conversationMessage
            val conversationAck = message?.let {
                val localNode = haLowStatus?.macAddress?.takeIf { node -> node != 0L }
                it.operation == UsbControl.Operation.ACKNOWLEDGE.number &&
                    it.sequence == 0 &&
                    (localNode == null || it.from != localNode)
            } == true
            if (status == null && user == null && conversationMessage == null && !conversationAck) return
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
                        val previousUser = haLowUsers.values.firstOrNull { isSameConversationUser(it, user) }
                        val updatedUser = user.withFallbackLocationAndMarker(previousUser)
                        val updatedUserKey = conversationKey(updatedUser)
                        Log.d(
                            TAG_USERS,
                            "update user source=$source node=${updatedUser.nodeId} " +
                                "previousLastSeen=${previousUser?.lastSeenMs} newLastSeen=${updatedUser.lastSeenMs} " +
                                "previousLat=${previousUser?.latitude} previousLon=${previousUser?.longitude} " +
                                "newLat=${updatedUser.latitude} newLon=${updatedUser.longitude} locTs=${updatedUser.locationTimestampMs} " +
                                "previousMarker=${previousUser?.marker} beaconMarker=${user.marker} marker=${updatedUser.marker} " +
                                "selected=${selectedConversationUser?.let { isSameConversationUser(it, updatedUser) } == true}",
                        )
                        edgeZDatabase.upsertUser(updatedUser)
                        sensorData?.let {
                            edgeZDatabase.insertSensorData(updatedUserKey, updatedUser.nodeNum, updatedUser.lastSeenMs, it)
                        }
                        haLowUsers = (if (previousUser != null && previousUser.nodeNum != updatedUser.nodeNum) {
                            haLowUsers - previousUser.nodeNum
                        } else {
                            haLowUsers
                        }) + (updatedUser.nodeNum to updatedUser)
                        if (selectedConversationUser?.let { isSameConversationUser(it, updatedUser) } == true) {
                            val previousConversationKey = selectedConversationUser?.let { conversationKey(it) }
                            if (previousConversationKey != null && previousConversationKey != updatedUserKey) {
                                val previousMessages = conversations[previousConversationKey].orEmpty()
                                val updatedMessages = conversations[updatedUserKey].orEmpty()
                                if (previousMessages.isNotEmpty()) {
                                    conversations = conversations + (updatedUserKey to (updatedMessages + previousMessages).distinctBy { it.timestampMs to it.messageUuid })
                                }
                            }
                            selectedConversationUser = updatedUser
                            Log.d(TAG_USERS, "refreshed selected conversation user node=${updatedUser.nodeId}")
                        }
                    }
                    if (message != null && conversationAck) {
                        markConversationDelivered(message)
                    }
                    if (message != null && conversationMessage != null) {
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
                                    else -> ConversationEntry(
                                        text = decryptConversationText(identity, senderUser, message),
                                        mine = false,
                                        timestampMs = System.currentTimeMillis(),
                                        messageUuid = formatMessageUuid(message.messageIdHigh, message.messageIdLow),
                                    )
                                }
                            }.getOrElse {
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
                            if (entry != null) {
                                val senderKey = conversationKey(senderUser)
                                edgeZDatabase.insertMessage(senderKey, entry)
                                conversations = conversations + (
                                    senderKey to ((conversations[senderKey] ?: emptyList()) + entry)
                                    )
                                sendConversationAck(source, message)
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
            messageAckExecutor.shutdownNow()
        }
    }

    LaunchedEffect(Unit) {
        if (lastConnectionPreferences.getBleAutoConnect()) {
            connectSelectedBleFromPreferences()
        }
    }

    val savedMapCamera = if (mapCameraLatitude != null && mapCameraLongitude != null && mapCameraZoom != null) {
        EdgeZMapCamera(
            latitude = mapCameraLatitude ?: 0.0,
            longitude = mapCameraLongitude ?: 0.0,
            zoom = mapCameraZoom ?: 0,
        )
    } else {
        null
    }
    val updateMapCamera: (EdgeZMapCamera) -> Unit = { camera ->
        mapCameraLatitude = camera.latitude
        mapCameraLongitude = camera.longitude
        mapCameraZoom = camera.zoom
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
                    onClick = {
                        if (destination != AppDestination.NODES && provisionMode) {
                            provisionMode = false
                            DeviceModeState.enabled = false
                        }
                        currentDestination = destination
                    },
                )
            }
        },
    ) {
        when (currentDestination) {
            AppDestination.MAP -> MapScreen(
                users = haLowUsers.values.sortedByDescending { it.lastSeenMs },
                gpsCursorMarker = mapCursorMarker,
                savedCamera = savedMapCamera,
                onCameraChanged = updateMapCamera,
            )
            AppDestination.NODES -> {
                val conversationUser = selectedConversationUser
                if (provisionMode) {
                    ProvisioningScreen(
                        client = usbClient,
                        bleClient = bleClient,
                        activeConnection = activeConnection,
                        edgeZDatabase = edgeZDatabase,
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
                        onProvisionCancel = {
                            provisionMode = false
                            DeviceModeState.enabled = false
                            currentDestination = AppDestination.PROFILE
                        },
                        onProvisionComplete = {
                            provisionMode = false
                            DeviceModeState.enabled = false
                        },
                    )
                } else if (conversationUser != null) {
                    val conversationUserKey = conversationKey(conversationUser)
                    val isUserConversation = conversationUser.deviceType == EdgeZDeviceType.USER ||
                        conversationUser.deviceType == EdgeZDeviceType.UNSPECIFIED
                    if (!isUserConversation) {
                        DeviceDetailScreen(
                            user = conversationUser,
                            samples = edgeZDatabase.getSensorData(conversationUserKey),
                            dashboardDisplay = dashboardDeviceDisplays[conversationUserKey]
                                ?: DashboardDeviceDisplay(deviceKey = conversationUserKey),
                            onDashboardDisplayChange = { display ->
                                edgeZDatabase.setDashboardDeviceDisplay(display)
                                dashboardDeviceDisplays = dashboardDeviceDisplays + (display.deviceKey to display)
                            },
                            onBack = { selectedConversationUser = null },
                        )
                    } else {
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
                                        val result = when (activeConnection) {
                                            ActiveConnection.USB -> usbClient.sendConversationMessage(encryptedMessage, fromNode, toNode, PacketMime.TEXT, maxHop, messageIdHigh = messageUuid.high, messageIdLow = messageUuid.low, userIdHigh = identity.userIdHigh, userIdLow = identity.userIdLow)
                                            ActiveConnection.BLE -> bleClient.sendConversationMessage(encryptedMessage, fromNode, toNode, PacketMime.TEXT, maxHop, messageIdHigh = messageUuid.high, messageIdLow = messageUuid.low, userIdHigh = identity.userIdHigh, userIdLow = identity.userIdLow)
                                            ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                                        }
                                        result.onSuccess {
                                            val entry = ConversationEntry(
                                                text = text,
                                                mine = true,
                                                timestampMs = System.currentTimeMillis(),
                                                status = "Sent via ${activeConnection.name}",
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
                                            val sendResult = when (activeConnection) {
                                                ActiveConnection.USB -> usbClient.sendConversationMessage(encrypted, fromNode, toNode, PacketMime.VOICE, maxHop, index + 1, messageUuid.high, messageUuid.low, identity.userIdHigh, identity.userIdLow)
                                                ActiveConnection.BLE -> bleClient.sendConversationMessage(encrypted, fromNode, toNode, PacketMime.VOICE, maxHop, index + 1, messageUuid.high, messageUuid.low, identity.userIdHigh, identity.userIdLow)
                                                ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                                            }
                                            sendResult.getOrThrow()
                                            paceVoiceChunkSend(index, chunks.size)
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
                                        val sendResult = when (activeConnection) {
                                            ActiveConnection.USB -> usbClient.sendConversationMessage(encrypted, fromNode, toNode, PacketMime.VOICE, maxHop, index + 1, resendMessageUuid.high, resendMessageUuid.low, identity.userIdHigh, identity.userIdLow)
                                            ActiveConnection.BLE -> bleClient.sendConversationMessage(encrypted, fromNode, toNode, PacketMime.VOICE, maxHop, index + 1, resendMessageUuid.high, resendMessageUuid.low, identity.userIdHigh, identity.userIdLow)
                                            ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                                        }
                                        sendResult.getOrThrow()
                                        paceVoiceChunkSend(index, chunks.size)
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
                    }
                } else {
                    NodesScreen(
                        users = sortNodesByName(haLowUsers.values),
                        selectedFilter = selectedNodeListFilter,
                        onSelectedFilterChange = { selectedNodeListFilter = it },
                        onCreateGroup = {
                            selectedNodeListFilter = NodeListFilter.GROUPS
                        },
                        onRemoveNode = { user ->
                            val userKey = conversationKey(user)
                            edgeZDatabase.deleteUser(userKey)
                            haLowUsers = haLowUsers - user.nodeNum
                            conversations = conversations - userKey
                            dashboardDeviceDisplays = dashboardDeviceDisplays - userKey
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
            AppDestination.PROFILE -> DashboardScreen(
                users = haLowUsers.values.sortedByDescending { it.lastSeenMs },
                sensorSamples = dashboardDeviceDisplays
                    .filterValues { it.showOnDashboard }
                    .mapValues { (_, display) -> edgeZDatabase.getSensorData(display.deviceKey) },
                dashboardDeviceDisplays = dashboardDeviceDisplays,
                gpsCursorMarker = mapCursorMarker,
                savedCamera = savedMapCamera,
                onCameraChanged = updateMapCamera,
                onOpenMap = {
                    currentDestination = AppDestination.MAP
                },
                onOpenSensorDetail = { user ->
                    selectedConversationUser = user
                    currentDestination = AppDestination.NODES
                },
                onOpenDeviceProvision = { openDeviceProvisioning() },
            )
            AppDestination.SETTINGS -> SettingsScreen(
                client = usbClient,
                bleClient = bleClient,
                activeConnection = activeConnection,
                edgeZDatabase = edgeZDatabase,
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
    PROFILE("Dashboard", R.drawable.ic_account_box),
    MAP("Map", R.drawable.ic_map),
    NODES("Nodes", R.drawable.ic_halow_mesh),
    SETTINGS("Settings", R.drawable.ic_usb),
}

@Composable
private fun DashboardScreen(
    users: List<HaLowUser>,
    sensorSamples: Map<String, List<SensorSample>>,
    dashboardDeviceDisplays: Map<String, DashboardDeviceDisplay>,
    gpsCursorMarker: String,
    savedCamera: EdgeZMapCamera?,
    onCameraChanged: (EdgeZMapCamera) -> Unit,
    onOpenMap: () -> Unit,
    onOpenSensorDetail: (HaLowUser) -> Unit,
    onOpenDeviceProvision: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val dashboardItems = users.mapNotNull { user ->
                val deviceKey = conversationKey(user)
                val display = dashboardDeviceDisplays[deviceKey]?.takeIf { it.showOnDashboard } ?: return@mapNotNull null
                DashboardDeviceItem(user, display, sensorSamples[deviceKey].orEmpty())
            }
            val compactItems = dashboardItems.filter { it.display.widget != DashboardDeviceWidget.TIME_SERIES }
            val fullWidthItems = dashboardItems.filter { it.display.widget == DashboardDeviceWidget.TIME_SERIES }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Dashboard",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Button(onClick = onOpenDeviceProvision) {
                        Icon(
                            painter = painterResource(R.drawable.ic_bluetooth),
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Provisioning")
                    }
                }
            }
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 1.dp,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MapScreen(
                            users = users,
                            gpsCursorMarker = gpsCursorMarker,
                            savedCamera = savedCamera,
                            onCameraChanged = onCameraChanged,
                            previewMode = true,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(onClick = onOpenMap),
                        )
                    }
                }
            }
            compactItems.chunked(2).forEach { rowItems ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        rowItems.forEach { dashboardItem ->
                            DashboardSensorCard(
                                item = dashboardItem,
                                modifier = Modifier.weight(1f),
                                onOpenSensorDetail = onOpenSensorDetail,
                            )
                        }
                        if (rowItems.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            fullWidthItems.forEach { dashboardItem ->
                item {
                    DashboardSensorCard(
                        item = dashboardItem,
                        onOpenSensorDetail = onOpenSensorDetail,
                    )
                }
            }
        }
    }
}

private data class DashboardDeviceItem(
    val user: HaLowUser,
    val display: DashboardDeviceDisplay,
    val samples: List<SensorSample>,
)

@Composable
private fun DashboardSensorCard(
    item: DashboardDeviceItem,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onOpenSensorDetail: (HaLowUser) -> Unit,
) {
    val sample = dashboardSampleForRange(item.samples, item.display.range)
    val compact = item.display.widget != DashboardDeviceWidget.TIME_SERIES
    val markerBackground = item.user.dashboardMarkerTintColor()?.let { markerColor ->
        lerp(MaterialTheme.colorScheme.surfaceVariant, markerColor, if (compact) 0.58f else 0.46f)
    }
        ?: MaterialTheme.colorScheme.surfaceVariant
    Card(
        modifier = modifier.clickable { onOpenSensorDetail(item.user) },
        colors = CardDefaults.cardColors(containerColor = markerBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        ) {
            if (item.display.widget == DashboardDeviceWidget.TEMP_HUMIDITY) {
                Text(
                    item.user.displayName,
                    style = MaterialTheme.typography.titleSmall,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${item.user.deviceType.label} · ${item.user.displayName}",
                            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        )
                        Text(item.display.range.label, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!compact) {
                        Text("Node ${item.user.nodeId}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (sample == null || !sample.data.hasAnyValue) {
                Text("No sensor data", style = MaterialTheme.typography.bodySmall)
            } else if (item.display.widget == DashboardDeviceWidget.TEMP_HUMIDITY) {
                if (sample.data.temperature == null && sample.data.humidity == null) {
                    Text("No temp or humidity", style = MaterialTheme.typography.bodySmall)
                } else {
                    DashboardSensorValueRow("Temp", sample.data.temperature, "°C")
                    DashboardSensorValueRow("Humidity", sample.data.humidity, "%")
                }
            } else {
                DashboardSensorValueRows(sample.data)
                Text(
                    if (item.display.widget == DashboardDeviceWidget.LATEST_VALUE) {
                        "Updated ${formatDashboardSensorAge(sample.timestampMs)}"
                    } else {
                        "${item.samples.countSamplesInRange(item.display.range)} samples"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DashboardSensorValueRows(data: EdgeZSensorData) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DashboardSensorValueRow("Temperature", data.temperature, "°C")
        DashboardSensorValueRow("Humidity", data.humidity, "%")
        DashboardSensorValueRow("Pressure", data.pressure, "hPa")
        DashboardSensorValueRow("Pass-by score", data.vibrationAverage, "")
        DashboardSensorValueRow("Altitude", data.altitude, "m")
    }
}

@Composable
private fun DashboardSensorValueRow(label: String, value: Double?, unit: String) {
    if (value == null) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            if (unit.isBlank()) formatDashboardSensorValue(value) else "${formatDashboardSensorValue(value)} $unit",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun dashboardSampleForRange(samples: List<SensorSample>, range: DashboardDeviceRange): SensorSample? {
    if (samples.isEmpty()) return null
    if (range == DashboardDeviceRange.LATEST) return samples.lastOrNull()
    val since = System.currentTimeMillis() - (range.windowMs ?: return samples.lastOrNull())
    val rangeSamples = samples.filter { it.timestampMs >= since }
    if (rangeSamples.isEmpty()) return null
    return SensorSample(
        timestampMs = rangeSamples.maxOf { it.timestampMs },
        data = EdgeZSensorData(
            latitude = rangeSamples.averageOf { it.data.latitude },
            longitude = rangeSamples.averageOf { it.data.longitude },
            altitude = rangeSamples.averageOf { it.data.altitude },
            temperature = rangeSamples.averageOf { it.data.temperature },
            humidity = rangeSamples.averageOf { it.data.humidity },
            pressure = rangeSamples.averageOf { it.data.pressure },
            vibrationAverage = rangeSamples.averageOf { it.data.vibrationAverage },
        ),
    )
}

private fun List<SensorSample>.countSamplesInRange(range: DashboardDeviceRange): Int {
    val windowMs = range.windowMs ?: return size
    val since = System.currentTimeMillis() - windowMs
    return count { it.timestampMs >= since }
}

private fun List<SensorSample>.averageOf(value: (SensorSample) -> Double?): Double? {
    val values = mapNotNull(value)
    if (values.isEmpty()) return null
    return values.sum() / values.size
}

private fun formatDashboardSensorValue(value: Double): String {
    return when {
        kotlin.math.abs(value) >= 100.0 -> "%.0f".format(value)
        kotlin.math.abs(value) >= 10.0 -> "%.1f".format(value)
        else -> "%.2f".format(value)
    }
}

private fun formatDashboardSensorAge(timestampMs: Long): String {
    val ageSeconds = ((System.currentTimeMillis() - timestampMs).coerceAtLeast(0L) / 1000L)
    return when {
        ageSeconds < 60 -> "${ageSeconds}s ago"
        ageSeconds < 3600 -> "${ageSeconds / 60}m ago"
        ageSeconds < 86400 -> "${ageSeconds / 3600}h ago"
        else -> "${ageSeconds / 86400}d ago"
    }
}
