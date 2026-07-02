package ai.edgez.edgez

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.location.LocationManager
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
import androidx.compose.runtime.LaunchedEffect
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
import ai.edgez.edgez.usb.DeviceSettings
import ai.edgez.edgez.usb.EdgezUsbClient
import ai.edgez.edgez.usb.UsbCandidate
import java.util.concurrent.Executors
import java.util.UUID

@Composable
fun SettingsScreen(
    client: EdgezUsbClient,
    bleClient: EdgezBleClient,
    activeConnection: ActiveConnection,
    shareLocation: Boolean,
    onShareLocationChange: (Boolean) -> Unit,
    onTransportConnectionChange: (ActiveConnection, Boolean) -> Unit,
    onTransportDisconnect: (ActiveConnection) -> Unit,
) {
    val context = LocalContext.current
    val connectionPreferences = remember { LastConnectionPreferences(context.applicationContext) }
    fun newDeviceIdentity(name: String = "EdgeZ Device"): UserIdentity {
        val uuid = UUID.randomUUID()
        val keyPair = X25519KeyGenerator.generateKeyPair()
        return UserIdentity(
            userUuid = uuid.toString(),
            userIdHigh = uuid.mostSignificantBits,
            userIdLow = uuid.leastSignificantBits,
            name = name,
            privateKey = keyPair.first,
            publicKey = keyPair.second,
        )
    }

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
    var deviceIdentity by remember { mutableStateOf<UserIdentity?>(null) }
    var deviceUserName by rememberSaveable { mutableStateOf("EdgeZ Device") }
    var deviceUserMarker by rememberSaveable { mutableStateOf(NodeMapMarker.DEFAULT.id) }
    var deviceMeshId by rememberSaveable { mutableStateOf("edgez") }
    var deviceBeaconIntervalSeconds by rememberSaveable { mutableStateOf(DEFAULT_BEACON_INTERVAL_SECONDS.toString()) }
    var deviceShareLocation by rememberSaveable { mutableStateOf(false) }
    var deviceLatitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var deviceLongitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var deviceMode by rememberSaveable { mutableStateOf(DeviceModeState.enabled) }
    var autoReplayReceivedVoice by rememberSaveable { mutableStateOf(connectionPreferences.getAutoReplayReceivedVoice()) }
    var showDebugPopup by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connect the ESP32-S3 USB port, then scan.") }
    val activity = context as? ComponentActivity
    val currentOnTransportConnectionChange by rememberUpdatedState(onTransportConnectionChange)
    val showDeviceSettingsOnly = activeConnection != ActiveConnection.NONE && deviceMode

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

    fun reloadAppSettingsFromPreferences() {
        meshCountry = connectionPreferences.getMeshCountry()
        meshId = connectionPreferences.getMeshId()
        passphrase = connectionPreferences.getMeshPassphrase()
        maxHop = connectionPreferences.getMeshMaxHop().toString()
        beaconIntervalSeconds = connectionPreferences.getBeaconIntervalSeconds().toString()
        userIdentity = connectionPreferences.getOrCreateUserIdentity()
        userName = userIdentity.name
        userMarker = connectionPreferences.getUserMarker()
        deviceMode = false
        DeviceModeState.enabled = false
        autoReplayReceivedVoice = connectionPreferences.getAutoReplayReceivedVoice()
        onShareLocationChange(connectionPreferences.getShareLocation())
    }

    fun ensureDeviceIdentity(): UserIdentity {
        val existing = deviceIdentity
        if (existing != null) return existing
        val generated = newDeviceIdentity(deviceUserName.ifBlank { "EdgeZ Device" })
        deviceIdentity = generated
        deviceUserName = generated.name
        return generated
    }

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

    fun currentLocationPair(): Pair<Double, Double>? {
        val hasFine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = if (hasFine) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        val location = providers.mapNotNull { provider ->
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.getLastKnownLocation(provider)
                } else {
                    null
                }
            }.getOrNull()
        }.maxByOrNull { it.time }
        return location?.latitude?.let { lat ->
            location.longitude.let { lon -> lat to lon }
        }
    }

    fun applyDeviceSettings(settings: DeviceSettings) {
        deviceMode = settings.deviceModeEnabled
        DeviceModeState.enabled = settings.deviceModeEnabled
        if (settings.meshId.isNotBlank()) {
            deviceMeshId = settings.meshId
        }
        deviceShareLocation = settings.shareLocation
        deviceUserName = settings.userName.ifBlank { deviceUserName }
        deviceUserMarker = NodeMapMarker.normalize(settings.marker)
        if (settings.beaconIntervalSeconds > 0) {
            deviceBeaconIntervalSeconds = settings.beaconIntervalSeconds.toString()
        }
        if ((deviceLatitude == null || deviceLongitude == null) && settings.latitude != null && settings.longitude != null) {
            deviceLatitude = settings.latitude
            deviceLongitude = settings.longitude
        }
        if (deviceIdentity == null && (settings.userIdHigh != 0L || settings.userIdLow != 0L) && settings.userPrivateKey.size == 32) {
            val publicKey = if (settings.userPublicKey.size == 32) {
                settings.userPublicKey
            } else {
                X25519KeyGenerator.publicKey(settings.userPrivateKey)
            }
            deviceIdentity = UserIdentity(
                userUuid = UUID(settings.userIdHigh, settings.userIdLow).toString(),
                userIdHigh = settings.userIdHigh,
                userIdLow = settings.userIdLow,
                name = deviceUserName.ifBlank { settings.userName },
                privateKey = settings.userPrivateKey,
                publicKey = publicKey,
            )
        } else if (deviceIdentity == null && settings.deviceModeEnabled) {
            ensureDeviceIdentity()
        }
        status = "Device settings loaded"
    }

    fun currentDeviceSettings(enabled: Boolean = deviceMode): DeviceSettings {
        val identity = ensureDeviceIdentity()
        return DeviceSettings(
            deviceModeEnabled = enabled,
            meshId = deviceMeshId.ifBlank { "edgez" },
            shareLocation = deviceShareLocation,
            userName = deviceUserName,
            marker = deviceUserMarker,
            beaconIntervalSeconds = deviceBeaconIntervalSeconds.toIntOrNull() ?: DEFAULT_BEACON_INTERVAL_SECONDS,
            userIdHigh = identity.userIdHigh,
            userIdLow = identity.userIdLow,
            userPublicKey = identity.publicKey,
            userPrivateKey = identity.privateKey,
            latitude = deviceLatitude.takeIf { deviceShareLocation },
            longitude = deviceLongitude.takeIf { deviceShareLocation },
        )
    }

    fun refreshDeviceLocation() {
        val location = currentLocationPair()
        if (location == null) {
            if (!hasLocationPermission()) {
                requestLocationPermissions()
            } else {
                status = "No phone location available"
            }
            return
        }
        deviceLatitude = location.first
        deviceLongitude = location.second
        status = "Device location refreshed"
    }

    fun requestDeviceSettings(connection: ActiveConnection = activeConnection) {
        if (connection == ActiveConnection.NONE) {
            status = "Connect USB or BLE before loading device settings"
            return
        }
        status = "Loading device settings..."
        executor.execute {
            val result = when (connection) {
                ActiveConnection.USB -> client.requestDeviceSettings()
                ActiveConnection.BLE -> bleClient.requestDeviceSettings()
                ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
            }
            activity?.runOnUiThread {
                status = result.fold(
                    onSuccess = {
                        deviceMode = false
                        DeviceModeState.enabled = false
                        "Device settings request sent; using app mode unless device settings respond"
                    },
                    onFailure = {
                        deviceMode = false
                        DeviceModeState.enabled = false
                        "Device settings unavailable; using app mode"
                    },
                )
            }
        }
    }

    fun sendDeviceSettingsToDevice(
        settings: DeviceSettings = currentDeviceSettings(),
        connection: ActiveConnection = activeConnection,
        label: String = "Device settings",
    ) {
        if (connection == ActiveConnection.NONE) {
            status = "$label saved locally; connect USB or BLE to sync"
            return
        }
        status = "Saving $label..."
        executor.execute {
            val result = when (connection) {
                ActiveConnection.USB -> client.sendDeviceSettings(settings)
                ActiveConnection.BLE -> bleClient.sendDeviceSettings(settings)
                ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
            }
            activity?.runOnUiThread {
                status = result.fold(
                    onSuccess = { "$label sent" },
                    onFailure = { it.message ?: "$label save failed" },
                )
            }
        }
    }

    fun disconnectActiveTransport() {
        val connection = activeConnection
        DeviceModeState.enabled = false
        reloadAppSettingsFromPreferences()
        if (connection == ActiveConnection.NONE) {
            status = "Disconnected"
            return
        }
        when (connection) {
            ActiveConnection.USB -> client.close()
            ActiveConnection.BLE -> {
                bleReady = false
                bleClient.close()
            }
            ActiveConnection.NONE -> Unit
        }
        onTransportDisconnect(connection)
        status = "Disconnected from ${connection.name}"
    }

    fun saveMeshPreferences(
        country: String = meshCountry,
        id: String = meshId,
        password: String = passphrase,
        hopLimit: Int = maxHop.toIntOrNull() ?: 2,
        beaconInterval: Int = beaconIntervalSeconds.toIntOrNull() ?: DEFAULT_BEACON_INTERVAL_SECONDS,
    ) {
        if (deviceMode) {
            sendDeviceSettingsToDevice()
            return
        }
        connectionPreferences.setMeshCredentials(country, id, password, hopLimit, beaconInterval)
        maxHop = connectionPreferences.getMeshMaxHop().toString()
        beaconIntervalSeconds = connectionPreferences.getBeaconIntervalSeconds().toString()
        connectionPreferences.setUserName(userName)
        connectionPreferences.setUserMarker(userMarker)
        connectionPreferences.setShareLocation(shareLocation)
        userIdentity = connectionPreferences.getOrCreateUserIdentity()
        status = "Settings saved"
    }

    DisposableEffect(Unit) {
        fun handleSettingsFrame(frame: ByteArray) {
            val deviceSettings = decodeHaLowSyncFrame(frame, connectionPreferences.getMeshPassphrase())?.deviceSettings
                ?: return
            activity?.runOnUiThread {
                applyDeviceSettings(deviceSettings)
            }
        }

        val removeUsbSettingsListener = client.addFrameListener(::handleSettingsFrame)
        val removeBleSettingsListener = bleClient.addFrameListener(::handleSettingsFrame)
        val removeUsbDebugListener = client.addDebugListener { line ->
            activity?.runOnUiThread {
                if (line.startsWith("CONNECT ")) {
                    bleReady = false
                    currentOnTransportConnectionChange(ActiveConnection.USB, true)
                } else if (line == "CLOSE") {
                    currentOnTransportConnectionChange(ActiveConnection.USB, false)
                }
            }
        }
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
            removeUsbSettingsListener()
            removeBleSettingsListener()
            removeUsbDebugListener()
            removeBleDebugListener()
            executor.shutdownNow()
        }
    }

    LaunchedEffect(activeConnection) {
        if (activeConnection != ActiveConnection.NONE) {
            requestDeviceSettings(activeConnection)
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
                Text("Settings source: ${if (deviceMode) "Device" else "App"}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!showDeviceSettingsOnly) {
                        Button(onClick = { showDebugPopup = true }) {
                            Text("Debug")
                        }
                        Button(onClick = { requestIgnoreBatteryOptimizations() }) {
                            Text("Allow background connection")
                        }
                    }
                }
            }

            item {
                SettingsCard(title = "Device mode") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Use device settings", style = MaterialTheme.typography.titleSmall)
                            Text("Read and write mesh, beacon, location, name, and marker on the connected device", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = deviceMode,
                            onCheckedChange = { enabled ->
                                deviceMode = enabled
                                DeviceModeState.enabled = enabled
                                sendDeviceSettingsToDevice(
                                    currentDeviceSettings(enabled = enabled),
                                    label = if (enabled) "Device mode settings" else "App mode settings",
                                )
                                if (!enabled) status = "App settings mode enabled"
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = activeConnection != ActiveConnection.NONE,
                            onClick = { requestDeviceSettings() },
                        ) {
                            Text("Load")
                        }
                        if (showDeviceSettingsOnly) {
                            Button(onClick = { disconnectActiveTransport() }) {
                                Text("Disconnect")
                            }
                        }
                    }
                }
            }

            if (!showDeviceSettingsOnly) item {
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
                            }
                        }) { Text("Connect") }
                    }
                    Spacer(Modifier.height(10.dp))
                    DeviceList(candidates, selected) { selected = it }
                }
            }

            if (!showDeviceSettingsOnly) item {
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
                SettingsCard(title = if (showDeviceSettingsOnly) "Device user" else "User") {
                    Text("User ID", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (showDeviceSettingsOnly) deviceIdentity?.userUuid ?: "Not loaded" else userIdentity.userUuid,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = if (showDeviceSettingsOnly) deviceUserName else userName,
                        onValueChange = { value ->
                            if (showDeviceSettingsOnly) {
                                deviceUserName = value.take(64)
                            } else {
                                userName = value.take(64)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("X25519 public key", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (showDeviceSettingsOnly) {
                            deviceIdentity?.publicKey?.let(::formatHex) ?: "Not loaded"
                        } else {
                            formatHex(userIdentity.publicKey)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (showDeviceSettingsOnly) {
                        Spacer(Modifier.height(8.dp))
                        Text("X25519 private key", style = MaterialTheme.typography.titleSmall)
                        Text(deviceIdentity?.privateKey?.let(::formatHex) ?: "Not loaded", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = {
                            deviceIdentity = newDeviceIdentity(deviceUserName.ifBlank { "EdgeZ Device" })
                            deviceUserName = deviceIdentity?.name ?: "EdgeZ Device"
                            status = "Device X25519 key pair regenerated"
                        }) {
                            Text("Generate device key pair")
                        }
                    } else {
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
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { markerDropdownExpanded = true },
                        ) {
                            Text(
                                "Marker: ${NodeMapMarker.fromId(if (showDeviceSettingsOnly) deviceUserMarker else userMarker).label}",
                            )
                        }
                        DropdownMenu(
                            expanded = markerDropdownExpanded,
                            onDismissRequest = { markerDropdownExpanded = false },
                        ) {
                            markerOptions.forEach { marker ->
                                DropdownMenuItem(
                                    text = { Text(marker.label) },
                                    onClick = {
                                        if (showDeviceSettingsOnly) {
                                            deviceUserMarker = marker.id
                                        } else {
                                            userMarker = marker.id
                                        }
                                        markerDropdownExpanded = false
                                        if (!showDeviceSettingsOnly) {
                                            connectionPreferences.setUserMarker(marker.id)
                                        }
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
                            checked = if (showDeviceSettingsOnly) deviceShareLocation else shareLocation,
                            onCheckedChange = { enabled ->
                                if (showDeviceSettingsOnly) {
                                    deviceShareLocation = enabled
                                } else {
                                    connectionPreferences.setShareLocation(enabled)
                                    onShareLocationChange(enabled)
                                }
                                if (enabled && !hasLocationPermission()) {
                                    requestLocationPermissions()
                                } else {
                                    status = if (enabled) "Location sharing enabled" else "Location sharing disabled"
                                }
                            },
                        )
                    }
                    if (showDeviceSettingsOnly) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Location: ${formatLocation(deviceLatitude, deviceLongitude)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { refreshDeviceLocation() }) {
                            Text("Refresh location")
                        }
                    }
                }
            }

            item {
                SettingsCard(title = if (showDeviceSettingsOnly) "Device settings" else "Mesh network") {
                    if (!showDeviceSettingsOnly) {
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
                    }
                    OutlinedTextField(
                        value = if (showDeviceSettingsOnly) deviceMeshId else meshId,
                        onValueChange = { value ->
                            if (showDeviceSettingsOnly) {
                                deviceMeshId = value.take(32)
                            } else {
                                meshId = value.take(32)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Mesh ID / SSID") },
                        singleLine = true,
                    )
                    if (!showDeviceSettingsOnly) {
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
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = if (showDeviceSettingsOnly) deviceBeaconIntervalSeconds else beaconIntervalSeconds,
                        onValueChange = { value ->
                            if (showDeviceSettingsOnly) {
                                deviceBeaconIntervalSeconds = value.filter { it.isDigit() }.take(4)
                            } else {
                                beaconIntervalSeconds = value.filter { it.isDigit() }.take(4)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Beacon interval (seconds)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { saveMeshPreferences() }) {
                        Text(if (deviceMode) "Save to device" else "Save settings")
                    }
                }
            }

            if (!showDeviceSettingsOnly) item {
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

private fun formatLocation(latitude: Double?, longitude: Double?): String {
    if (latitude == null || longitude == null) return "Not loaded"
    return "%.6f, %.6f".format(latitude, longitude)
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
            onTransportDisconnect = {},
        )
    }
}
