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
import java.util.concurrent.atomic.AtomicBoolean

@PreviewScreenSizes
@Composable
fun EdgeZApp() {
    val context = LocalContext.current
    val usbClient = remember { EdgezUsbClient(context.applicationContext) }
    val bleClient = remember { EdgezBleClient(context.applicationContext) }
    val lastConnectionPreferences = remember { LastConnectionPreferences(context.applicationContext) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    var txConnection by rememberSaveable { mutableStateOf(ActiveConnection.NONE) }
    var rxConnection by rememberSaveable { mutableStateOf(ActiveConnection.NONE) }
    var usbConnected by rememberSaveable { mutableStateOf(false) }
    var bleConnected by rememberSaveable { mutableStateOf(false) }
    var firstConnectedTransport by rememberSaveable { mutableStateOf(ActiveConnection.NONE) }
    var secondConnectedTransport by rememberSaveable { mutableStateOf(ActiveConnection.NONE) }

    fun isTransportConnected(connection: ActiveConnection, nextUsbConnected: Boolean, nextBleConnected: Boolean): Boolean {
        return when (connection) {
            ActiveConnection.USB -> nextUsbConnected
            ActiveConnection.BLE -> nextBleConnected
            ActiveConnection.NONE -> false
        }
    }

    fun setTransportConnected(connection: ActiveConnection, connected: Boolean) {
        val nextUsbConnected = if (connection == ActiveConnection.USB) connected else usbConnected
        val nextBleConnected = if (connection == ActiveConnection.BLE) connected else bleConnected
        val orderedConnections = listOf(firstConnectedTransport, secondConnectedTransport)
            .filter { it != ActiveConnection.NONE && isTransportConnected(it, nextUsbConnected, nextBleConnected) }
            .toMutableList()

        if (connected && connection != ActiveConnection.NONE && !orderedConnections.contains(connection)) {
            orderedConnections += connection
        }

        usbConnected = nextUsbConnected
        bleConnected = nextBleConnected
        firstConnectedTransport = orderedConnections.getOrElse(0) { ActiveConnection.NONE }
        secondConnectedTransport = orderedConnections.getOrElse(1) { ActiveConnection.NONE }

        txConnection = firstConnectedTransport
        rxConnection = if (secondConnectedTransport != ActiveConnection.NONE) {
            secondConnectedTransport
        } else {
            firstConnectedTransport
        }

        if (connected) {
            lastConnectionPreferences.setLastSuccessfulConnection(connection)
        }
    }
    val currentSetTransportConnected by rememberUpdatedState<(ActiveConnection, Boolean) -> Unit> { connection, connected ->
        setTransportConnected(connection, connected)
    }

    DisposableEffect(Unit) {
        val removeBleDebugListener = bleClient.addDebugListener { line ->
            if (line == "SERVICE ready") {
                mainHandler.post {
                    currentSetTransportConnected(ActiveConnection.BLE, true)
                }
            } else if ((line.startsWith("CONN") && line.contains("state=0")) || line == "CLOSE") {
                mainHandler.post {
                    currentSetTransportConnected(ActiveConnection.BLE, false)
                }
            }
        }
        onDispose {
            removeBleDebugListener()
            usbClient.close()
            bleClient.close()
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
            AppDestination.HOME -> HomeScreen(
                txConnection = txConnection,
                rxConnection = rxConnection,
            )
            AppDestination.FAVORITES -> PlaceholderScreen("Favorites")
            AppDestination.PROFILE -> PlaceholderScreen("Profile")
            AppDestination.SETTINGS -> SettingsScreen(
                client = usbClient,
                bleClient = bleClient,
                txConnection = txConnection,
                rxConnection = rxConnection,
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
