package ai.edgez.edgez

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.ble.BleCandidate
import ai.edgez.edgez.ble.EdgezBleClient
import ai.edgez.edgez.ui.theme.EdgeZTheme
import ai.edgez.edgez.usb.ACTION_USB_PERMISSION
import ai.edgez.edgez.usb.EDGEZ_HEADER_LEN
import ai.edgez.edgez.usb.EDGEZ_MAGIC_0
import ai.edgez.edgez.usb.EDGEZ_MAGIC_1
import ai.edgez.edgez.usb.EDGEZ_MAX_PAYLOAD
import ai.edgez.edgez.usb.EDGEZ_TYPE_CONTROL_RESP
import ai.edgez.edgez.usb.EDGEZ_TYPE_ECHO_RESP
import ai.edgez.edgez.usb.EDGEZ_TYPE_ERROR
import ai.edgez.edgez.usb.EDGEZ_VERSION
import ai.edgez.edgez.usb.EdgezUsbClient
import ai.edgez.edgez.usb.EdgezUsbControlProto
import ai.edgez.edgez.usb.USB_CONTROL_ACTION_GET_STATUS
import ai.edgez.edgez.usb.USB_CONTROL_ACTION_SET_BLE_ENABLED
import ai.edgez.edgez.usb.USB_CONTROL_ACTION_SET_PAIRING_ENABLED
import ai.edgez.edgez.usb.USB_CONTROL_ACTION_SET_WIFI_CREDENTIALS
import ai.edgez.edgez.usb.UsbCandidate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

@Composable
fun SettingsScreen(
    client: EdgezUsbClient,
    bleClient: EdgezBleClient,
    activeConnection: ActiveConnection,
    onTransportConnectionChange: (ActiveConnection, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val connectionPreferences = remember { LastConnectionPreferences(context.applicationContext) }
    var candidates by remember { mutableStateOf(client.scan()) }
    var selected by remember { mutableStateOf<UsbCandidate?>(candidates.firstOrNull()) }
    var bleCandidates by remember { mutableStateOf(listOf<BleCandidate>()) }
    var selectedBle by remember { mutableStateOf<BleCandidate?>(null) }
    var bleReady by remember { mutableStateOf(false) }
    var meshId by rememberSaveable { mutableStateOf(connectionPreferences.getMeshId()) }
    var passphrase by rememberSaveable { mutableStateOf(connectionPreferences.getMeshPassphrase()) }
    var bleEnabled by rememberSaveable { mutableStateOf(false) }
    var pairingEnabled by rememberSaveable { mutableStateOf(false) }
    var showDebugPopup by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connect the ESP32-S3 USB port, then scan.") }
    var log by remember { mutableStateOf(listOf<String>()) }
    val activity = context as? ComponentActivity
    val currentActiveConnection by rememberUpdatedState(activeConnection)
    val currentOnTransportConnectionChange by rememberUpdatedState(onTransportConnectionChange)

    fun appendLog(line: String) {
        log = (listOf(line) + log).take(200)
    }

    if (showDebugPopup) {
        DebugScreen(
            client = client,
            bleClient = bleClient,
            activeConnection = activeConnection,
            onClose = { showDebugPopup = false },
        )
        return
    }

    val executor = remember { Executors.newSingleThreadExecutor() }

    fun requestBlePermissions() {
        val required = bleClient.requiredPermissions()
        activity?.requestPermissions(required, 2001)
        status = "Requesting BLE permission"
        appendLog(status)
    }

    fun sendControl(
        label: String,
        action: Int,
        nextBleEnabled: Boolean = bleEnabled,
        nextPairingEnabled: Boolean = pairingEnabled,
        nextMeshId: String = meshId,
        nextPassphrase: String = passphrase,
        connectAfterSet: Boolean = false,
        connection: ActiveConnection = activeConnection,
    ) {
        status = "Sending $label..."
        appendLog(status)
        executor.execute {
            val result = when (connection) {
                ActiveConnection.BLE -> {
                    bleClient.sendControl(
                        action = action,
                        bleEnabled = nextBleEnabled,
                        pairingEnabled = nextPairingEnabled,
                        wifiSsid = nextMeshId,
                        wifiPassphrase = nextPassphrase,
                        connectAfterSet = connectAfterSet,
                    )
                }
                ActiveConnection.USB -> {
                    client.sendControl(
                        action = action,
                        bleEnabled = nextBleEnabled,
                        pairingEnabled = nextPairingEnabled,
                        wifiSsid = nextMeshId,
                        wifiPassphrase = nextPassphrase,
                        connectAfterSet = connectAfterSet,
                    )
                }
                ActiveConnection.NONE -> Result.failure(IllegalStateException("No TX connection"))
            }
            activity?.runOnUiThread {
                result.fold(
                    onSuccess = {
                        status = "$label command sent via ${connection.name}"
                        appendLog(status)
                    },
                    onFailure = {
                        status = it.message ?: "$label failed"
                        appendLog(status)
                    },
                )
            }
        }
    }

    fun handleFrame(source: ActiveConnection, frame: ByteArray) {
        val selectedConnection = currentActiveConnection
        if (selectedConnection == ActiveConnection.NONE) {
            appendLog("${source.name} RX ignored; no interface selected")
            return
        }
        if (source != selectedConnection) {
            appendLog("${source.name} RX ignored; interface is ${selectedConnection.name}")
            return
        }

        if (frame.size < EDGEZ_HEADER_LEN || frame[0] != EDGEZ_MAGIC_0 || frame[1] != EDGEZ_MAGIC_1 || frame[2] != EDGEZ_VERSION) {
            return
        }

        val responseType = frame[3].toInt() and 0xff
        val responseSeq = ByteBuffer.wrap(frame, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        val responseLen = ByteBuffer.wrap(frame, 6, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        if (responseLen > EDGEZ_MAX_PAYLOAD || EDGEZ_HEADER_LEN + responseLen > frame.size) {
            return
        }

        val payload = frame.copyOfRange(EDGEZ_HEADER_LEN, EDGEZ_HEADER_LEN + responseLen)
        activity?.runOnUiThread {
            when (responseType) {
                EDGEZ_TYPE_CONTROL_RESP -> {
                    val control = EdgezUsbControlProto.decodeResponse(payload)
                    if (control == null) {
                        status = "Malformed control response seq=$responseSeq"
                    } else {
                        bleEnabled = control.bleEnabled
                        pairingEnabled = control.pairingEnabled
                        val message = control.message.ifBlank { if (control.ok) "OK" else "Error" }
                        status = "${source.name} RX seq=$responseSeq $message err=${control.espErr}"
                    }
                }
                EDGEZ_TYPE_ERROR -> {
                    val text = String(payload, StandardCharsets.UTF_8)
                    status = "${source.name} device error on seq=$responseSeq: $text"
                }
                EDGEZ_TYPE_ECHO_RESP -> {
                    val text = String(payload, StandardCharsets.UTF_8)
                    status = "${source.name} debug echo seq=$responseSeq: $text"
                }
                else -> {
                    status = "${source.name} unknown packet on seq=$responseSeq: type=$responseType"
                }
            }
            appendLog(status)
        }
    }

    DisposableEffect(Unit) {
        val removeFrameListener = client.addFrameListener { frame ->
            handleFrame(ActiveConnection.USB, frame)
        }
        val removeDebugListener = client.addDebugListener { line ->
            activity?.runOnUiThread {
                appendLog("USB $line")
            }
        }
        val removeBleFrameListener = bleClient.addFrameListener { frame ->
            handleFrame(ActiveConnection.BLE, frame)
        }
        val removeBleDebugListener = bleClient.addDebugListener { line ->
            activity?.runOnUiThread {
                appendLog("BLE $line")
                if (line == "SERVICE ready") {
                    bleReady = true
                    currentOnTransportConnectionChange(ActiveConnection.BLE, true)
                    status = "BLE connected"
                } else if (line.startsWith("CONN") && line.contains("state=0") || line == "CLOSE") {
                    bleReady = false
                    currentOnTransportConnectionChange(ActiveConnection.BLE, false)
                }
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                candidates = client.scan()
                selected = candidates.firstOrNull { client.hasPermission(it.device) } ?: candidates.firstOrNull()
                status = if (granted) "USB permission granted" else "USB permission denied"
                appendLog(status)
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), flags)
        onDispose {
            context.unregisterReceiver(receiver)
            removeFrameListener()
            removeDebugListener()
            removeBleFrameListener()
            removeBleDebugListener()
            executor.shutdownNow()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text("Interface: ${activeConnection.name}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { showDebugPopup = true }) {
                    Text("Debug")
                }
            }

            item {
                SettingsCard(title = "USB connection") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            candidates = client.scan()
                            selected = candidates.firstOrNull()
                            status = "Found ${candidates.size} USB device interface(s)"
                            appendLog(status)
                        }) { Text("Scan") }
                        Button(enabled = selected != null, onClick = {
                            val candidate = selected ?: return@Button
                            if (!client.hasPermission(candidate.device)) {
                                client.requestPermission(candidate.device)
                                status = "Requesting USB permission"
                                appendLog(status)
                            } else {
                                status = client.connect(candidate)
                                onTransportConnectionChange(ActiveConnection.USB, true)
                                bleReady = false
                                appendLog(status)
                                sendControl("status", USB_CONTROL_ACTION_GET_STATUS, connection = ActiveConnection.USB)
                            }
                        }) { Text("Connect") }
                    }
                    Spacer(Modifier.height(10.dp))
                    DeviceList(candidates, selected) { selected = it }
                }
            }

            item {
                SettingsCard(title = "BLE connection") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (!bleClient.hasPermissions()) {
                                requestBlePermissions()
                            } else {
                                bleCandidates = emptyList()
                                selectedBle = null
                                bleReady = false
                                val result = bleClient.startScan { candidate ->
                                    activity?.runOnUiThread {
                                        if (bleCandidates.none { it.device.address == candidate.device.address }) {
                                            bleCandidates = (bleCandidates + candidate).sortedBy { it.label }
                                        }
                                        selectedBle = selectedBle ?: candidate
                                    }
                                }
                                result.fold(
                                    onSuccess = {
                                        status = it
                                        appendLog(status)
                                    },
                                    onFailure = {
                                        status = it.message ?: "BLE scan failed"
                                        appendLog(status)
                                    },
                                )
                            }
                        }) { Text("Scan BLE") }
                        Button(onClick = {
                            bleClient.stopScan()
                            status = "BLE scan stopped"
                            appendLog(status)
                        }) { Text("Stop") }
                        Button(enabled = selectedBle != null, onClick = {
                            val candidate = selectedBle ?: return@Button
                            bleClient.stopScan()
                            val result = bleClient.connect(candidate)
                            result.fold(
                                onSuccess = {
                                    status = it
                                    onTransportConnectionChange(ActiveConnection.USB, false)
                                    appendLog(status)
                                },
                                onFailure = {
                                    status = it.message ?: "BLE connect failed"
                                    appendLog(status)
                                },
                            )
                        }) { Text("Connect") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(if (bleReady) "BLE ready" else "BLE not connected", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    if (bleCandidates.isEmpty()) {
                        Text("No EdgeZ BLE devices found.")
                    } else {
                        bleCandidates.forEach { candidate ->
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { selectedBle = candidate },
                            ) {
                                Text(if (candidate == selectedBle) "Selected: ${candidate.label}" else candidate.label)
                            }
                        }
                    }
                }
            }

            item {
                SettingsCard(title = "Mesh network") {
                    OutlinedTextField(
                        value = meshId,
                        onValueChange = { meshId = it.take(32) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Mesh ID / SSID") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Passphrase") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        enabled = meshId.isNotBlank(),
                        onClick = {
                            connectionPreferences.setMeshCredentials(meshId, passphrase)
                            sendControl(
                                label = "credentials",
                                action = USB_CONTROL_ACTION_SET_WIFI_CREDENTIALS,
                                connectAfterSet = true,
                            )
                        },
                    ) { Text("Send credentials") }
                }
            }

            item {
                SettingsCard(title = "BLE") {
                    SettingSwitchRow(
                        label = "BLE enabled",
                        checked = bleEnabled,
                        onCheckedChange = { checked ->
                            bleEnabled = checked
                            if (!checked) pairingEnabled = false
                            sendControl(
                                label = if (checked) "enable BLE" else "disable BLE",
                                action = USB_CONTROL_ACTION_SET_BLE_ENABLED,
                                nextBleEnabled = checked,
                            )
                        },
                    )
                    SettingSwitchRow(
                        label = "Pairing mode",
                        checked = pairingEnabled,
                        onCheckedChange = { checked ->
                            pairingEnabled = checked
                            if (checked) bleEnabled = true
                            sendControl(
                                label = if (checked) "enable pairing" else "disable pairing",
                                action = USB_CONTROL_ACTION_SET_PAIRING_ENABLED,
                                nextPairingEnabled = checked,
                            )
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { sendControl("status", USB_CONTROL_ACTION_GET_STATUS) }) {
                        Text("Refresh status")
                    }
                }
            }

            item { LogCard(log) }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    EdgeZTheme {
        val context = LocalContext.current.applicationContext
        SettingsScreen(
            client = EdgezUsbClient(context),
            bleClient = EdgezBleClient(context),
            activeConnection = ActiveConnection.NONE,
            onTransportConnectionChange = { _, _ -> },
        )
    }
}
