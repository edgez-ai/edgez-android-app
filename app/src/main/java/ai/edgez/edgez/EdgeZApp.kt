package ai.edgez.edgez

import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val RECONNECT_DELAY_MS = 2_000L
private const val HALOW_BEACON_INTERVAL_MS = 30_000L

@PreviewScreenSizes
@Composable
fun EdgeZApp() {
    val context = LocalContext.current
    val usbClient = remember { EdgezUsbClient(context.applicationContext) }
    val bleClient = remember { EdgezBleClient(context.applicationContext) }
    val lastConnectionPreferences = remember { LastConnectionPreferences(context.applicationContext) }
    val edgeZDatabase = remember { EdgeZDatabase(context.applicationContext) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val haLowInitExecutor = remember { Executors.newSingleThreadExecutor() }
    val reconnectExecutor = remember { Executors.newSingleThreadExecutor() }
    val beaconExecutor = remember { Executors.newSingleThreadExecutor() }
    val pendingHaLowInitKey = remember { AtomicReference<String?>(null) }
    val reconnectRequested = remember { AtomicReference<ActiveConnection?>(null) }
    val reconnectAttemptRunning = remember { AtomicBoolean(false) }
    val shuttingDown = remember { AtomicBoolean(false) }
    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    var activeConnection by rememberSaveable { mutableStateOf(ActiveConnection.NONE) }
    var haLowStatus by remember { mutableStateOf<HaLowInterfaceStatus?>(null) }
    var haLowUsers by remember { mutableStateOf(edgeZDatabase.getUsers()) }
    var selectedConversationUser by remember { mutableStateOf<HaLowUser?>(null) }
    var conversations by remember { mutableStateOf(edgeZDatabase.getMessages()) }

    fun resetHaLowInitTrigger() {
        pendingHaLowInitKey.set(null)
    }

    fun clearReconnect() {
        reconnectRequested.set(null)
        reconnectAttemptRunning.set(false)
    }

    fun markTransportConnected(connection: ActiveConnection) {
        clearReconnect()
        activeConnection = connection
        haLowStatus = null
        selectedConversationUser = null
        resetHaLowInitTrigger()
        lastConnectionPreferences.setLastSuccessfulConnection(connection)
        when (connection) {
            ActiveConnection.USB -> bleClient.close()
            ActiveConnection.BLE -> usbClient.close()
            ActiveConnection.NONE -> Unit
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
            activeConnection = ActiveConnection.NONE
            haLowStatus = null
            selectedConversationUser = null
            resetHaLowInitTrigger()
            scheduleReconnect(connection)
        }
    }
    val currentSetTransportConnected by rememberUpdatedState<(ActiveConnection, Boolean) -> Unit> { connection, connected ->
        setTransportConnected(connection, connected)
    }
    val currentActiveConnection by rememberUpdatedState(activeConnection)
    val currentHaLowStatus by rememberUpdatedState(haLowStatus)

    DisposableEffect(Unit) {
        fun triggerHaLowInitIfNeeded(source: ActiveConnection, status: HaLowInterfaceStatus) {
            if (status.stackInitialized) {
                resetHaLowInitTrigger()
                return
            }

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
            val message = decodeHaLowSyncFrame(frame)
            val status = message?.halowStatus ?: decodeHaLowStatusFrame(frame)
            val user = message?.toHaLowUser(source.name)
            val conversationMessage = message?.conversationMessage
            if (status == null && user == null && conversationMessage == null) return
            if (status != null) {
                triggerHaLowInitIfNeeded(source, status)
            }
            mainHandler.post {
                if (source == currentActiveConnection) {
                    if (status != null) {
                        haLowStatus = status
                    }
                    if (user != null) {
                        edgeZDatabase.upsertUser(user)
                        haLowUsers = haLowUsers + (user.nodeNum to user)
                    }
                    if (conversationMessage != null) {
                        val senderNodeNum = conversationMessage.senderUserId and 0xffffffffL
                        val identity = lastConnectionPreferences.getOrCreateUserIdentity()
                        if (conversationMessage.recipientUserId == (identity.userIdLow and 0xffffffffL)) {
                            val entry = runCatching {
                                ConversationEntry(
                                    text = decryptConversationText(identity, conversationMessage),
                                    mine = false,
                                    timestampMs = System.currentTimeMillis(),
                                )
                            }.getOrElse {
                                ConversationEntry(
                                    text = "Unable to decrypt message",
                                    mine = false,
                                    timestampMs = System.currentTimeMillis(),
                                    status = it.message.orEmpty(),
                                )
                            }
                            edgeZDatabase.insertMessage(senderNodeNum, entry)
                            conversations = conversations + (
                                senderNodeNum to ((conversations[senderNodeNum] ?: emptyList()) + entry)
                                )
                        }
                    }
                }
            }
        }

        val beaconRunnable = object : Runnable {
            override fun run() {
                if (shuttingDown.get()) return
                val source = currentActiveConnection
                val status = currentHaLowStatus
                if (source != ActiveConnection.NONE && status != null && status.supported && status.stackInitialized && status.meshMode) {
                    val userIdentity = lastConnectionPreferences.getOrCreateUserIdentity()
                    beaconExecutor.execute {
                        when (source) {
                            ActiveConnection.USB -> usbClient.sendHaLowBeacon(
                                userIdentity.userIdHigh,
                                userIdentity.userIdLow,
                                userIdentity.name,
                                userIdentity.publicKey,
                            )
                            ActiveConnection.BLE -> bleClient.sendHaLowBeacon(
                                userIdentity.userIdHigh,
                                userIdentity.userIdLow,
                                userIdentity.name,
                                userIdentity.publicKey,
                            )
                            ActiveConnection.NONE -> Unit
                        }
                    }
                }
                mainHandler.postDelayed(this, HALOW_BEACON_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(beaconRunnable, HALOW_BEACON_INTERVAL_MS)

        val removeUsbFrameListener = usbClient.addFrameListener { frame ->
            handleTransportFrame(ActiveConnection.USB, frame)
        }
        val removeBleFrameListener = bleClient.addFrameListener { frame ->
            handleTransportFrame(ActiveConnection.BLE, frame)
        }
        val removeUsbDebugListener = usbClient.addDebugListener { line ->
            if (line.startsWith("USB RX error")) {
                mainHandler.post {
                    currentSetTransportConnected(ActiveConnection.USB, false)
                }
            }
        }
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
            removeUsbFrameListener()
            removeBleFrameListener()
            removeUsbDebugListener()
            removeBleDebugListener()
            mainHandler.removeCallbacks(beaconRunnable)
            usbClient.close()
            bleClient.close()
            edgeZDatabase.close()
            haLowInitExecutor.shutdownNow()
            reconnectExecutor.shutdownNow()
            beaconExecutor.shutdownNow()
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
            AppDestination.HOME -> {
                val conversationUser = selectedConversationUser
                if (conversationUser != null) {
                    ConversationScreen(
                        activeConnection = activeConnection,
                        user = conversationUser,
                        messages = conversations[conversationUser.nodeNum] ?: emptyList(),
                        onBack = { selectedConversationUser = null },
                        onSendMessage = { text ->
                            val identity = lastConnectionPreferences.getOrCreateUserIdentity()
                            runCatching {
                                encryptConversationText(identity, conversationUser, text)
                            }.fold(
                                onSuccess = { encryptedMessage ->
                                    val result = when (activeConnection) {
                                        ActiveConnection.USB -> usbClient.sendConversationMessage(encryptedMessage)
                                        ActiveConnection.BLE -> bleClient.sendConversationMessage(encryptedMessage)
                                        ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                                    }
                                    result.onSuccess {
                                        val entry = ConversationEntry(
                                            text = text,
                                            mine = true,
                                            timestampMs = System.currentTimeMillis(),
                                            status = "Sent via ${activeConnection.name}",
                                        )
                                        edgeZDatabase.insertMessage(conversationUser.nodeNum, entry)
                                        conversations = conversations + (
                                            conversationUser.nodeNum to ((conversations[conversationUser.nodeNum] ?: emptyList()) + entry)
                                            )
                                    }
                                },
                                onFailure = { Result.failure(it) },
                            )
                        },
                    )
                } else {
                    HomeScreen(
                        activeConnection = activeConnection,
                        haLowStatus = haLowStatus,
                        users = haLowUsers.values.sortedByDescending { it.lastSeenMs },
                        onRemoveNode = { user ->
                            edgeZDatabase.deleteUser(user.nodeNum)
                            haLowUsers = haLowUsers - user.nodeNum
                            conversations = conversations - user.nodeNum
                            if (selectedConversationUser?.nodeNum == user.nodeNum) {
                                selectedConversationUser = null
                            }
                        },
                        onOpenConversation = { user ->
                            selectedConversationUser = user
                        },
                    )
                }
            }
            AppDestination.FAVORITES -> PlaceholderScreen("Favorites")
            AppDestination.PROFILE -> PlaceholderScreen("Profile")
            AppDestination.SETTINGS -> SettingsScreen(
                client = usbClient,
                bleClient = bleClient,
                activeConnection = activeConnection,
                onTransportConnectionChange = { connection, connected ->
                    setTransportConnected(connection, connected)
                },
            )
        }
    }
}

private enum class AppDestination(
    val label: String,
    val icon: Int,
) {
    HOME("Home", R.drawable.ic_home),
    FAVORITES("Favorites", R.drawable.ic_favorite),
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
