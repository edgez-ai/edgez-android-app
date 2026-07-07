package ai.edgez.edgez

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.content.SharedPreferences
import android.content.Context
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import ai.edgez.edgez.ble.EdgezBleClient
import ai.edgez.edgez.usb.EdgezUsbClient
import ai.edgez.edgez.usb.EdgezUsbControlProto
import ai.edgez.edgez.usb.HaLowInterfaceStatus
import ai.edgez.edgez.usb.PacketMime
import ai.edgez.halow.UsbControl
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private const val RECONNECT_DELAY_MS = 2_000L
private const val TAG_USERS = "EdgeZUsers"
private const val HALOW_BROADCAST_NODE_48 = 0xffffffffffffL
private const val HALOW_BROADCAST_NODE_32 = 0xffffffffL
private const val VOICE_CHUNK_SEND_SPACING_MS = 120L
private val GROUP_RANDOM = SecureRandom()

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

private data class MessageUuidPair(
    val high: Long,
    val low: Long,
)

private fun parseMessageUuidParts(userUuid: String): MessageUuidPair? {
    val parsed = runCatching { UUID.fromString(userUuid) }.getOrNull() ?: return null
    return MessageUuidPair(
        high = parsed.mostSignificantBits,
        low = parsed.leastSignificantBits,
    )
}

private fun conversationGroupId(user: HaLowUser): MessageUuidPair? {
    if (user.deviceType != EdgeZDeviceType.GROUP) return null
    return parseMessageUuidParts(user.userUuid)
}

private fun conversationKey(user: HaLowUser): String = user.userUuid.ifBlank { user.nodeNum.toString() }

private fun isUserNode(user: HaLowUser): Boolean {
    return user.deviceType == EdgeZDeviceType.USER || user.deviceType == EdgeZDeviceType.UNSPECIFIED
}

private fun isConversationNode(user: HaLowUser): Boolean {
    return isUserNode(user) || user.deviceType == EdgeZDeviceType.GROUP
}

private fun newGroupPsk(): ByteArray = ByteArray(32).also(GROUP_RANDOM::nextBytes)

private fun newGroupNodeNum(existingNodeNums: Set<Long>): Long {
    while (true) {
        val candidate = GROUP_RANDOM.nextLong() and 0xffffffffffffL
        if (candidate != 0L &&
            candidate != HALOW_BROADCAST_NODE_48 &&
            candidate != HALOW_BROADCAST_NODE_32 &&
            candidate !in existingNodeNums
        ) {
            return candidate
        }
    }
}

private fun createGroupNode(name: String, existingNodeNums: Set<Long>): HaLowUser {
    val groupUuid = UUID.randomUUID()
    val groupName = name.ifBlank { "Group" }.take(64)
    return HaLowUser(
        nodeNum = newGroupNodeNum(existingNodeNums),
        userId = groupUuid.leastSignificantBits,
        userUuid = groupUuid.toString(),
        shortName = groupName.take(4),
        longName = groupName,
        route = "LOCAL",
        lastSeenMs = System.currentTimeMillis(),
        publicKey = newGroupPsk(),
        deviceType = EdgeZDeviceType.GROUP,
    )
}

private fun normalizeDashboardWidgetOrder(
    savedOrder: List<String>,
    visibleKeys: Collection<String>,
): List<String> {
    val visibleSet = visibleKeys.toSet()
    return savedOrder.filter { it in visibleSet } + visibleKeys.filter { it !in savedOrder }
}

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
    val libp2pExecutor = remember { Executors.newSingleThreadExecutor() }
    val pendingHaLowInitKey = remember { AtomicReference<String?>(null) }
    val pendingVoiceMessages = remember { mutableMapOf<String, PendingVoiceMessage>() }
    var libp2pBridgeHolder by remember { mutableStateOf<Libp2pMeshBridge?>(null) }
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
    var conversations by remember { mutableStateOf<Map<String, List<ConversationEntry>>>(emptyMap()) }
    var loadedConversationKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var shareLocation by rememberSaveable { mutableStateOf(lastConnectionPreferences.getShareLocation()) }
    var dashboardDeviceDisplays by remember { mutableStateOf(edgeZDatabase.getDashboardDeviceDisplays()) }
    var dashboardWidgetOrder by remember { mutableStateOf(lastConnectionPreferences.getDashboardWidgetOrder()) }

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

    fun loadLatestMessages(user: HaLowUser) {
        val userKey = conversationKey(user)
        if (userKey in loadedConversationKeys) return
        val cachedMessages = conversations[userKey].orEmpty()
        val latestMessages = edgeZDatabase.getMessages(userKey)
        conversations = conversations + (
            userKey to (latestMessages + cachedMessages)
                .distinctBy { Triple(it.timestampMs, it.messageUuid, it.mine) }
                .sortedBy { it.timestampMs }
            )
        loadedConversationKeys = loadedConversationKeys + userKey
    }

    fun loadOlderMessages(user: HaLowUser) {
        val userKey = conversationKey(user)
        val currentMessages = conversations[userKey].orEmpty()
        val beforeTimestampMs = currentMessages.firstOrNull()?.timestampMs ?: return
        val olderMessages = edgeZDatabase.getMessages(userKey, beforeTimestampMs = beforeTimestampMs)
        if (olderMessages.isEmpty()) return
        conversations = conversations + (
            userKey to (olderMessages + currentMessages).distinctBy { Triple(it.timestampMs, it.messageUuid, it.mine) }
            )
    }

    fun openConversation(user: HaLowUser) {
        loadLatestMessages(user)
        selectedConversationUser = user
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
            EdgeZBeaconRunner.publishSelfBeaconToLibp2p(context.applicationContext)
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
            } else if (
                key == "libp2p_mesh_enabled" ||
                key == "mesh_id" ||
                key == "mesh_passphrase" ||
                key == "user_private_key" ||
                key == "user_public_key"
            ) {
                libp2pBridgeHolder?.let { bridge ->
                    libp2pExecutor.execute {
                        if (lastConnectionPreferences.getLibp2pMeshEnabled()) {
                            bridge.start(bridge.configFromPreferences(lastConnectionPreferences)).onFailure {
                                Log.w(TAG_USERS, "libp2p mesh restart failed", it)
                            }
                        } else {
                            bridge.stop()
                        }
                    }
                }
            }
        }
    }

    val currentSetTransportConnected by rememberUpdatedState<(ActiveConnection, Boolean) -> Unit> { connection, connected ->
        setTransportConnected(connection, connected)
    }
    val currentActiveConnection by rememberUpdatedState(activeConnection)

    fun publishLibp2pFrame(frame: ByteArray) {
        if (frame.isEmpty() || !lastConnectionPreferences.getLibp2pMeshEnabled()) return
        val bridge = libp2pBridgeHolder ?: return
        libp2pExecutor.execute {
            bridge.publish(frame).onFailure {
                Log.w(TAG_USERS, "libp2p publish failed", it)
            }
        }
    }

    fun sendAndReplicate(
        _source: ActiveConnection,
        packet: ByteArray,
        sendAction: () -> Result<String>,
    ): Result<String> {
        val result = sendAction()
        result.onSuccess { publishLibp2pFrame(packet) }
        return result
    }

    DisposableEffect(Unit) {
        connectionPrefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        EdgeZBeaconRunner.attach(
            context = context.applicationContext,
            usbClient = usbClient,
            bleClient = bleClient,
            onFramePublished = { frame -> publishLibp2pFrame(frame) },
        )

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
            val packet = runCatching {
                EdgezUsbControlProto.encodeConversationAck(
                    message.messageIdHigh,
                    message.messageIdLow,
                    fromNode,
                    toNode,
                    identity.userIdHigh,
                    identity.userIdLow,
                    maxHop,
                )
            }
            if (packet.isFailure) {
                Log.w(TAG_USERS, "conversation ACK encode failed", packet.exceptionOrNull())
                return
            }
            messageAckExecutor.execute {
                val result = when (source) {
                    ActiveConnection.USB -> usbClient.sendConversationAck(message.messageIdHigh, message.messageIdLow, fromNode, toNode, identity.userIdHigh, identity.userIdLow, maxHop)
                    ActiveConnection.BLE -> bleClient.sendConversationAck(message.messageIdHigh, message.messageIdLow, fromNode, toNode, identity.userIdHigh, identity.userIdLow, maxHop)
                    ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                }
                result.onSuccess { publishLibp2pFrame(packet.getOrNull() ?: ByteArray(0)) }
                result.onFailure {
                    Log.w(TAG_USERS, "conversation ACK send failed messageId=${formatMessageUuid(message.messageIdHigh, message.messageIdLow)}", it)
                }
            }
        }

        fun handleMeshFrame(route: String, frame: ByteArray) {
            val meshPassphrase = lastConnectionPreferences.getMeshPassphrase()
            val message = decodeHaLowSyncFrame(frame, meshPassphrase)
                ?: EdgezUsbControlProto.decodeNetworkPacket(frame, meshPassphrase)
            val status = message?.halowStatus ?: decodeHaLowStatusFrame(frame, meshPassphrase)
            val user = message?.toHaLowUser(route)
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
                val source = currentActiveConnection
                if (source != ActiveConnection.NONE) {
                    triggerHaLowInitIfNeeded(source, status)
                }
            }
            mainHandler.post {
                if (route == "LIBP2P" || currentActiveConnection != ActiveConnection.NONE) {
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
                            "update user route=$route node=${updatedUser.nodeId} " +
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
                        val groupUser = haLowUsers.values.firstOrNull {
                            it.deviceType == EdgeZDeviceType.GROUP && it.nodeNum == message.to
                        }
                        val senderUser = groupUser ?: if (senderUserUuid.isNotBlank()) {
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
                            val senderNodeNum = if (senderUser.deviceType == EdgeZDeviceType.GROUP) message.from else senderUser.nodeNum
                            val groupMessageId = if (message.groupIdHigh != 0L || message.groupIdLow != 0L) {
                                MessageUuidPair(message.groupIdHigh, message.groupIdLow)
                            } else {
                                conversationGroupId(senderUser)
                            }
                            val entry = runCatching {
                                when (message.mime) {
                                    PacketMime.VOICE -> {
                                        val payload = decryptConversationPayload(
                                            identity,
                                            senderUser,
                                            message,
                                            groupIdHigh = groupMessageId?.high ?: 0L,
                                            groupIdLow = groupMessageId?.low ?: 0L,
                                        )
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
                                        text = decryptConversationText(
                                            identity,
                                            senderUser,
                                            message,
                                            groupIdHigh = groupMessageId?.high ?: 0L,
                                            groupIdLow = groupMessageId?.low ?: 0L,
                                        ),
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
                                if (route != "LIBP2P") {
                                    sendConversationAck(currentActiveConnection, message)
                                }
                            }
                        }
                    }
                }
            }
        }

        fun handleTransportFrame(source: ActiveConnection, frame: ByteArray) {
            if (source != currentActiveConnection) return
            handleMeshFrame(source.name, frame)
        }

        val removeUsbFrameListener = usbClient.addFrameListener { frame ->
            handleTransportFrame(ActiveConnection.USB, frame)
        }
        val removeBleFrameListener = bleClient.addFrameListener { frame ->
            handleTransportFrame(ActiveConnection.BLE, frame)
        }
        val libp2pBridge = Libp2pMeshBridge(context.applicationContext) { frame ->
            handleMeshFrame("LIBP2P", frame)
        }
        libp2pBridgeHolder = libp2pBridge
        fun syncLibp2pMesh() {
            libp2pExecutor.execute {
                if (lastConnectionPreferences.getLibp2pMeshEnabled()) {
                    libp2pBridge.start(libp2pBridge.configFromPreferences(lastConnectionPreferences)).onFailure {
                        Log.w(TAG_USERS, "libp2p mesh start failed", it)
                    }
                        .onSuccess {
                            EdgeZBeaconRunner.publishSelfBeaconToLibp2p(context.applicationContext)
                        }
                } else {
                    libp2pBridge.stop()
                }
            }
        }
        syncLibp2pMesh()
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
            libp2pExecutor.execute {
                libp2pBridge.stop()
            }
            libp2pBridgeHolder = null
            removeUsbFrameListener()
            removeBleFrameListener()
            removeUsbDebugListener()
            removeBleDebugListener()
            EdgeZBeaconRunner.setActiveConnection(ActiveConnection.NONE)
            EdgeZBeaconRunner.setFramePublishedListener(null)
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

    val savedMapCamera = if (mapCameraLatitude != null && mapCameraLongitude != null && (mapCameraZoom ?: 0) > 0) {
        EdgeZMapCamera(
            latitude = mapCameraLatitude ?: 0.0,
            longitude = mapCameraLongitude ?: 0.0,
            zoom = mapCameraZoom ?: 9,
        )
    } else {
        null
    }
    val updateMapCamera: (EdgeZMapCamera) -> Unit = { camera ->
        mapCameraLatitude = camera.latitude
        mapCameraLongitude = camera.longitude
        mapCameraZoom = camera.zoom
    }

    fun sendVoiceMessageToUser(
        conversationUser: HaLowUser,
        voiceBytes: ByteArray,
        durationMs: Long,
        localPath: String,
        codec: Int,
    ): Result<String> {
        val conversationUserKey = conversationKey(conversationUser)
        val groupId = conversationGroupId(conversationUser)
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
            return Result.failure(IllegalStateException(error))
        }

        val result = runCatching {
            val toNode = conversationUser.nodeNum
            val maxHop = lastConnectionPreferences.getMeshMaxHop()
            val voiceChunkGroupId = timestampMs
            val chunks = voiceBytes.asList().chunked(VOICE_CHUNK_AUDIO_BYTES)
            chunks.forEachIndexed { index, chunkBytes ->
                val voicePayload = encodeVoiceChunk(
                    VoiceChunk(
                        groupId = voiceChunkGroupId,
                        durationMs = durationMs,
                        totalChunks = chunks.size,
                        index = index,
                        codec = codec,
                        audio = chunkBytes.toByteArray(),
                    ),
                )
                val encrypted = encryptConversationPayload(
                    identity,
                    conversationUser,
                    voicePayload,
                    fromNode,
                    groupIdHigh = groupId?.high ?: 0L,
                    groupIdLow = groupId?.low ?: 0L,
                )
                val packet = runCatching {
                    EdgezUsbControlProto.encodeConversationMessage(
                        message = encrypted,
                        from = fromNode,
                        to = toNode,
                        mime = PacketMime.VOICE,
                        maxHop = maxHop,
                        sequence = index + 1,
                        messageIdHigh = messageUuid.high,
                        messageIdLow = messageUuid.low,
                        userIdHigh = identity.userIdHigh,
                        userIdLow = identity.userIdLow,
                        groupIdHigh = groupId?.high ?: 0L,
                        groupIdLow = groupId?.low ?: 0L,
                    )
                }.getOrElse {
                    throw it
                }
                val sendResult = when (activeConnection) {
                    ActiveConnection.USB -> usbClient.sendConversationMessage(
                        encrypted,
                        fromNode,
                        toNode,
                        PacketMime.VOICE,
                        maxHop,
                        index + 1,
                        messageIdHigh = messageUuid.high,
                        messageIdLow = messageUuid.low,
                        userIdHigh = identity.userIdHigh,
                        userIdLow = identity.userIdLow,
                        groupIdHigh = groupId?.high ?: 0L,
                        groupIdLow = groupId?.low ?: 0L,
                    )
                    ActiveConnection.BLE -> bleClient.sendConversationMessage(
                        encrypted,
                        fromNode,
                        toNode,
                        PacketMime.VOICE,
                        maxHop,
                        index + 1,
                        messageIdHigh = messageUuid.high,
                        messageIdLow = messageUuid.low,
                        userIdHigh = identity.userIdHigh,
                        userIdLow = identity.userIdLow,
                        groupIdHigh = groupId?.high ?: 0L,
                        groupIdLow = groupId?.low ?: 0L,
                    )
                    ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                }
                sendAndReplicate(activeConnection, packet) { sendResult }.getOrThrow()
                paceVoiceChunkSend(index, chunks.size)
            }
        }
        return result.fold(
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
                    val isUserConversation = isConversationNode(conversationUser)
                    if (!isUserConversation) {
                        DeviceDetailScreen(
                            user = conversationUser,
                            samples = edgeZDatabase.getSensorData(conversationUserKey),
                            dashboardDisplay = dashboardDeviceDisplays[conversationUserKey]
                                ?: DashboardDeviceDisplay(deviceKey = conversationUserKey),
                            onDashboardDisplayChange = { display ->
                                edgeZDatabase.setDashboardDeviceDisplay(display)
                                dashboardDeviceDisplays = dashboardDeviceDisplays + (display.deviceKey to display)
                                val nextOrder = if (display.showOnDashboard) {
                                    (dashboardWidgetOrder + display.deviceKey).distinct()
                                } else {
                                    dashboardWidgetOrder - display.deviceKey
                                }
                                dashboardWidgetOrder = nextOrder
                                lastConnectionPreferences.setDashboardWidgetOrder(nextOrder)
                            },
                            onBack = { selectedConversationUser = null },
                        )
                    } else {
                        ConversationScreen(
                            activeConnection = activeConnection,
                            user = conversationUser,
                            messages = conversations[conversationUserKey] ?: emptyList(),
                            onBack = { selectedConversationUser = null },
                            onLoadOlderMessages = { loadOlderMessages(conversationUser) },
                        onSendMessage = onSend@{ text ->
                            val identity = lastConnectionPreferences.getOrCreateUserIdentity()
                            val fromNode = haLowStatus?.macAddress?.takeIf { it != 0L }
                            if (fromNode == null) {
                                return@onSend Result.failure(IllegalStateException("No local node id"))
                            }
                            val toNode = conversationUser.nodeNum
                            val groupId = conversationGroupId(conversationUser)
                            val maxHop = lastConnectionPreferences.getMeshMaxHop()
                            val messageUuid = newMessageUuid()
                            val encryptedMessage = runCatching {
                                encryptConversationPayload(
                                    identity,
                                    conversationUser,
                                    text.toByteArray(Charsets.UTF_8),
                                    fromNode,
                                    groupIdHigh = groupId?.high ?: 0L,
                                    groupIdLow = groupId?.low ?: 0L,
                                )
                            }.getOrElse {
                                Log.w(TAG_USERS, "conversation text encryption failed", it)
                                return@onSend Result.failure(it)
                            }
                            val packet = runCatching {
                                EdgezUsbControlProto.encodeConversationMessage(
                                    message = encryptedMessage,
                                    from = fromNode,
                                    to = toNode,
                                    mime = PacketMime.TEXT,
                                    maxHop = maxHop,
                                    messageIdHigh = messageUuid.high,
                                    messageIdLow = messageUuid.low,
                                    userIdHigh = identity.userIdHigh,
                                    userIdLow = identity.userIdLow,
                                    groupIdHigh = groupId?.high ?: 0L,
                                    groupIdLow = groupId?.low ?: 0L,
                                )
                            }.getOrElse {
                                Log.w(TAG_USERS, "conversation text encode failed", it)
                                return@onSend Result.failure(it)
                            }
                            val sendResult = when (activeConnection) {
                                ActiveConnection.USB -> usbClient.sendConversationMessage(
                                    encryptedMessage,
                                    fromNode,
                                    toNode,
                                    PacketMime.TEXT,
                                    maxHop,
                                    messageIdHigh = messageUuid.high,
                                    messageIdLow = messageUuid.low,
                                    userIdHigh = identity.userIdHigh,
                                    userIdLow = identity.userIdLow,
                                    groupIdHigh = groupId?.high ?: 0L,
                                    groupIdLow = groupId?.low ?: 0L,
                                )
                                ActiveConnection.BLE -> bleClient.sendConversationMessage(
                                    encryptedMessage,
                                    fromNode,
                                    toNode,
                                    PacketMime.TEXT,
                                    maxHop,
                                    messageIdHigh = messageUuid.high,
                                    messageIdLow = messageUuid.low,
                                    userIdHigh = identity.userIdHigh,
                                    userIdLow = identity.userIdLow,
                                    groupIdHigh = groupId?.high ?: 0L,
                                    groupIdLow = groupId?.low ?: 0L,
                                )
                                ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                            }
                            val replicatedResult = sendAndReplicate(activeConnection, packet) { sendResult }
                            replicatedResult.onSuccess {
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
                            return@onSend replicatedResult.map { "Sent via ${activeConnection.name}" }
                        },
                            onSendVoiceMessage = { voiceBytes, durationMs, localPath, codec ->
                            sendVoiceMessageToUser(conversationUser, voiceBytes, durationMs, localPath, codec)
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
                                    val groupId = conversationGroupId(conversationUser)
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
                                        val encrypted = encryptConversationPayload(
                                            identity,
                                            conversationUser,
                                            voicePayload,
                                            fromNode,
                                            groupIdHigh = groupId?.high ?: 0L,
                                            groupIdLow = groupId?.low ?: 0L,
                                        )
                                        val packet = runCatching {
                                            EdgezUsbControlProto.encodeConversationMessage(
                                                message = encrypted,
                                                from = fromNode,
                                                to = toNode,
                                                mime = PacketMime.VOICE,
                                                maxHop = maxHop,
                                                sequence = index + 1,
                                                messageIdHigh = resendMessageUuid.high,
                                                messageIdLow = resendMessageUuid.low,
                                                userIdHigh = identity.userIdHigh,
                                                userIdLow = identity.userIdLow,
                                                groupIdHigh = groupId?.high ?: 0L,
                                                groupIdLow = groupId?.low ?: 0L,
                                            )
                                        }.getOrElse {
                                            throw it
                                        }
                                        val sendResult = when (activeConnection) {
                                            ActiveConnection.USB -> usbClient.sendConversationMessage(
                                                encrypted,
                                                fromNode,
                                                toNode,
                                                PacketMime.VOICE,
                                                maxHop,
                                                index + 1,
                                                messageIdHigh = resendMessageUuid.high,
                                                messageIdLow = resendMessageUuid.low,
                                                userIdHigh = identity.userIdHigh,
                                                userIdLow = identity.userIdLow,
                                                groupIdHigh = groupId?.high ?: 0L,
                                                groupIdLow = groupId?.low ?: 0L,
                                            )
                                            ActiveConnection.BLE -> bleClient.sendConversationMessage(
                                                encrypted,
                                                fromNode,
                                                toNode,
                                                PacketMime.VOICE,
                                                maxHop,
                                                index + 1,
                                                messageIdHigh = resendMessageUuid.high,
                                                messageIdLow = resendMessageUuid.low,
                                                userIdHigh = identity.userIdHigh,
                                                userIdLow = identity.userIdLow,
                                                groupIdHigh = groupId?.high ?: 0L,
                                                groupIdLow = groupId?.low ?: 0L,
                                            )
                                            ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                                        }
                                        sendAndReplicate(activeConnection, packet) { sendResult }.getOrThrow()
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
                        dashboardDeviceDisplays = dashboardDeviceDisplays,
                        onSelectedFilterChange = { selectedNodeListFilter = it },
                        onCreateGroup = { groupName ->
                            val group = createGroupNode(groupName, haLowUsers.keys)
                            edgeZDatabase.upsertUser(group)
                            haLowUsers = haLowUsers + (group.nodeNum to group)
                            selectedNodeListFilter = NodeListFilter.GROUPS
                            openConversation(group)
                        },
                        onToggleDashboard = { user ->
                            val userKey = conversationKey(user)
                            val currentDisplay = dashboardDeviceDisplays[userKey] ?: DashboardDeviceDisplay(deviceKey = userKey)
                            val nextDisplay = currentDisplay.copy(showOnDashboard = !currentDisplay.showOnDashboard)
                            edgeZDatabase.setDashboardDeviceDisplay(nextDisplay)
                            dashboardDeviceDisplays = dashboardDeviceDisplays + (userKey to nextDisplay)
                            val nextOrder = if (nextDisplay.showOnDashboard) {
                                (dashboardWidgetOrder + userKey).distinct()
                            } else {
                                dashboardWidgetOrder - userKey
                            }
                            dashboardWidgetOrder = nextOrder
                            lastConnectionPreferences.setDashboardWidgetOrder(nextOrder)
                        },
                        onRemoveNode = { user ->
                            val userKey = conversationKey(user)
                            edgeZDatabase.deleteUser(userKey)
                            haLowUsers = haLowUsers - user.nodeNum
                            conversations = conversations - userKey
                            loadedConversationKeys = loadedConversationKeys - userKey
                            dashboardDeviceDisplays = dashboardDeviceDisplays - userKey
                            val nextOrder = dashboardWidgetOrder - userKey
                            dashboardWidgetOrder = nextOrder
                            lastConnectionPreferences.setDashboardWidgetOrder(nextOrder)
                            if (selectedConversationUser?.let { conversationKey(it) } == userKey) {
                                selectedConversationUser = null
                            }
                        },
                        onOpenConversation = { user ->
                            openConversation(user)
                        },
                    )
                }
            }
            AppDestination.PROFILE -> DashboardScreen(
                users = haLowUsers.values.sortedByDescending { it.lastSeenMs },
                activeConnection = activeConnection,
                sensorSamples = dashboardDeviceDisplays
                    .filterValues { it.showOnDashboard }
                    .mapValues { (_, display) -> edgeZDatabase.getSensorData(display.deviceKey) },
                dashboardDeviceDisplays = dashboardDeviceDisplays,
                dashboardWidgetOrder = dashboardWidgetOrder,
                gpsCursorMarker = mapCursorMarker,
                savedCamera = savedMapCamera,
                onCameraChanged = updateMapCamera,
                onOpenMap = {
                    currentDestination = AppDestination.MAP
                },
                onOpenConversation = { user ->
                    openConversation(user)
                    currentDestination = AppDestination.NODES
                },
                onOpenSensorDetail = { user ->
                    selectedConversationUser = user
                    currentDestination = AppDestination.NODES
                },
                onSendUserVoiceMessage = { user, voiceBytes, durationMs, localPath, codec ->
                    sendVoiceMessageToUser(user, voiceBytes, durationMs, localPath, codec)
                },
                onMoveWidget = { widgetKey, direction ->
                    val visibleKeys = dashboardDeviceDisplays.filterValues { it.showOnDashboard }.keys
                    val normalizedOrder = normalizeDashboardWidgetOrder(dashboardWidgetOrder, visibleKeys)
                    val index = normalizedOrder.indexOf(widgetKey)
                    val targetIndex = (index + direction).coerceIn(0, normalizedOrder.lastIndex)
                    if (index >= 0 && index != targetIndex) {
                        val nextOrder = normalizedOrder.toMutableList().apply {
                            add(targetIndex, removeAt(index))
                        }
                        dashboardWidgetOrder = nextOrder
                        lastConnectionPreferences.setDashboardWidgetOrder(nextOrder)
                    }
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
    activeConnection: ActiveConnection,
    sensorSamples: Map<String, List<SensorSample>>,
    dashboardDeviceDisplays: Map<String, DashboardDeviceDisplay>,
    dashboardWidgetOrder: List<String>,
    gpsCursorMarker: String,
    savedCamera: EdgeZMapCamera?,
    onCameraChanged: (EdgeZMapCamera) -> Unit,
    onOpenMap: () -> Unit,
    onOpenConversation: (HaLowUser) -> Unit,
    onOpenSensorDetail: (HaLowUser) -> Unit,
    onSendUserVoiceMessage: (HaLowUser, ByteArray, Long, String, Int) -> Result<String>,
    onMoveWidget: (String, Int) -> Unit,
    onOpenDeviceProvision: () -> Unit,
) {
    var editLayoutMode by rememberSaveable { mutableStateOf(false) }
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
            val itemsByKey = dashboardItems.associateBy { it.display.deviceKey }
            val orderedItems = normalizeDashboardWidgetOrder(dashboardWidgetOrder, itemsByKey.keys)
                .mapNotNull { itemsByKey[it] }
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = onOpenDeviceProvision) {
                            Icon(
                                painter = painterResource(R.drawable.ic_bluetooth),
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Prov")
                        }
                        Button(onClick = { editLayoutMode = !editLayoutMode }) {
                            Text(if (editLayoutMode) "Done" else "Edit")
                        }
                    }
                }
            }
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
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
            var index = 0
            while (index < orderedItems.size) {
                val dashboardItem = orderedItems[index]
                if (dashboardItem.isCompactWidget) {
                    val nextItem = orderedItems.getOrNull(index + 1)?.takeIf { it.isCompactWidget }
                    item(key = listOfNotNull(dashboardItem, nextItem).joinToString("|") { it.display.deviceKey }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            DashboardWidgetCard(
                                item = dashboardItem,
                                activeConnection = activeConnection,
                                editLayoutMode = editLayoutMode,
                                modifier = Modifier.weight(1f),
                                onOpenConversation = onOpenConversation,
                                onOpenSensorDetail = onOpenSensorDetail,
                                onSendVoiceMessage = onSendUserVoiceMessage,
                                onMoveWidget = onMoveWidget,
                            )
                            if (nextItem != null) {
                                DashboardWidgetCard(
                                    item = nextItem,
                                    activeConnection = activeConnection,
                                    editLayoutMode = editLayoutMode,
                                    modifier = Modifier.weight(1f),
                                    onOpenConversation = onOpenConversation,
                                    onOpenSensorDetail = onOpenSensorDetail,
                                    onSendVoiceMessage = onSendUserVoiceMessage,
                                    onMoveWidget = onMoveWidget,
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    index += if (nextItem != null) 2 else 1
                } else {
                    item(key = dashboardItem.display.deviceKey) {
                        DashboardWidgetCard(
                            item = dashboardItem,
                            activeConnection = activeConnection,
                            editLayoutMode = editLayoutMode,
                            onOpenConversation = onOpenConversation,
                            onOpenSensorDetail = onOpenSensorDetail,
                            onSendVoiceMessage = onSendUserVoiceMessage,
                            onMoveWidget = onMoveWidget,
                        )
                    }
                    index += 1
                }
            }
        }
    }
}

private data class DashboardDeviceItem(
    val user: HaLowUser,
    val display: DashboardDeviceDisplay,
    val samples: List<SensorSample>,
) {
    val isCompactWidget: Boolean
        get() = isUserNode(user) || display.widget != DashboardDeviceWidget.TIME_SERIES
}

@Composable
private fun DashboardWidgetCard(
    item: DashboardDeviceItem,
    activeConnection: ActiveConnection,
    editLayoutMode: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onOpenConversation: (HaLowUser) -> Unit,
    onOpenSensorDetail: (HaLowUser) -> Unit,
    onSendVoiceMessage: (HaLowUser, ByteArray, Long, String, Int) -> Result<String>,
    onMoveWidget: (String, Int) -> Unit,
) {
    if (isUserNode(item.user)) {
        DashboardUserCard(
            item = item,
            activeConnection = activeConnection,
            editLayoutMode = editLayoutMode,
            modifier = modifier,
            onOpenConversation = onOpenConversation,
            onSendVoiceMessage = onSendVoiceMessage,
            onMoveWidget = onMoveWidget,
        )
    } else {
        DashboardSensorCard(
            item = item,
            editLayoutMode = editLayoutMode,
            modifier = modifier,
            onOpenSensorDetail = onOpenSensorDetail,
            onMoveWidget = onMoveWidget,
        )
    }
}

private fun Modifier.dashboardWidgetReorderInput(
    widgetKey: String,
    editLayoutMode: Boolean,
    onDragStateChange: (Boolean) -> Unit,
    onDragOffsetChange: (Offset) -> Unit,
    onMoveWidget: (String, Int) -> Unit,
): Modifier {
    if (!editLayoutMode) return this
    return pointerInput(widgetKey, editLayoutMode) {
        var reorderOffset = Offset.Zero
        fun dropWidget() {
            val dominantOffset = if (abs(reorderOffset.y) >= abs(reorderOffset.x)) {
                reorderOffset.y
            } else {
                reorderOffset.x
            }
            if (abs(dominantOffset) >= 72f) {
                val steps = (abs(dominantOffset) / 96f).toInt().coerceAtLeast(1)
                onMoveWidget(widgetKey, if (dominantOffset > 0f) steps else -steps)
            }
            reorderOffset = Offset.Zero
            onDragOffsetChange(Offset.Zero)
            onDragStateChange(false)
        }
        detectDragGesturesAfterLongPress(
            onDragStart = {
                reorderOffset = Offset.Zero
                onDragOffsetChange(Offset.Zero)
                onDragStateChange(true)
            },
            onDragCancel = {
                reorderOffset = Offset.Zero
                onDragOffsetChange(Offset.Zero)
                onDragStateChange(false)
            },
            onDragEnd = { dropWidget() },
            onDrag = { change, dragAmount ->
                change.consume()
                reorderOffset += dragAmount
                onDragOffsetChange(reorderOffset)
            },
        )
    }
}

@Composable
private fun DashboardUserCard(
    item: DashboardDeviceItem,
    activeConnection: ActiveConnection,
    editLayoutMode: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onOpenConversation: (HaLowUser) -> Unit,
    onSendVoiceMessage: (HaLowUser, ByteArray, Long, String, Int) -> Result<String>,
    onMoveWidget: (String, Int) -> Unit,
) {
    val context = LocalContext.current
    val userKey = conversationKey(item.user)
    val recorder = remember(userKey) { VoiceMessageRecorder(context.applicationContext) }
    var recording by remember(userKey) { mutableStateOf(false) }
    var status by remember(userKey) { mutableStateOf("") }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        status = if (granted) "Hold to talk" else "Microphone permission denied"
    }
    val canSendVoice = activeConnection != ActiveConnection.NONE
    val markerBackground = item.user.markerTintColor()?.let { markerColor ->
        lerp(MaterialTheme.colorScheme.surfaceVariant, markerColor, 0.40f)
    } ?: MaterialTheme.colorScheme.surfaceVariant
    var dragOffset by remember(userKey) { mutableStateOf(Offset.Zero) }
    var dragging by remember(userKey) { mutableStateOf(false) }

    Card(
        modifier = modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationX = dragOffset.x
                translationY = dragOffset.y
                shadowElevation = if (dragging) 12.dp.toPx() else 0f
                scaleX = if (dragging) 1.03f else 1f
                scaleY = if (dragging) 1.03f else 1f
            }
            .dashboardWidgetReorderInput(
                widgetKey = item.display.deviceKey,
                editLayoutMode = editLayoutMode,
                onDragStateChange = { dragging = it },
                onDragOffsetChange = { dragOffset = it },
                onMoveWidget = onMoveWidget,
            )
            .then(
                if (editLayoutMode) {
                    Modifier
                } else {
                    Modifier.pointerInput(userKey, canSendVoice) {
                        awaitEachGesture {
                            awaitFirstDown()
                            val releasedBeforeHold = withTimeoutOrNull(280L) {
                                waitForUpOrCancellation()
                            }
                            if (releasedBeforeHold != null) {
                                onOpenConversation(item.user)
                                return@awaitEachGesture
                            }
                            if (!canSendVoice) {
                                status = "Connect to send voice"
                                waitForUpOrCancellation()
                                return@awaitEachGesture
                            }
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!hasPermission) {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                waitForUpOrCancellation()
                                return@awaitEachGesture
                            }
                            val started = recorder.start()
                            if (started.isFailure) {
                                status = started.exceptionOrNull()?.message ?: "Voice record failed"
                                waitForUpOrCancellation()
                                return@awaitEachGesture
                            }
                            recording = true
                            status = "Recording"
                            val released = waitForUpOrCancellation() != null
                            recording = false
                            val voice = recorder.stop(delete = !released)
                            if (released && voice != null) {
                                val result = onSendVoiceMessage(item.user, voice.bytes, voice.durationMs, voice.path, voice.codec)
                                status = result.exceptionOrNull()?.message ?: result.getOrNull().orEmpty()
                            } else {
                                status = "Voice canceled"
                            }
                        }
                    }
                },
            ),
        colors = CardDefaults.cardColors(containerColor = markerBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.user.displayName, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                when {
                    dragging -> "Drop to place"
                    editLayoutMode -> "Long press and drag"
                    recording -> "Recording"
                    status.isNotBlank() -> status
                    canSendVoice -> "Hold to talk"
                    else -> "Connect to talk"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DashboardSensorCard(
    item: DashboardDeviceItem,
    editLayoutMode: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onOpenSensorDetail: (HaLowUser) -> Unit,
    onMoveWidget: (String, Int) -> Unit,
) {
    val sample = dashboardSampleForRange(item.samples, item.display.range)
    val compact = item.display.widget != DashboardDeviceWidget.TIME_SERIES
    val markerBackground = item.user.markerTintColor()?.let { markerColor ->
        lerp(MaterialTheme.colorScheme.surfaceVariant, markerColor, if (compact) 0.58f else 0.46f)
    }
        ?: MaterialTheme.colorScheme.surfaceVariant
    var dragOffset by remember(item.display.deviceKey) { mutableStateOf(Offset.Zero) }
    var dragging by remember(item.display.deviceKey) { mutableStateOf(false) }
    Card(
        modifier = modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationX = dragOffset.x
                translationY = dragOffset.y
                shadowElevation = if (dragging) 12.dp.toPx() else 0f
                scaleX = if (dragging) 1.03f else 1f
                scaleY = if (dragging) 1.03f else 1f
            }
            .dashboardWidgetReorderInput(
                widgetKey = item.display.deviceKey,
                editLayoutMode = editLayoutMode,
                onDragStateChange = { dragging = it },
                onDragOffsetChange = { dragOffset = it },
                onMoveWidget = onMoveWidget,
            )
            .then(
                if (editLayoutMode) {
                    Modifier
                } else {
                    Modifier.clickable { onOpenSensorDetail(item.user) }
                },
            ),
        colors = CardDefaults.cardColors(containerColor = markerBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        ) {
            if (item.display.widget == DashboardDeviceWidget.TEMP_HUMIDITY) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.user.displayName,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.user.displayName,
                            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        )
                        Text(item.display.range.label, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!compact) {
                            Text("Node ${item.user.nodeId}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (editLayoutMode) {
                Text(if (dragging) "Drop to place" else "Long press and drag", style = MaterialTheme.typography.bodySmall)
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
