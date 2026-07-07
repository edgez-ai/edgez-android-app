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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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

private enum class ProvisionStep {
    SELECT_BLE,
    DEVICE_SETTINGS,
}

private fun parseDeviceMacAddress(input: String): Long {
    val hex = input.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    if (hex.isBlank()) return 0L
    if (hex.length != 12) return 0L
    return hex.toLongOrNull(16)?.let { it and 0xffffffffffffL } ?: 0L
}

private fun formatDeviceMacAddress(value: Long): String {
    val masked = value and 0xffffffffffffL
    if (masked == 0L) return ""
    return (5 downTo 0).joinToString(":") { byteIndex ->
        "%02X".format((masked shr (byteIndex * 8)) and 0xffL)
    }
}

@Composable
fun ProvisioningScreen(
    client: EdgezUsbClient,
    bleClient: EdgezBleClient,
    activeConnection: ActiveConnection,
    edgeZDatabase: EdgeZDatabase,
    shareLocation: Boolean,
    onShareLocationChange: (Boolean) -> Unit,
    onTransportConnectionChange: (ActiveConnection, Boolean) -> Unit,
    onTransportDisconnect: (ActiveConnection) -> Unit,
    onProvisionComplete: () -> Unit,
) {
    ProvisioningContent(
        client = client,
        bleClient = bleClient,
        activeConnection = activeConnection,
        edgeZDatabase = edgeZDatabase,
        shareLocation = shareLocation,
        onShareLocationChange = onShareLocationChange,
        onTransportConnectionChange = onTransportConnectionChange,
        onTransportDisconnect = onTransportDisconnect,
        onProvisionComplete = onProvisionComplete,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProvisioningContent(
    client: EdgezUsbClient,
    bleClient: EdgezBleClient,
    activeConnection: ActiveConnection,
    edgeZDatabase: EdgeZDatabase,
    shareLocation: Boolean,
    onShareLocationChange: (Boolean) -> Unit,
    onTransportConnectionChange: (ActiveConnection, Boolean) -> Unit,
    onTransportDisconnect: (ActiveConnection) -> Unit,
    onProvisionComplete: () -> Unit,
) {
    val provisionMode = true
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
    val uartI2cSensorOptions = remember(context) {
        DeviceSensorCatalog.sensorDefinitionsFor(context, DeviceSensorConnector.UART_I2C)
    }
    val rs485SensorOptions = remember(context) {
        DeviceSensorCatalog.sensorDefinitionsFor(context, DeviceSensorConnector.RS485)
    }
    fun loadDeviceGeoFences(): List<DeviceGeoFence> {
        val stored = edgeZDatabase.getGeoFences()
        if (stored.isNotEmpty()) return stored
        val legacy = connectionPreferences.getDeviceGeoFences()
        if (legacy.isNotEmpty()) {
            edgeZDatabase.upsertGeoFences(legacy)
            return edgeZDatabase.getGeoFences()
        }
        return emptyList()
    }

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
    var devicePassphrase by rememberSaveable { mutableStateOf(connectionPreferences.getMeshPassphrase()) }
    var deviceMaxHop by rememberSaveable { mutableStateOf(connectionPreferences.getMeshMaxHop().toString()) }
    var deviceBeaconIntervalSeconds by rememberSaveable { mutableStateOf(DEFAULT_BEACON_INTERVAL_SECONDS.toString()) }
    var deviceUpstreamWifiSsid by rememberSaveable { mutableStateOf("") }
    var deviceUpstreamWifiPassphrase by rememberSaveable { mutableStateOf("") }
    var deviceBeaconUnicast by rememberSaveable { mutableStateOf("") }
    var deviceShareLocation by rememberSaveable { mutableStateOf(false) }
    var deviceLatitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var deviceLongitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var deviceGeoFences by remember { mutableStateOf(loadDeviceGeoFences()) }
    var selectedDeviceGeoFenceKey by rememberSaveable { mutableStateOf(connectionPreferences.getSelectedDeviceGeoFenceKey() ?: "") }
    var showGeoFencePage by rememberSaveable { mutableStateOf(false) }
    var deviceGeoIndex by rememberSaveable { mutableStateOf(0) }
    var deviceUartI2cSensorType by rememberSaveable { mutableStateOf("") }
    var deviceRs485SensorType by rememberSaveable { mutableStateOf("") }
    var lastSavedDeviceUartI2cSensorType by rememberSaveable { mutableStateOf("") }
    var lastSavedDeviceRs485SensorType by rememberSaveable { mutableStateOf("") }
    var uartI2cSensorDropdownExpanded by remember { mutableStateOf(false) }
    var rs485SensorDropdownExpanded by remember { mutableStateOf(false) }
    var autoReplayReceivedVoice by rememberSaveable { mutableStateOf(connectionPreferences.getAutoReplayReceivedVoice()) }
    var showDebugPopup by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connect the ESP32-S3 USB port, then scan.") }
    var provisionStep by rememberSaveable { mutableStateOf(ProvisionStep.SELECT_BLE) }
    var pendingProvisionNext by rememberSaveable { mutableStateOf(false) }
    val activity = context as? ComponentActivity
    val currentOnTransportConnectionChange by rememberUpdatedState(onTransportConnectionChange)
    val provisionBleReady = provisionMode && activeConnection == ActiveConnection.BLE && bleReady
    val showProvisionDeviceSettings = provisionMode && provisionStep == ProvisionStep.DEVICE_SETTINGS && provisionBleReady
    val deviceMode = provisionBleReady
    val showDeviceSettingsOnly = showProvisionDeviceSettings

    if (showGeoFencePage) {
        GeoFenceMaintenanceScreen(
            geoFences = deviceGeoFences,
            selectedKey = selectedDeviceGeoFenceKey,
            onGeoFencesChange = { updated ->
                deviceGeoFences
                    .filterNot { existing -> updated.any { it.key == existing.key } }
                    .forEach(edgeZDatabase::deleteGeoFence)
                edgeZDatabase.upsertGeoFences(updated)
                val refreshed = edgeZDatabase.getGeoFences()
                deviceGeoFences = refreshed
                if (selectedDeviceGeoFenceKey.isNotBlank() && refreshed.none { DeviceGeoFence.matchesKey(it, selectedDeviceGeoFenceKey) }) {
                    selectedDeviceGeoFenceKey = ""
                    connectionPreferences.setSelectedDeviceGeoFenceKey(null)
                }
            },
            onSelectedKeyChange = { key ->
                selectedDeviceGeoFenceKey = key.orEmpty()
                connectionPreferences.setSelectedDeviceGeoFenceKey(key)
            },
            onBack = { showGeoFencePage = false },
        )
        return
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

    fun reloadAppSettingsFromPreferences() {
        meshCountry = connectionPreferences.getMeshCountry()
        meshId = connectionPreferences.getMeshId()
        passphrase = connectionPreferences.getMeshPassphrase()
        maxHop = connectionPreferences.getMeshMaxHop().toString()
        beaconIntervalSeconds = connectionPreferences.getBeaconIntervalSeconds().toString()
        userIdentity = connectionPreferences.getOrCreateUserIdentity()
        userName = userIdentity.name
        userMarker = connectionPreferences.getUserMarker()
        autoReplayReceivedVoice = connectionPreferences.getAutoReplayReceivedVoice()
        deviceGeoFences = loadDeviceGeoFences()
        selectedDeviceGeoFenceKey = connectionPreferences.getSelectedDeviceGeoFenceKey() ?: ""
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

    fun regenerateDeviceIdentity() {
        val generated = newDeviceIdentity(deviceUserName.ifBlank { "EdgeZ Device" })
        deviceIdentity = generated
        deviceUserName = generated.name
        status = "Device user ID regenerated; save settings to apply"
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

    fun startBleScanForProvision(clearPrevious: Boolean = true) {
        if (!bleClient.hasPermissions()) {
            requestBlePermissions()
            return
        }
        if (clearPrevious) {
            bleCandidates = emptyList()
            selectedBle = null
        }
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
            onSuccess = { status = it },
            onFailure = { status = it.message ?: "BLE scan failed" },
        )
    }

    fun connectSelectedBleForProvision(): Boolean {
        val candidate = selectedBle
        if (candidate == null) {
            status = "Select an EdgeZ BLE device before continuing"
            return false
        }
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
        return result.isSuccess
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
        if (settings.meshId.isNotBlank()) {
            deviceMeshId = settings.meshId
        }
        devicePassphrase = settings.passphrase
        deviceUpstreamWifiSsid = settings.upstreamWifiSsid
        deviceUpstreamWifiPassphrase = settings.upstreamWifiPassphrase
        deviceBeaconUnicast = formatDeviceMacAddress(settings.beaconUnicast)
        deviceShareLocation = settings.shareLocation
        deviceUserName = settings.userName.ifBlank { deviceUserName }
        deviceUserMarker = NodeMapMarker.normalize(settings.marker)
        if (settings.beaconIntervalSeconds > 0) {
            deviceBeaconIntervalSeconds = settings.beaconIntervalSeconds.toString()
        }
        deviceMaxHop = settings.maxHop.coerceIn(0, 255).toString()
        if ((deviceLatitude == null || deviceLongitude == null) && settings.latitude != null && settings.longitude != null) {
            deviceLatitude = settings.latitude
            deviceLongitude = settings.longitude
        }
        settings.geoFence?.let { geoFence ->
            edgeZDatabase.upsertGeoFence(geoFence)
            deviceGeoFences = edgeZDatabase.getGeoFences()
            selectedDeviceGeoFenceKey = geoFence.key
            connectionPreferences.setSelectedDeviceGeoFenceKey(geoFence.key)
        }
        deviceGeoIndex = settings.geoIndex.coerceAtLeast(0)
        val loadedUartI2cSensorType = settings.uartI2cSensorType.take(32)
        val loadedRs485SensorType = settings.rs485SensorType.take(32)
        deviceUartI2cSensorType = loadedUartI2cSensorType
        deviceRs485SensorType = loadedRs485SensorType
        lastSavedDeviceUartI2cSensorType = loadedUartI2cSensorType
        lastSavedDeviceRs485SensorType = loadedRs485SensorType
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
        val selectedGeoFence = deviceGeoFences.firstOrNull { DeviceGeoFence.matchesKey(it, selectedDeviceGeoFenceKey) }
        return DeviceSettings(
            deviceModeEnabled = enabled,
            meshId = deviceMeshId.ifBlank { "edgez" },
            passphrase = devicePassphrase.take(64),
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
            maxHop = deviceMaxHop.toIntOrNull() ?: connectionPreferences.getMeshMaxHop(),
            geoFence = selectedGeoFence,
            uartI2cSensorType = deviceUartI2cSensorType.take(32),
            rs485SensorType = deviceRs485SensorType.take(32),
            geoIndex = deviceGeoIndex.coerceAtLeast(0),
            upstreamWifiSsid = deviceUpstreamWifiSsid.take(32),
            upstreamWifiPassphrase = deviceUpstreamWifiPassphrase.take(64),
            beaconUnicast = parseDeviceMacAddress(deviceBeaconUnicast),
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
                    onSuccess = { "Device settings request sent" },
                    onFailure = { it.message ?: "Device settings unavailable" },
                )
            }
        }
    }

    fun sendDeviceSettingsToDevice(
        settings: DeviceSettings = currentDeviceSettings(),
        connection: ActiveConnection = activeConnection,
        label: String = "Device settings",
        onSuccessAction: (() -> Unit)? = null,
    ) {
        if (connection == ActiveConnection.NONE) {
            status = "$label saved locally; connect USB or BLE to sync"
            return
        }
        status = "Saving $label..."
        executor.execute {
            val uartI2cSensorType = settings.uartI2cSensorType.take(32)
            val rs485SensorType = settings.rs485SensorType.take(32)
            val sensorSelectionChanged = uartI2cSensorType != lastSavedDeviceUartI2cSensorType ||
                rs485SensorType != lastSavedDeviceRs485SensorType
            val scriptConfigs = if (sensorSelectionChanged) {
                DeviceSensorCatalog.scriptConfigsFor(
                    context = context,
                    uartI2cSensorType = uartI2cSensorType,
                    rs485SensorType = rs485SensorType,
                    previousUartI2cSensorType = lastSavedDeviceUartI2cSensorType,
                    previousRs485SensorType = lastSavedDeviceRs485SensorType,
                )
            } else {
                emptyList()
            }
            var result: Result<String> = Result.success("No sensor scripts")
            for (scriptConfig in scriptConfigs) {
                result = when (connection) {
                    ActiveConnection.USB -> client.sendDeviceSensorScript(scriptConfig)
                    ActiveConnection.BLE -> bleClient.sendDeviceSensorScript(scriptConfig)
                    ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                }
                if (result.isFailure) break
            }
            if (result.isSuccess) {
                result = when (connection) {
                    ActiveConnection.USB -> client.sendDeviceSettings(settings)
                    ActiveConnection.BLE -> bleClient.sendDeviceSettings(settings)
                    ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                }
            }
            activity?.runOnUiThread {
                status = result.fold(
                    onSuccess = {
                        lastSavedDeviceUartI2cSensorType = uartI2cSensorType
                        lastSavedDeviceRs485SensorType = rs485SensorType
                        val message = if (scriptConfigs.isEmpty()) {
                            "$label sent"
                        } else {
                            "$label and ${scriptConfigs.size} sensor config(s) sent"
                        }
                        onSuccessAction?.invoke()
                        message
                    },
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

    fun disconnectProvisionTransport() {
        val connection = activeConnection
        DeviceModeState.enabled = false
        bleClient.stopScan()
        if (connection == ActiveConnection.NONE) {
            bleReady = false
            status = "Disconnected"
            return
        }
        if (connection == ActiveConnection.BLE) {
            bleReady = false
            bleClient.close()
            onTransportDisconnect(connection)
            status = "Disconnected from ${connection.name}"
        }
    }

    fun cancelProvision() {
        bleClient.stopScan()
        if (activeConnection == ActiveConnection.BLE || bleReady) {
            disconnectProvisionTransport()
        }
        provisionStep = ProvisionStep.SELECT_BLE
        onProvisionComplete()
    }

    fun goBackProvisionStep() {
        if (provisionStep == ProvisionStep.DEVICE_SETTINGS) {
            provisionStep = ProvisionStep.SELECT_BLE
            status = "Select an EdgeZ BLE device"
        } else {
            cancelProvision()
        }
    }

    fun goNextProvisionStep() {
        if (provisionStep == ProvisionStep.SELECT_BLE) {
            if (!provisionBleReady) {
                if (connectSelectedBleForProvision()) {
                    pendingProvisionNext = true
                }
                return
            }
            pendingProvisionNext = false
            provisionStep = ProvisionStep.DEVICE_SETTINGS
            requestDeviceSettings(ActiveConnection.BLE)
        }
    }

    fun saveMeshPreferences(
        country: String = meshCountry,
        id: String = meshId,
        password: String = passphrase,
        hopLimit: Int = maxHop.toIntOrNull() ?: 2,
        beaconInterval: Int = beaconIntervalSeconds.toIntOrNull() ?: DEFAULT_BEACON_INTERVAL_SECONDS,
    ) {
        if (deviceMode) {
            sendDeviceSettingsToDevice(
                onSuccessAction = {
                    if (provisionMode) {
                        disconnectProvisionTransport()
                        onProvisionComplete()
                    }
                },
            )
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
                    if (provisionMode && provisionStep == ProvisionStep.SELECT_BLE) {
                        if (pendingProvisionNext) {
                            pendingProvisionNext = false
                            provisionStep = ProvisionStep.DEVICE_SETTINGS
                            requestDeviceSettings(ActiveConnection.BLE)
                        } else {
                            status = "BLE ready; tap Next"
                        }
                    }
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

    LaunchedEffect(activeConnection, bleReady, provisionMode) {
        if (provisionMode && !provisionBleReady && provisionStep == ProvisionStep.DEVICE_SETTINGS) {
            provisionStep = ProvisionStep.SELECT_BLE
        }
    }

    LaunchedEffect(provisionMode) {
        if (provisionMode && activeConnection != ActiveConnection.BLE && !bleReady) {
            status = "Scanning for EdgeZ BLE devices..."
            startBleScanForProvision()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (provisionMode) {
                TopAppBar(
                    title = { Text("Provision device") },
                    navigationIcon = {
                        TextButton(onClick = { goBackProvisionStep() }) {
                            Text("Back")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (provisionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { cancelProvision() },
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = if (provisionStep == ProvisionStep.SELECT_BLE) selectedBle != null else showDeviceSettingsOnly,
                        onClick = {
                            if (provisionStep == ProvisionStep.SELECT_BLE) {
                                goNextProvisionStep()
                            } else {
                                saveMeshPreferences()
                            }
                        },
                    ) {
                        Text(if (provisionStep == ProvisionStep.SELECT_BLE) "Next" else "Save")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                if (provisionMode) {
                    Text(
                        if (provisionStep == ProvisionStep.SELECT_BLE) {
                            "Step 1 of 2: Select BLE device"
                        } else {
                            "Step 2 of 2: Configure device"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                } else {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                }
                Text("Interface: ${activeConnection.name}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                if (!provisionMode) {
                    Text(
                        "Settings source: ${if (showDeviceSettingsOnly) "Device" else "App"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
                if (!provisionMode && !showDeviceSettingsOnly) {
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
            }

            if (showDeviceSettingsOnly && !provisionMode) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = activeConnection != ActiveConnection.NONE,
                        onClick = { requestDeviceSettings() },
                    ) {
                        Text("Load")
                    }
                    Button(onClick = { disconnectActiveTransport() }) {
                        Text("Disconnect")
                    }
                }
            }

            if (!provisionMode && !showDeviceSettingsOnly) item {
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
                SettingsCard(title = if (provisionMode) "Select BLE device" else "BLE connection") {
                    if (!provisionMode) {
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
                                connectSelectedBleForProvision()
                            }) { Text("Connect") }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
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

            if (!provisionMode || showDeviceSettingsOnly) item {
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
                        Button(onClick = { regenerateDeviceIdentity() }) {
                            Text("Regenerate device user ID")
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

            if (showDeviceSettingsOnly) item {
                SettingsCard(title = "Device geofence") {
                    val selectedGeoFence = deviceGeoFences.firstOrNull { DeviceGeoFence.matchesKey(it, selectedDeviceGeoFenceKey) }
                    Text(
                        selectedGeoFence?.let { "Selected: ${it.name}" } ?: "No geofence selected",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Geo index", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            enabled = deviceGeoIndex > 0,
                            onClick = { deviceGeoIndex = (deviceGeoIndex - 1).coerceAtLeast(0) },
                        ) {
                            Text("-")
                        }
                        Text(
                            deviceGeoIndex.toString(),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedButton(
                            onClick = { deviceGeoIndex += 1 },
                        ) {
                            Text("+")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showGeoFencePage = true }) {
                            Text("Manage")
                        }
                        OutlinedButton(
                            enabled = selectedDeviceGeoFenceKey.isNotBlank(),
                            onClick = {
                                selectedDeviceGeoFenceKey = ""
                                connectionPreferences.setSelectedDeviceGeoFenceKey(null)
                                status = "Device geofence cleared"
                            },
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }

            if (showDeviceSettingsOnly) item {
                SettingsCard(title = "Device sensors") {
                    SensorTypeDropdown(
                        label = "UART/I2C connector",
                        selectedKey = deviceUartI2cSensorType,
                        options = uartI2cSensorOptions,
                        expanded = uartI2cSensorDropdownExpanded,
                        onExpandedChange = { uartI2cSensorDropdownExpanded = it },
                        onSelected = { selectedSensor ->
                            deviceUartI2cSensorType = selectedSensor.key
                            status = "UART/I2C sensor set to ${selectedSensor.label}"
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    SensorTypeDropdown(
                        label = "RS485 connector",
                        selectedKey = deviceRs485SensorType,
                        options = rs485SensorOptions,
                        expanded = rs485SensorDropdownExpanded,
                        onExpandedChange = { rs485SensorDropdownExpanded = it },
                        onSelected = { selectedSensor ->
                            deviceRs485SensorType = selectedSensor.key
                            status = "RS485 sensor set to ${selectedSensor.label}"
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { sendDeviceSettingsToDevice() }) {
                        Text("Save sensors")
                    }
                }
            }

            if (!provisionMode || showDeviceSettingsOnly) item {
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = if (showDeviceSettingsOnly) devicePassphrase else passphrase,
                        onValueChange = { value ->
                            if (showDeviceSettingsOnly) {
                                devicePassphrase = value.take(64)
                            } else {
                                passphrase = value.take(64)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Passphrase") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = if (showDeviceSettingsOnly) deviceMaxHop else maxHop,
                        onValueChange = { value ->
                            val sanitized = value.filter { it.isDigit() }.take(3)
                            if (showDeviceSettingsOnly) {
                                deviceMaxHop = sanitized
                            } else {
                                maxHop = sanitized
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Max hop") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
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
                    if (showDeviceSettingsOnly) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = deviceUpstreamWifiSsid,
                            onValueChange = { value ->
                                deviceUpstreamWifiSsid = value.take(32)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Upstream Wi-Fi SSID") },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = deviceUpstreamWifiPassphrase,
                            onValueChange = { value ->
                                deviceUpstreamWifiPassphrase = value.take(64)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Upstream Wi-Fi passphrase") },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = deviceBeaconUnicast,
                            onValueChange = { value ->
                                deviceBeaconUnicast = value
                                    .filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '-' }
                                    .take(17)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Beacon unicast MAC") },
                            singleLine = true,
                        )
                    }
                    if (!provisionMode) {
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { saveMeshPreferences() }) {
                            Text(if (deviceMode) "Save to device" else "Save settings")
                        }
                    }
                }
            }

            if (!provisionMode && !showDeviceSettingsOnly) item {
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

@Composable
private fun SensorTypeDropdown(
    label: String,
    selectedKey: String,
    options: List<DeviceSensorDefinition>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (DeviceSensorDefinition) -> Unit,
) {
    val selected = options.firstOrNull { it.key == selectedKey } ?: options.firstOrNull()
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onExpandedChange(true) },
        ) {
            Text("$label: ${selected?.label ?: selectedKey.ifBlank { "None" }}")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            options.forEach { sensor ->
                DropdownMenuItem(
                    text = { Text(sensor.label) },
                    onClick = {
                        onSelected(sensor)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

@Composable
private fun GeoFenceMaintenanceScreen(
    geoFences: List<DeviceGeoFence>,
    selectedKey: String,
    onGeoFencesChange: (List<DeviceGeoFence>) -> Unit,
    onSelectedKeyChange: (String?) -> Unit,
    onBack: () -> Unit,
) {
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("Geo fence") }
    var marker by rememberSaveable { mutableStateOf(NodeMapMarker.DEFAULT.id) }
    var alertCondition by rememberSaveable { mutableStateOf(GeoFenceAlertCondition.UNSPECIFIED.name) }
    var markerExpanded by remember { mutableStateOf(false) }
    var alertExpanded by remember { mutableStateOf(false) }

    fun editGeoFence(geoFence: DeviceGeoFence?) {
        editingKey = geoFence?.key
        name = geoFence?.name ?: "Geo fence"
        marker = geoFence?.marker ?: NodeMapMarker.DEFAULT.id
        alertCondition = geoFence?.alertCondition?.name ?: GeoFenceAlertCondition.UNSPECIFIED.name
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onBack) {
                        Text("Back")
                    }
                    Text("Geofences", style = MaterialTheme.typography.headlineMedium)
                }
            }

            item {
                SettingsCard(title = "Selected geofence") {
                    val selected = geoFences.firstOrNull { DeviceGeoFence.matchesKey(it, selectedKey) }
                    Text(selected?.name ?: "No geofence selected", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        enabled = selectedKey.isNotBlank(),
                        onClick = { onSelectedKeyChange(null) },
                    ) {
                        Text("Clear selection")
                    }
                }
            }

            item {
                SettingsCard(title = "Geofence list") {
                    if (geoFences.isEmpty()) {
                        Text("No geofences saved.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        geoFences.forEach { geoFence ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(geoFence.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${NodeMapMarker.fromId(geoFence.marker).label} · ${geoFence.alertCondition.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onSelectedKeyChange(geoFence.key) }) {
                                        Text(if (DeviceGeoFence.matchesKey(geoFence, selectedKey)) "Selected" else "Select")
                                    }
                                    OutlinedButton(onClick = { editGeoFence(geoFence) }) {
                                        Text("Edit")
                                    }
                                    OutlinedButton(onClick = {
                                        onGeoFencesChange(geoFences.filterNot { it.key == geoFence.key })
                                    }) {
                                        Text("Delete")
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }

            item {
                SettingsCard(title = if (editingKey == null) "New geofence" else "Edit geofence") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { markerExpanded = true },
                        ) {
                            Text("Marker: ${NodeMapMarker.fromId(marker).label}")
                        }
                        DropdownMenu(
                            expanded = markerExpanded,
                            onDismissRequest = { markerExpanded = false },
                        ) {
                            NodeMapMarker.values().forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        marker = option.id
                                        markerExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val selectedAlert = GeoFenceAlertCondition.fromName(alertCondition)
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { alertExpanded = true },
                        ) {
                            Text("Alert: ${selectedAlert.label}")
                        }
                        DropdownMenu(
                            expanded = alertExpanded,
                            onDismissRequest = { alertExpanded = false },
                        ) {
                            GeoFenceAlertCondition.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        alertCondition = option.name
                                        alertExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val existing = geoFences.firstOrNull { it.key == editingKey }
                            val updated = if (existing == null) {
                                DeviceGeoFence.create(
                                    name = name,
                                    marker = marker,
                                    alertCondition = GeoFenceAlertCondition.fromName(alertCondition),
                                )
                            } else {
                                existing.copy(
                                    name = name.ifBlank { "Geo fence" }.take(64),
                                    marker = NodeMapMarker.normalize(marker),
                                    alertCondition = GeoFenceAlertCondition.fromName(alertCondition),
                                )
                            }
                            val next = if (existing == null) {
                                geoFences + updated
                            } else {
                                geoFences.map { if (it.key == updated.key) updated else it }
                            }
                            onGeoFencesChange(next)
                            onSelectedKeyChange(updated.key)
                            editGeoFence(null)
                        }) {
                            Text("Save")
                        }
                        OutlinedButton(onClick = { editGeoFence(null) }) {
                            Text("New")
                        }
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
private fun ProvisioningPreview() {
    EdgeZTheme {
        val context = LocalContext.current.applicationContext
        ProvisioningScreen(
            client = EdgezUsbClient(context),
            bleClient = EdgezBleClient(context),
            activeConnection = ActiveConnection.NONE,
            edgeZDatabase = EdgeZDatabase(context),
            shareLocation = false,
            onShareLocationChange = {},
            onTransportConnectionChange = { _, _ -> },
            onTransportDisconnect = {},
            onProvisionComplete = {},
        )
    }
}
