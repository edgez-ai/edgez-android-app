package ai.edgez.edgez

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.ble.BleCandidate
import ai.edgez.edgez.ble.EdgezBleClient
import ai.edgez.edgez.ui.theme.EdgeZTheme
import ai.edgez.edgez.usb.ACTION_USB_PERMISSION
import ai.edgez.edgez.usb.EdgezUsbClient
import ai.edgez.edgez.usb.USB_CONTROL_ACTION_GET_STATUS
import ai.edgez.edgez.usb.UsbCandidate
import java.util.concurrent.Executors

@Composable
fun SettingsScreen(
    client: EdgezUsbClient,
    bleClient: EdgezBleClient,
    activeConnection: ActiveConnection,
    shareLocation: Boolean,
    onShareLocationChange: (Boolean) -> Unit,
    onTransportConnectionChange: (ActiveConnection, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val connectionPreferences = remember { LastConnectionPreferences(context.applicationContext) }
    var candidates by remember { mutableStateOf(client.scan()) }
    var selected by remember { mutableStateOf<UsbCandidate?>(candidates.firstOrNull()) }
    var bleCandidates by remember { mutableStateOf(listOf<BleCandidate>()) }
    var selectedBle by remember { mutableStateOf<BleCandidate?>(null) }
    var bleReady by remember { mutableStateOf(false) }
    val countryOptions = remember { listOf("US", "JP", "EU") }
    val markerOptions = remember { NodeMapMarker.values().toList() }
    var countryDropdownExpanded by remember { mutableStateOf(false) }
    var markerDropdownExpanded by remember { mutableStateOf(false) }
    var meshCountry by rememberSaveable { mutableStateOf(connectionPreferences.getMeshCountry()) }
    var meshId by rememberSaveable { mutableStateOf(connectionPreferences.getMeshId()) }
    var passphrase by rememberSaveable { mutableStateOf(connectionPreferences.getMeshPassphrase()) }
    var maxHop by rememberSaveable { mutableStateOf(connectionPreferences.getMeshMaxHop().toString()) }
    var beaconIntervalSeconds by rememberSaveable {
        mutableStateOf(connectionPreferences.getBeaconIntervalSeconds().toString())
    }
    var userIdentity by remember { mutableStateOf(connectionPreferences.getOrCreateUserIdentity()) }
    var userName by rememberSaveable { mutableStateOf(userIdentity.name) }
    var userMarker by rememberSaveable { mutableStateOf(connectionPreferences.getUserMarker()) }
    var autoReplayReceivedVoice by rememberSaveable { mutableStateOf(connectionPreferences.getAutoReplayReceivedVoice()) }
    var showDebugPopup by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connect the ESP32-S3 USB port, then scan.") }
    val activity = context as? ComponentActivity
    val currentActiveConnection by rememberUpdatedState(activeConnection)
    val currentOnTransportConnectionChange by rememberUpdatedState(onTransportConnectionChange)

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
        val required = if (Build.VERSION.SDK_INT >= 33) {
            bleClient.requiredPermissions() + Manifest.permission.POST_NOTIFICATIONS
        } else {
            bleClient.requiredPermissions()
        }
        activity?.requestPermissions(required, 2001)
        status = "Requesting BLE permission"
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestNotificationPermissionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return false
        }
        activity?.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2003)
        status = "Requesting notification permission"
        return true
    }

    fun requestIgnoreBatteryOptimizations() {
        if (requestNotificationPermissionIfNeeded()) return
        if (isIgnoringBatteryOptimizations()) {
            status = "Battery optimization already disabled"
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        runCatching {
            context.startActivity(intent)
        }.onSuccess {
            status = "Battery optimization prompt opened"
        }.onFailure {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            status = "Battery optimization settings opened"
        }
    }

    fun hasLocationPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun requestLocationPermissions() {
        activity?.requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            2002,
        )
        status = "Requesting location permission"
    }

    fun saveMeshPreferences(
        country: String = meshCountry,
        id: String = meshId,
        password: String = passphrase,
        hopLimit: Int = maxHop.toIntOrNull() ?: 2,
        beaconInterval: Int = beaconIntervalSeconds.toIntOrNull() ?: DEFAULT_BEACON_INTERVAL_SECONDS,
    ) {
        connectionPreferences.setMeshCredentials(country, id, password, hopLimit, beaconInterval)
        maxHop = connectionPreferences.getMeshMaxHop().toString()
        beaconIntervalSeconds = connectionPreferences.getBeaconIntervalSeconds().toString()
        connectionPreferences.setUserName(userName)
        connectionPreferences.setUserMarker(userMarker)
        connectionPreferences.setShareLocation(shareLocation)
        userIdentity = connectionPreferences.getOrCreateUserIdentity()
        status = "Settings saved"
    }

    fun sendControl(
        label: String,
        action: Int,
        connection: ActiveConnection = activeConnection,
    ) {
        status = "Sending $label..."
        executor.execute {
            val result = when (connection) {
                ActiveConnection.BLE -> {
                    bleClient.sendControl(
                        action = action,
                    )
                }
                ActiveConnection.USB -> {
                    client.sendControl(
                        action = action,
                    )
                }
                ActiveConnection.NONE -> Result.failure(IllegalStateException("No TX connection"))
            }
            activity?.runOnUiThread {
                result.fold(
                    onSuccess = {
                        status = "$label command sent via ${connection.name}"
                    },
                    onFailure = {
                        status = it.message ?: "$label failed"
                    },
                )
            }
        }
    }

    DisposableEffect(Unit) {
        val removeBleDebugListener = bleClient.addDebugListener { line ->
            activity?.runOnUiThread {
                if (line == "SERVICE ready") {
                    bleReady = true
                    currentOnTransportConnectionChange(ActiveConnection.BLE, true)
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
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), flags)
        onDispose {
            context.unregisterReceiver(receiver)
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showDebugPopup = true }) {
                        Text("Debug")
                    }
                    Button(onClick = { requestIgnoreBatteryOptimizations() }) {
                        Text("Allow background connection")
                    }
                }
            }

            item {
                SettingsCard(title = "USB connection") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            candidates = client.scan()
                            selected = candidates.firstOrNull()
                            status = "Found ${candidates.size} USB device interface(s)"
                        }) { Text("Scan") }
                        Button(enabled = selected != null, onClick = {
                            val candidate = selected ?: return@Button
                            if (!client.hasPermission(candidate.device)) {
                                client.requestPermission(candidate.device)
                                status = "Requesting USB permission"
                            } else {
                                status = client.connect(candidate)
                                onTransportConnectionChange(ActiveConnection.USB, true)
                                bleReady = false
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
                                    },
                                    onFailure = {
                                        status = it.message ?: "BLE scan failed"
                                    },
                                )
                            }
                        }) { Text("Scan BLE") }
                        Button(onClick = {
                            bleClient.stopScan()
                            status = "BLE scan stopped"
                        }) { Text("Stop") }
                        Button(enabled = selectedBle != null, onClick = {
                            val candidate = selectedBle ?: return@Button
                            bleClient.stopScan()
                            val result = bleClient.connect(candidate)
                            result.fold(
                                onSuccess = {
                                    status = it
                                    onTransportConnectionChange(ActiveConnection.USB, false)
                                },
                                onFailure = {
                                    status = it.message ?: "BLE connect failed"
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
                SettingsCard(title = "User") {
                    Text("User ID", style = MaterialTheme.typography.titleSmall)
                    Text(userIdentity.userUuid, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = userName,
                        onValueChange = { userName = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("X25519 public key", style = MaterialTheme.typography.titleSmall)
                    Text(formatHex(userIdentity.publicKey), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        connectionPreferences.setUserName(userName)
                        connectionPreferences.setUserMarker(userMarker)
                        connectionPreferences.setShareLocation(shareLocation)
                        userIdentity = connectionPreferences.regenerateUserKeyPair()
                        userName = userIdentity.name
                        status = "X25519 key pair regenerated"
                    }) {
                        Text("Generate key pair")
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { markerDropdownExpanded = true },
                        ) {
                            Text("Marker: ${NodeMapMarker.fromId(userMarker).label}")
                        }
                        DropdownMenu(
                            expanded = markerDropdownExpanded,
                            onDismissRequest = { markerDropdownExpanded = false },
                        ) {
                            markerOptions.forEach { marker ->
                                DropdownMenuItem(
                                    text = { Text(marker.label) },
                                    onClick = {
                                        userMarker = marker.id
                                        markerDropdownExpanded = false
                                        connectionPreferences.setUserMarker(marker.id)
                                        status = "Marker set to ${marker.label}"
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Share location", style = MaterialTheme.typography.titleSmall)
                            Text("Include location in HaLow beacon", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = shareLocation,
                            onCheckedChange = { enabled ->
                                connectionPreferences.setShareLocation(enabled)
                                onShareLocationChange(enabled)
                                if (enabled && !hasLocationPermission()) {
                                    requestLocationPermissions()
                                } else {
                                    status = if (enabled) "Location sharing enabled" else "Location sharing disabled"
                                }
                            },
                        )
                    }
                }
            }

            item {
                SettingsCard(title = "Mesh network") {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { countryDropdownExpanded = true },
                        ) {
                            Text("Country: $meshCountry")
                        }
                        DropdownMenu(
                            expanded = countryDropdownExpanded,
                            onDismissRequest = { countryDropdownExpanded = false },
                        ) {
                            countryOptions.forEach { country ->
                                DropdownMenuItem(
                                    text = { Text(country) },
                                    onClick = {
                                        meshCountry = country
                                        countryDropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = maxHop,
                        onValueChange = { value ->
                            maxHop = value.filter { it.isDigit() }.take(3)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Max hop") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = beaconIntervalSeconds,
                        onValueChange = { value ->
                            beaconIntervalSeconds = value.filter { it.isDigit() }.take(4)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Beacon interval (seconds)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { saveMeshPreferences() }) {
                        Text("Save settings")
                    }
                }
            }

            item {
                SettingsCard(title = "Chat") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto replay received voice", style = MaterialTheme.typography.titleSmall)
                            Text("Play new incoming voice messages automatically", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = autoReplayReceivedVoice,
                            onCheckedChange = { enabled ->
                                autoReplayReceivedVoice = enabled
                                connectionPreferences.setAutoReplayReceivedVoice(enabled)
                                status = if (enabled) "Auto replay enabled" else "Auto replay disabled"
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun formatHex(bytes: ByteArray): String {
    return bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
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
            shareLocation = false,
            onShareLocationChange = {},
            onTransportConnectionChange = { _, _ -> },
        )
    }
}
