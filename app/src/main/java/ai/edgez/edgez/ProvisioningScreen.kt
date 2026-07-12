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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
    MODE,
    IDENTITY,
    NETWORK,
    LOCATION,
    GEO_FENCE,
    SENSOR,
    SLEEP_MODE,
}

private const val DEVICE_MODE_UNSET = -1
private const val DEVICE_MODE_DEVICE = 0
private const val DEVICE_MODE_RELAY = 1

private fun parseIpv4Address(input: String): Long {
    val parts = input.trim().split(".")
    if (parts.size != 4) return 0L
    var value = 0L
    for (part in parts) {
        val byte = part.toIntOrNull() ?: return 0L
        if (byte !in 0..255) return 0L
        value = (value shl 8) or byte.toLong()
    }
    return value
}

private fun formatIpv4Address(value: Long): String {
    val masked = value and 0xffffffffL
    if (masked == 0L) return ""
    return listOf(
        (masked ushr 24) and 0xff,
        (masked ushr 16) and 0xff,
        (masked ushr 8) and 0xff,
        masked and 0xff,
    ).joinToString(".")
}

private data class BeaconMulticastOption(
    val address: String,
    val label: String,
)

private val beaconMulticastOptions = listOf(
    BeaconMulticastOption("", "Not set"),
    BeaconMulticastOption("224.0.0.1", "224.0.0.1 - all hosts"),
    BeaconMulticastOption("224.0.0.251", "224.0.0.251 - mDNS"),
    BeaconMulticastOption("239.255.255.250", "239.255.255.250 - SSDP"),
    BeaconMulticastOption("239.255.0.1", "239.255.0.1 - site-local"),
    BeaconMulticastOption("239.192.0.1", "239.192.0.1 - organization-local"),
)

private fun provisionHaLowFrequenciesKHz(country: String, bandwidthMHz: Int): List<Int> = when (country) {
    "US" -> when (bandwidthMHz) {
        1 -> (902500..927500 step 1000).toList()
        2 -> (903000..927000 step 2000).toList()
        4 -> (904000..926000 step 4000).toList()
        8 -> (908000..924000 step 8000).toList()
        else -> emptyList()
    }
    "JP" -> when (bandwidthMHz) {
        1 -> (920500..927500 step 1000).toList()
        2 -> (921000..927000 step 2000).toList()
        4 -> listOf(922000, 926000)
        8 -> listOf(924000)
        else -> emptyList()
    }
    "EU" -> when (bandwidthMHz) {
        1 -> (863500..867500 step 1000).toList()
        2 -> listOf(864000, 866000)
        4 -> listOf(865000)
        else -> emptyList()
    }
    else -> emptyList()
}

private fun provisionHaLowBandwidthOptions(country: String): List<Int> =
    listOf(1, 2, 4, 8).filter { provisionHaLowFrequenciesKHz(country, it).isNotEmpty() }

private fun provisionHaLowFrequencyLabel(country: String, frequencyKHz: Int): String {
    val baseKHz = when (country) {
        "US" -> 902000
        "JP" -> 920000
        "EU" -> 863000
        else -> frequencyKHz
    }
    val channel = (frequencyKHz - baseKHz) / 500
    return "Channel $channel - ${frequencyKHz / 1000.0} MHz"
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
    onProvisionCancel: () -> Unit,
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
        onProvisionCancel = onProvisionCancel,
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
    onProvisionCancel: () -> Unit,
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
    var bandwidthDropdownExpanded by remember { mutableStateOf(false) }
    var frequencyDropdownExpanded by remember { mutableStateOf(false) }
    var markerDropdownExpanded by remember { mutableStateOf(false) }
    var meshCountry by rememberSaveable { mutableStateOf(connectionPreferences.getMeshCountry()) }
    var meshId by rememberSaveable { mutableStateOf(connectionPreferences.getMeshId()) }
    var passphrase by rememberSaveable { mutableStateOf(connectionPreferences.getMeshPassphrase()) }
    var maxHop by rememberSaveable { mutableStateOf(connectionPreferences.getMeshMaxHop().toString()) }
    var meshBandwidthMHz by rememberSaveable { mutableStateOf(connectionPreferences.getMeshBandwidthMHz()) }
    var meshFrequencyKHz by rememberSaveable { mutableStateOf(connectionPreferences.getMeshFrequencyKHz()) }
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
    var deviceBeaconMulticast by rememberSaveable { mutableStateOf("") }
    var deviceUpstreamEnabled by rememberSaveable { mutableStateOf(false) }
    var beaconMulticastDropdownExpanded by remember { mutableStateOf(false) }
    var deviceSleepModeEnabled by rememberSaveable { mutableStateOf(false) }
    var deviceShareLocation by rememberSaveable { mutableStateOf(false) }
    var deviceLatitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var deviceLongitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var deviceGeoFences by remember { mutableStateOf(loadDeviceGeoFences()) }
    var selectedDeviceGeoFenceKey by rememberSaveable { mutableStateOf(connectionPreferences.getSelectedDeviceGeoFenceKey() ?: "") }
    var deviceGeoFenceEnabled by rememberSaveable { mutableStateOf(false) }
    var showGeoFencePage by rememberSaveable { mutableStateOf(false) }
    var deviceGeoIndex by rememberSaveable { mutableStateOf(0) }
    var deviceUartI2cSensorType by rememberSaveable { mutableStateOf("") }
    var deviceRs485SensorType by rememberSaveable { mutableStateOf("") }
    var deviceSensorsEnabled by rememberSaveable { mutableStateOf(false) }
    var lastSavedDeviceUartI2cSensorType by rememberSaveable { mutableStateOf("") }
    var lastSavedDeviceRs485SensorType by rememberSaveable { mutableStateOf("") }
    var uartI2cSensorDropdownExpanded by remember { mutableStateOf(false) }
    var rs485SensorDropdownExpanded by remember { mutableStateOf(false) }
    var autoReplayReceivedVoice by rememberSaveable { mutableStateOf(connectionPreferences.getAutoReplayReceivedVoice()) }
    var showDebugPopup by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connect the ESP32-S3 USB port, then scan.") }
    var provisionStep by rememberSaveable { mutableStateOf(ProvisionStep.SELECT_BLE) }
    var pendingProvisionNext by rememberSaveable { mutableStateOf(false) }
    var showDeviceSettingsRetryDialog by rememberSaveable { mutableStateOf(false) }
    var provisionDeviceMode by rememberSaveable { mutableStateOf(DEVICE_MODE_UNSET) }
    var isSavingProvisionSettings by rememberSaveable { mutableStateOf(false) }
    var isLoadingDeviceSettings by rememberSaveable { mutableStateOf(false) }
    var deviceSettingsLoaded by rememberSaveable { mutableStateOf(false) }
    val activity = context as? ComponentActivity
    val currentOnTransportConnectionChange by rememberUpdatedState(onTransportConnectionChange)
    val provisionBleReady = provisionMode && activeConnection == ActiveConnection.BLE && bleReady
    val showProvisionDeviceSettings = provisionMode && provisionStep != ProvisionStep.SELECT_BLE && provisionBleReady
    val deviceMode = provisionDeviceMode == DEVICE_MODE_DEVICE
    val showDeviceSettingsOnly = showProvisionDeviceSettings
    val provisionStepNumber = when (provisionStep) {
        ProvisionStep.SELECT_BLE -> 1
        ProvisionStep.MODE -> 2
        ProvisionStep.IDENTITY -> 3
        ProvisionStep.NETWORK -> 4
        ProvisionStep.LOCATION -> 5
        ProvisionStep.GEO_FENCE -> 6
        ProvisionStep.SENSOR -> 7
        ProvisionStep.SLEEP_MODE -> 8
    }
    val provisionStepTitle = when (provisionStep) {
        ProvisionStep.SELECT_BLE -> "Select BLE device"
        ProvisionStep.MODE -> "Device mode"
        ProvisionStep.IDENTITY -> "Name, ID, and keys"
        ProvisionStep.LOCATION -> "Location"
        ProvisionStep.GEO_FENCE -> "Geo fence"
        ProvisionStep.SENSOR -> "Sensor"
        ProvisionStep.NETWORK -> "Network"
        ProvisionStep.SLEEP_MODE -> "Sleep mode"
    }
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
        meshBandwidthMHz = connectionPreferences.getMeshBandwidthMHz()
        meshFrequencyKHz = connectionPreferences.getMeshFrequencyKHz()
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
        provisionDeviceMode = DEVICE_MODE_UNSET
        deviceSettingsLoaded = false
        isLoadingDeviceSettings = false
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
        deviceBeaconMulticast = formatIpv4Address(settings.beaconUnicast)
        deviceUpstreamEnabled = settings.upstreamWifiSsid.isNotBlank() ||
            settings.upstreamWifiPassphrase.isNotBlank() ||
            settings.beaconUnicast != 0L
        deviceSleepModeEnabled = settings.sleepModeEnabled
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
            deviceGeoFenceEnabled = true
            edgeZDatabase.upsertGeoFence(geoFence)
            deviceGeoFences = edgeZDatabase.getGeoFences()
            selectedDeviceGeoFenceKey = geoFence.key
            connectionPreferences.setSelectedDeviceGeoFenceKey(geoFence.key)
        } ?: run {
            deviceGeoFenceEnabled = false
        }
        deviceGeoIndex = settings.geoIndex.coerceAtLeast(0)
        val loadedUartI2cSensorType = settings.uartI2cSensorType.take(32)
        val loadedRs485SensorType = settings.rs485SensorType.take(32)
        deviceUartI2cSensorType = loadedUartI2cSensorType
        deviceRs485SensorType = loadedRs485SensorType
        deviceSensorsEnabled = loadedUartI2cSensorType.isNotBlank() || loadedRs485SensorType.isNotBlank()
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
        provisionDeviceMode = if (settings.deviceModeEnabled) DEVICE_MODE_DEVICE else DEVICE_MODE_RELAY
        deviceSettingsLoaded = true
        isLoadingDeviceSettings = false
        if (pendingProvisionNext && provisionStep == ProvisionStep.SELECT_BLE) {
            pendingProvisionNext = false
            provisionStep = ProvisionStep.MODE
        }
        status = "Device settings loaded"
    }

    fun currentDeviceSettings(enabled: Boolean = deviceMode): DeviceSettings {
        val identity = ensureDeviceIdentity()
        val selectedGeoFence = deviceGeoFences
            .firstOrNull { DeviceGeoFence.matchesKey(it, selectedDeviceGeoFenceKey) }
            .takeIf { deviceGeoFenceEnabled }
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
            uartI2cSensorType = if (deviceSensorsEnabled) deviceUartI2cSensorType.take(32) else "",
            rs485SensorType = if (deviceSensorsEnabled) deviceRs485SensorType.take(32) else "",
            geoIndex = deviceGeoIndex.coerceAtLeast(0),
            upstreamWifiSsid = if (deviceUpstreamEnabled) deviceUpstreamWifiSsid.take(32) else "",
            upstreamWifiPassphrase = if (deviceUpstreamEnabled) deviceUpstreamWifiPassphrase.take(64) else "",
            beaconUnicast = if (deviceUpstreamEnabled) parseIpv4Address(deviceBeaconMulticast) else 0L,
            sleepModeEnabled = deviceSleepModeEnabled,
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
        isLoadingDeviceSettings = true
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
                if (result.isFailure) {
                    isLoadingDeviceSettings = false
                }
            }
        }
    }

    fun sendDeviceSettingsToDevice(
        settings: DeviceSettings = currentDeviceSettings(),
        connection: ActiveConnection = activeConnection,
        label: String = "Device settings",
        forceSaveScript: Boolean = false,
        onSuccessAction: (() -> Unit)? = null,
        onFailureAction: (() -> Unit)? = null,
    ) {
        if (connection == ActiveConnection.NONE) {
            status = "$label saved locally; connect USB or BLE to sync"
            onFailureAction?.invoke()
            return
        }
        status = "Saving $label..."
        executor.execute {
            val uartI2cSensorType = settings.uartI2cSensorType.take(32)
            val rs485SensorType = settings.rs485SensorType.take(32)
            val sensorSelectionChanged = uartI2cSensorType != lastSavedDeviceUartI2cSensorType ||
                rs485SensorType != lastSavedDeviceRs485SensorType
            val scriptConfigs = if (sensorSelectionChanged || forceSaveScript) {
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
            if (result.isSuccess) {
                result = when (connection) {
                    ActiveConnection.USB -> client.sendDeviceSettings(settings)
                    ActiveConnection.BLE -> bleClient.sendDeviceSettings(settings)
                    ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                }
            }
            if (result.isSuccess) {
                for (scriptConfig in scriptConfigs) {
                    result = when (connection) {
                        ActiveConnection.USB -> client.sendDeviceSensorScript(scriptConfig)
                        ActiveConnection.BLE -> bleClient.sendDeviceSensorScript(scriptConfig)
                        ActiveConnection.NONE -> Result.failure(IllegalStateException("No active connection"))
                    }
                    if (result.isFailure) break
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
                    onFailure = {
                        onFailureAction?.invoke()
                        it.message ?: "$label save failed"
                    },
                )
            }
        }
    }

    fun disconnectActiveTransport() {
        val connection = activeConnection
        DeviceModeState.enabled = false
        reloadAppSettingsFromPreferences()
        provisionDeviceMode = DEVICE_MODE_UNSET
        deviceSettingsLoaded = false
        isLoadingDeviceSettings = false
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
        provisionDeviceMode = DEVICE_MODE_UNSET
        deviceSettingsLoaded = false
        isLoadingDeviceSettings = false
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
        provisionDeviceMode = DEVICE_MODE_UNSET
        deviceSettingsLoaded = false
        isLoadingDeviceSettings = false
        pendingProvisionNext = false
        onProvisionCancel()
    }

    fun goBackProvisionStep() {
        provisionStep = when (provisionStep) {
            ProvisionStep.SELECT_BLE -> {
                cancelProvision()
                return
            }
            ProvisionStep.MODE -> ProvisionStep.SELECT_BLE
            ProvisionStep.IDENTITY -> ProvisionStep.MODE
            ProvisionStep.NETWORK -> ProvisionStep.IDENTITY
            ProvisionStep.LOCATION -> ProvisionStep.NETWORK
            ProvisionStep.GEO_FENCE -> ProvisionStep.LOCATION
            ProvisionStep.SENSOR -> ProvisionStep.GEO_FENCE
            ProvisionStep.SLEEP_MODE -> ProvisionStep.SENSOR
        }
        if (provisionStep == ProvisionStep.SELECT_BLE) {
            provisionDeviceMode = DEVICE_MODE_UNSET
            deviceSettingsLoaded = false
            isLoadingDeviceSettings = false
            pendingProvisionNext = false
        }
        status = if (provisionStep == ProvisionStep.SELECT_BLE) {
            "Select an EdgeZ BLE device"
        } else {
            "Device settings loaded"
        }
    }

    fun goNextProvisionStep() {
        when (provisionStep) {
            ProvisionStep.SELECT_BLE -> {
                if (!provisionBleReady) {
                    if (connectSelectedBleForProvision()) {
                        pendingProvisionNext = true
                    }
                    return
                }
                if (!deviceSettingsLoaded) {
                    showDeviceSettingsRetryDialog = true
                    return
                }
                if (provisionDeviceMode == DEVICE_MODE_UNSET) {
                    status = "Select Device or Relay mode"
                    return
                }
                pendingProvisionNext = false
                provisionStep = ProvisionStep.MODE
            }
            ProvisionStep.MODE -> {
                if (provisionDeviceMode == DEVICE_MODE_UNSET) {
                    status = "Select Device or Relay mode"
                    return
                }
                provisionStep = ProvisionStep.NETWORK
            }
            ProvisionStep.IDENTITY -> provisionStep = ProvisionStep.NETWORK
            ProvisionStep.NETWORK -> {
                connectionPreferences.setMeshCredentials(
                    meshCountry,
                    meshId,
                    passphrase,
                    maxHop.toIntOrNull() ?: 2,
                    beaconIntervalSeconds.toIntOrNull() ?: DEFAULT_BEACON_INTERVAL_SECONDS,
                    meshBandwidthMHz,
                    meshFrequencyKHz,
                )
                provisionStep = ProvisionStep.LOCATION
            }
            ProvisionStep.LOCATION -> provisionStep = ProvisionStep.GEO_FENCE
            ProvisionStep.GEO_FENCE -> provisionStep = ProvisionStep.SENSOR
            ProvisionStep.SENSOR -> provisionStep = ProvisionStep.SLEEP_MODE
            ProvisionStep.SLEEP_MODE -> sendDeviceSettingsToDevice(
                forceSaveScript = true,
                onSuccessAction = {
                    isSavingProvisionSettings = false
                    disconnectProvisionTransport()
                    onProvisionComplete()
                },
                onFailureAction = {
                    isSavingProvisionSettings = false
                },
            )
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
                            requestDeviceSettings(ActiveConnection.BLE)
                        } else if (!deviceSettingsLoaded && !isLoadingDeviceSettings) {
                            requestDeviceSettings(ActiveConnection.BLE)
                        } else {
                            status = "BLE ready; tap Next"
                        }
                    }
                    currentOnTransportConnectionChange(ActiveConnection.BLE, true)
                } else if (line.startsWith("CONN") && line.contains("state=0") || line == "CLOSE") {
                    bleReady = false
                    provisionDeviceMode = DEVICE_MODE_UNSET
                    deviceSettingsLoaded = false
                    isLoadingDeviceSettings = false
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
        if (provisionMode && !provisionBleReady && provisionStep != ProvisionStep.SELECT_BLE) {
            provisionStep = ProvisionStep.SELECT_BLE
            provisionDeviceMode = DEVICE_MODE_UNSET
            deviceSettingsLoaded = false
            isLoadingDeviceSettings = false
            pendingProvisionNext = false
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
                    title = { Text("Provisioning") },
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
                        enabled = if (provisionStep == ProvisionStep.SELECT_BLE) {
                            selectedBle != null
                        } else if (provisionStep == ProvisionStep.SLEEP_MODE) {
                            showDeviceSettingsOnly && !isSavingProvisionSettings
                        } else if (provisionStep == ProvisionStep.MODE) {
                            showDeviceSettingsOnly && provisionDeviceMode != DEVICE_MODE_UNSET
                        } else {
                            showDeviceSettingsOnly
                        },
                        onClick = {
                            if (!isSavingProvisionSettings) {
                                isSavingProvisionSettings = provisionStep == ProvisionStep.SLEEP_MODE
                                goNextProvisionStep()
                            }
                        },
                    ) {
                        if (provisionStep == ProvisionStep.SLEEP_MODE && isSavingProvisionSettings) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text("Saving")
                            }
                        } else {
                            Text(if (provisionStep == ProvisionStep.SLEEP_MODE) "Save" else "Next")
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (showDeviceSettingsRetryDialog) {
            AlertDialog(
                onDismissRequest = { showDeviceSettingsRetryDialog = false },
                title = { Text("Device settings not loaded") },
                text = { Text("Could not get device settings yet. Retry loading and try again.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeviceSettingsRetryDialog = false
                            pendingProvisionNext = true
                            requestDeviceSettings(ActiveConnection.BLE)
                        },
                    ) {
                        Text("Retry")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeviceSettingsRetryDialog = false },
                    ) {
                        Text("Cancel")
                    }
                },
            )
        }
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
                        "Step $provisionStepNumber of 7: $provisionStepTitle",
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

            if (!provisionMode || (showDeviceSettingsOnly && provisionStep == ProvisionStep.MODE)) item {
                if (provisionMode) {
                    SettingsCard(title = "Device mode") {
                        Text("Select mode", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Device mode enables peer behavior; relay mode enables relay behavior.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    provisionDeviceMode = DEVICE_MODE_DEVICE
                                    status = "Device mode selected"
                                },
                            ) {
                                Text("Device")
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    provisionDeviceMode = DEVICE_MODE_RELAY
                                    status = "Relay mode selected"
                                },
                            ) {
                                Text("Relay")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when (provisionDeviceMode) {
                                DEVICE_MODE_DEVICE -> "Selected: Device"
                                DEVICE_MODE_RELAY -> "Selected: Relay"
                                else -> "Selection required before continuing"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                SettingsCard(title = "Device user") {
                    Text("ID", style = MaterialTheme.typography.titleSmall)
                    Text(
                        deviceIdentity?.userUuid ?: "Not loaded",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deviceUserName,
                        onValueChange = { value ->
                            deviceUserName = value.take(64)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("X25519 public key", style = MaterialTheme.typography.titleSmall)
                    Text(
                        deviceIdentity?.publicKey?.let(::formatHex) ?: "Not loaded",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("X25519 private key", style = MaterialTheme.typography.titleSmall)
                    Text(deviceIdentity?.privateKey?.let(::formatHex) ?: "Not loaded", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { regenerateDeviceIdentity() }) {
                        Text("Regenerate ID and key pair")
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { markerDropdownExpanded = true },
                        ) {
                            Text("Marker: ${NodeMapMarker.fromId(deviceUserMarker).label}")
                        }
                        DropdownMenu(
                            expanded = markerDropdownExpanded,
                            onDismissRequest = { markerDropdownExpanded = false },
                        ) {
                            markerOptions.forEach { marker ->
                                DropdownMenuItem(
                                    text = { Text(marker.label) },
                                    onClick = {
                                        deviceUserMarker = marker.id
                                        markerDropdownExpanded = false
                                        status = "Marker set to ${marker.label}"
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (!provisionMode || (showDeviceSettingsOnly && provisionStep == ProvisionStep.LOCATION)) item {
                SettingsCard(title = if (showDeviceSettingsOnly) "Device location" else "Location") {
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
                        Button(
                            enabled = deviceShareLocation,
                            onClick = { refreshDeviceLocation() },
                        ) {
                            Text("Refresh location")
                        }
                    }
                }
            }

            if (showDeviceSettingsOnly && provisionStep == ProvisionStep.GEO_FENCE) item {
                SettingsCard(title = "Device geofence") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable geofence", style = MaterialTheme.typography.titleSmall)
                            Text("Include a geofence in device beacons", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = deviceGeoFenceEnabled,
                            onCheckedChange = { enabled ->
                                deviceGeoFenceEnabled = enabled
                                status = if (enabled) "Device geofence enabled" else "Device geofence disabled"
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
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
                            enabled = deviceGeoFenceEnabled && deviceGeoIndex > 0,
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
                            enabled = deviceGeoFenceEnabled,
                            onClick = { deviceGeoIndex += 1 },
                        ) {
                            Text("+")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = deviceGeoFenceEnabled,
                            onClick = { showGeoFencePage = true },
                        ) {
                            Text("Manage")
                        }
                        OutlinedButton(
                            enabled = deviceGeoFenceEnabled && selectedDeviceGeoFenceKey.isNotBlank(),
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

            if (showDeviceSettingsOnly && provisionStep == ProvisionStep.SENSOR) item {
                SettingsCard(title = "Device sensors") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable sensors", style = MaterialTheme.typography.titleSmall)
                            Text("Configure device sensor connectors", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = deviceSensorsEnabled,
                            onCheckedChange = { enabled ->
                                deviceSensorsEnabled = enabled
                                status = if (enabled) "Device sensors enabled" else "Device sensors disabled"
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    SensorTypeDropdown(
                        label = "UART/I2C connector",
                        selectedKey = deviceUartI2cSensorType,
                        options = uartI2cSensorOptions,
                        expanded = uartI2cSensorDropdownExpanded,
                        enabled = deviceSensorsEnabled,
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
                        enabled = deviceSensorsEnabled,
                        onExpandedChange = { rs485SensorDropdownExpanded = it },
                        onSelected = { selectedSensor ->
                            deviceRs485SensorType = selectedSensor.key
                            status = "RS485 sensor set to ${selectedSensor.label}"
                        },
                    )
                }
            }

            if (!provisionMode || (showDeviceSettingsOnly && provisionStep == ProvisionStep.NETWORK)) item {
                SettingsCard(title = if (showDeviceSettingsOnly) "Network" else "Mesh network") {
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
                                        val bandwidths = provisionHaLowBandwidthOptions(country)
                                        meshBandwidthMHz = meshBandwidthMHz.takeIf { it in bandwidths }
                                            ?: bandwidths.first()
                                        val frequencies = provisionHaLowFrequenciesKHz(country, meshBandwidthMHz)
                                        meshFrequencyKHz = meshFrequencyKHz.takeIf { it in frequencies }
                                            ?: frequencies.first()
                                        countryDropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { bandwidthDropdownExpanded = true },
                        ) {
                            Text("Bandwidth: $meshBandwidthMHz MHz")
                        }
                        DropdownMenu(
                            expanded = bandwidthDropdownExpanded,
                            onDismissRequest = { bandwidthDropdownExpanded = false },
                        ) {
                            provisionHaLowBandwidthOptions(meshCountry).forEach { bandwidth ->
                                DropdownMenuItem(
                                    text = { Text("$bandwidth MHz") },
                                    onClick = {
                                        meshBandwidthMHz = bandwidth
                                        val frequencies = provisionHaLowFrequenciesKHz(meshCountry, bandwidth)
                                        meshFrequencyKHz = meshFrequencyKHz.takeIf { it in frequencies }
                                            ?: frequencies.first()
                                        bandwidthDropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { frequencyDropdownExpanded = true },
                        ) {
                            Text("Frequency: ${provisionHaLowFrequencyLabel(meshCountry, meshFrequencyKHz)}")
                        }
                        DropdownMenu(
                            expanded = frequencyDropdownExpanded,
                            onDismissRequest = { frequencyDropdownExpanded = false },
                        ) {
                            provisionHaLowFrequenciesKHz(meshCountry, meshBandwidthMHz).forEach { frequency ->
                                DropdownMenuItem(
                                    text = { Text(provisionHaLowFrequencyLabel(meshCountry, frequency)) },
                                    onClick = {
                                        meshFrequencyKHz = frequency
                                        frequencyDropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
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
                    if (!provisionMode) {
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { saveMeshPreferences() }) {
                            Text(if (deviceMode) "Save to device" else "Save settings")
                        }
                    }
                }
            }

            if (showDeviceSettingsOnly && provisionStep == ProvisionStep.SLEEP_MODE) item {
                SettingsCard(title = "Sleep mode") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable sleep mode", style = MaterialTheme.typography.titleSmall)
                            Text("Allow the device to enter low-power sleep", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = deviceSleepModeEnabled,
                            onCheckedChange = { enabled ->
                                deviceSleepModeEnabled = enabled
                                status = if (enabled) "Sleep mode enabled" else "Sleep mode disabled"
                            },
                        )
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
    enabled: Boolean = true,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (DeviceSensorDefinition) -> Unit,
) {
    val selected = options.firstOrNull { it.key == selectedKey } ?: options.firstOrNull()
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
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
            onProvisionCancel = {},
            onProvisionComplete = {},
        )
    }
}
