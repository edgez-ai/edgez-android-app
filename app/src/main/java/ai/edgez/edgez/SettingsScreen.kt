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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.ble.BleCandidate
import ai.edgez.edgez.ble.EdgezBleClient
import ai.edgez.edgez.ui.theme.EdgeZTheme
import ai.edgez.edgez.usb.ACTION_USB_PERMISSION
import ai.edgez.edgez.usb.DeviceSettings
import ai.edgez.edgez.usb.EdgezUsbClient
import ai.edgez.edgez.usb.HaLowInterfaceStatus
import ai.edgez.edgez.usb.UsbCandidate
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.UUID
import org.json.JSONObject

private enum class SettingsProvisionStep {
    SELECT_BLE,
    DEVICE_SETTINGS,
}

private enum class SettingsTab(val label: String) {
    USER("User"),
    MESH_NETWORK("Mesh Network"),
    OTHERS("Others"),
}

private fun haLowFrequenciesKHz(country: String, bandwidthMHz: Int): List<Int> = when (country) {
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

private fun haLowBandwidthOptions(country: String): List<Int> =
    listOf(1, 2, 4, 8).filter { haLowFrequenciesKHz(country, it).isNotEmpty() }

private fun haLowFrequencyLabel(country: String, frequencyKHz: Int): String {
    val baseKHz = when (country) {
        "US" -> 902000
        "JP" -> 920000
        "EU" -> 863000
        else -> frequencyKHz
    }
    val channel = (frequencyKHz - baseKHz) / 500
    return "Channel $channel - ${frequencyKHz / 1000.0} MHz"
}

private const val SHOW_LIBP2P_SETTINGS = false
private const val OTA_MANIFEST_URL = "https://www.edgez.ai/api/ota/firmware"

private data class OtaFirmwareRelease(
    val version: String,
    val size: Int,
    val url: String,
)

private fun isNewerFirmwareVersion(current: String, available: String): Boolean {
    fun components(value: String): List<Int> = value.removePrefix("v")
        .split('.', '-', '_')
        .mapNotNull { it.toIntOrNull() }
    val currentParts = components(current)
    val availableParts = components(available)
    if (currentParts.isEmpty() || availableParts.isEmpty()) return current != available
    val count = maxOf(currentParts.size, availableParts.size)
    for (index in 0 until count) {
        val left = currentParts.getOrElse(index) { 0 }
        val right = availableParts.getOrElse(index) { 0 }
        if (left != right) return right > left
    }
    return false
}

private fun fetchOtaFirmwareRelease(): OtaFirmwareRelease {
    val connection = (URL(OTA_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        requestMethod = "GET"
    }
    try {
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Firmware check failed: HTTP ${connection.responseCode}")
        }
        val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        return OtaFirmwareRelease(
            version = json.getString("version"),
            size = json.getInt("size"),
            url = json.getString("url"),
        )
    } finally {
        connection.disconnect()
    }
}

private enum class Libp2pExtraServerOption(val label: String) {
    NONE("IPFS default"),
    EDGEZ("EdgeZ"),
    CUSTOM("Custom"),
}

private fun libp2pExtraServerOptionFor(peers: String): Libp2pExtraServerOption {
    val normalized = normalizeLibp2pBootstrapPeers(peers)
    val defaultPeers = normalizeLibp2pBootstrapPeers(DEFAULT_LIBP2P_BOOTSTRAP_PEERS)
    val edgezPeers = normalizeLibp2pBootstrapPeers(EDGEZ_LIBP2P_BOOTSTRAP_PEERS)
    return when {
        normalized == defaultPeers -> Libp2pExtraServerOption.NONE
        normalized == edgezPeers -> Libp2pExtraServerOption.EDGEZ
        else -> Libp2pExtraServerOption.CUSTOM
    }
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
fun SettingsScreen(
    client: EdgezUsbClient,
    bleClient: EdgezBleClient,
    activeConnection: ActiveConnection,
    haLowStatus: HaLowInterfaceStatus?,
    edgeZDatabase: EdgeZDatabase,
    shareLocation: Boolean,
    onLibp2pMeshSettingsSaved: () -> Unit = {},
    onShareLocationChange: (Boolean) -> Unit,
    onTransportConnectionChange: (ActiveConnection, Boolean) -> Unit,
    onTransportDisconnect: (ActiveConnection) -> Unit,
) {
    SettingsContent(
        client = client,
        bleClient = bleClient,
        activeConnection = activeConnection,
        haLowStatus = haLowStatus,
        edgeZDatabase = edgeZDatabase,
        shareLocation = shareLocation,
        provisionMode = false,
        onLibp2pMeshSettingsSaved = onLibp2pMeshSettingsSaved,
        onShareLocationChange = onShareLocationChange,
        onTransportConnectionChange = onTransportConnectionChange,
        onTransportDisconnect = onTransportDisconnect,
        onProvisionComplete = {},
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    client: EdgezUsbClient,
    bleClient: EdgezBleClient,
    activeConnection: ActiveConnection,
    haLowStatus: HaLowInterfaceStatus?,
    edgeZDatabase: EdgeZDatabase,
    shareLocation: Boolean,
    provisionMode: Boolean,
    onLibp2pMeshSettingsSaved: () -> Unit = {},
    onShareLocationChange: (Boolean) -> Unit,
    onTransportConnectionChange: (ActiveConnection, Boolean) -> Unit,
    onTransportDisconnect: (ActiveConnection) -> Unit,
    onProvisionComplete: () -> Unit,
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
    var showBlePicker by rememberSaveable { mutableStateOf(false) }
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
    var passphraseVisible by rememberSaveable { mutableStateOf(false) }
    var maxHop by rememberSaveable { mutableStateOf(connectionPreferences.getMeshMaxHop().toString()) }
    var meshBandwidthMHz by rememberSaveable { mutableStateOf(connectionPreferences.getMeshBandwidthMHz()) }
    var meshFrequencyKHz by rememberSaveable { mutableStateOf(connectionPreferences.getMeshFrequencyKHz()) }
    var libp2pMeshEnabled by rememberSaveable { mutableStateOf(connectionPreferences.getLibp2pMeshEnabled()) }
    var libp2pPublicDht by rememberSaveable { mutableStateOf(connectionPreferences.getLibp2pPublicDht()) }
    var libp2pBootstrapPeers by rememberSaveable {
        mutableStateOf(connectionPreferences.getLibp2pBootstrapPeers().joinToString("\n"))
    }
    var libp2pExtraServerDropdownExpanded by remember { mutableStateOf(false) }
    var libp2pExtraServerOption by rememberSaveable {
        mutableStateOf(libp2pExtraServerOptionFor(libp2pBootstrapPeers))
    }
    val isCustomBootstrapServerMissing = libp2pExtraServerOption == Libp2pExtraServerOption.CUSTOM && libp2pBootstrapPeers.trim()
        .isBlank()
    var beaconIntervalSeconds by rememberSaveable {
        mutableStateOf(connectionPreferences.getBeaconIntervalSeconds().toString())
    }
    var userIdentity by remember { mutableStateOf(connectionPreferences.getOrCreateUserIdentity()) }
    var userName by rememberSaveable { mutableStateOf(userIdentity.name) }
    var userMarker by rememberSaveable { mutableStateOf(connectionPreferences.getUserMarker()) }
    var deviceIdentity by remember { mutableStateOf<UserIdentity?>(null) }
    var deviceUserName by rememberSaveable { mutableStateOf("EdgeZ Device") }
    var deviceUserMarker by rememberSaveable { mutableStateOf(NodeMapMarker.DEFAULT.id) }
    var deviceType by rememberSaveable { mutableStateOf(EdgeZDeviceType.RELAY) }
    var deviceMeshId by rememberSaveable { mutableStateOf("edgez") }
    var devicePassphrase by rememberSaveable { mutableStateOf(connectionPreferences.getMeshPassphrase()) }
    var deviceMaxHop by rememberSaveable { mutableStateOf(connectionPreferences.getMeshMaxHop().toString()) }
    var deviceBeaconIntervalSeconds by rememberSaveable { mutableStateOf(DEFAULT_BEACON_INTERVAL_SECONDS.toString()) }
    var deviceUpstreamWifiSsid by rememberSaveable { mutableStateOf("") }
    var deviceUpstreamWifiPassphrase by rememberSaveable { mutableStateOf("") }
    var deviceUpstreamWifiPassphraseVisible by rememberSaveable { mutableStateOf(false) }
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
    var autoAnswerVoiceCalls by rememberSaveable { mutableStateOf(connectionPreferences.getAutoAnswerVoiceCalls()) }
    var selectedBleAddress by rememberSaveable { mutableStateOf(connectionPreferences.getSelectedBleAddress()) }
    var selectedBleLabel by rememberSaveable { mutableStateOf(connectionPreferences.getSelectedBleLabel()) }
    var bleAutoConnect by rememberSaveable { mutableStateOf(connectionPreferences.getBleAutoConnect()) }
    var otaRelease by remember { mutableStateOf<OtaFirmwareRelease?>(null) }
    var otaCheckInProgress by rememberSaveable { mutableStateOf(false) }
    var otaInProgress by rememberSaveable { mutableStateOf(false) }
    var otaProgress by rememberSaveable { mutableStateOf(0f) }
    var otaMessage by rememberSaveable { mutableStateOf("") }
    var showDebugPopup by rememberSaveable { mutableStateOf(false) }
    var selectedSettingsTab by rememberSaveable { mutableStateOf(SettingsTab.USER) }
    var status by remember { mutableStateOf("Connect the ESP32-S3 USB port, then scan.") }
    var provisionStep by rememberSaveable { mutableStateOf(SettingsProvisionStep.SELECT_BLE) }
    var pendingProvisionNext by rememberSaveable { mutableStateOf(false) }
    var isLoadingDeviceSettings by rememberSaveable { mutableStateOf(false) }
    var showResetDeviceModeDialog by rememberSaveable { mutableStateOf(false) }
    val activity = context as? ComponentActivity
    val currentOnTransportConnectionChange by rememberUpdatedState(onTransportConnectionChange)
    val provisionBleReady = provisionMode && activeConnection == ActiveConnection.BLE && bleReady
    val showProvisionDeviceSettings = provisionMode && provisionStep == SettingsProvisionStep.DEVICE_SETTINGS && provisionBleReady
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

    fun checkForOtaUpdate() {
        if (otaCheckInProgress || otaInProgress) return
        otaCheckInProgress = true
        otaMessage = "Checking for firmware updates..."
        executor.execute {
            runCatching(::fetchOtaFirmwareRelease).onSuccess { release ->
                activity?.runOnUiThread {
                    otaRelease = release
                    otaCheckInProgress = false
                    otaMessage = if (isNewerFirmwareVersion(haLowStatus?.firmwareVersion.orEmpty(), release.version)) {
                        "Update available: ${release.version}"
                    } else {
                        "Your firmware is up to date"
                    }
                }
            }.onFailure { error ->
                activity?.runOnUiThread {
                    otaCheckInProgress = false
                    otaMessage = error.message ?: "Firmware check failed"
                }
            }
        }
    }

    fun installOtaUpdate(release: OtaFirmwareRelease) {
        if (otaInProgress || !bleClient.isOtaReady()) {
            otaMessage = "Reconnect to a device with BLE OTA support"
            return
        }
        otaInProgress = true
        otaProgress = 0f
        otaMessage = "Downloading ${release.version}..."
        executor.execute {
            val result = runCatching {
                val connection = (URL(release.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                }
                try {
                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException("Firmware download failed: HTTP ${connection.responseCode}")
                    }
                    BufferedInputStream(connection.inputStream).use { image ->
                        bleClient.performOta(image, release.size) { sent, total ->
                            activity?.runOnUiThread {
                                otaProgress = sent.toFloat() / total.toFloat()
                                otaMessage = "Installing ${release.version}: ${(otaProgress * 100).toInt()}%"
                            }
                        }.getOrThrow()
                    }
                } finally {
                    connection.disconnect()
                }
            }
            activity?.runOnUiThread {
                otaInProgress = false
                otaMessage = result.fold(
                    onSuccess = { "Firmware uploaded. The device is restarting." },
                    onFailure = { it.message ?: "Firmware update failed" },
                )
            }
        }
    }

    fun reloadAppSettingsFromPreferences() {
        meshCountry = connectionPreferences.getMeshCountry()
        meshId = connectionPreferences.getMeshId()
        passphrase = connectionPreferences.getMeshPassphrase()
        maxHop = connectionPreferences.getMeshMaxHop().toString()
        meshBandwidthMHz = connectionPreferences.getMeshBandwidthMHz()
        meshFrequencyKHz = connectionPreferences.getMeshFrequencyKHz()
        libp2pMeshEnabled = connectionPreferences.getLibp2pMeshEnabled()
        libp2pPublicDht = connectionPreferences.getLibp2pPublicDht()
        libp2pBootstrapPeers = connectionPreferences.getLibp2pBootstrapPeers().joinToString("\n")
        libp2pExtraServerOption = libp2pExtraServerOptionFor(libp2pBootstrapPeers)
        beaconIntervalSeconds = connectionPreferences.getBeaconIntervalSeconds().toString()
        userIdentity = connectionPreferences.getOrCreateUserIdentity()
        userName = userIdentity.name
        userMarker = connectionPreferences.getUserMarker()
        autoReplayReceivedVoice = connectionPreferences.getAutoReplayReceivedVoice()
        autoAnswerVoiceCalls = connectionPreferences.getAutoAnswerVoiceCalls()
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
        deviceType = settings.deviceType
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
        } else if (deviceIdentity == null && settings.deviceType.isDeviceProfile) {
            ensureDeviceIdentity()
        }
        status = "Device settings loaded"
    }

    fun currentDeviceSettings(): DeviceSettings {
        val identity = ensureDeviceIdentity()
        val selectedGeoFence = deviceGeoFences.firstOrNull { DeviceGeoFence.matchesKey(it, selectedDeviceGeoFenceKey) }
        return DeviceSettings(
            deviceType = deviceType,
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
                    onFailure = {
                        isLoadingDeviceSettings = false
                        if (!provisionMode && connection == ActiveConnection.BLE) {
                            DeviceModeState.enabled = false
                        }
                        it.message ?: "Device settings unavailable"
                    },
                )
            }
        }
    }

    fun currentUserModeSettings(): DeviceSettings {
        val identity = connectionPreferences.getOrCreateUserIdentity()
        val shareUserLocation = connectionPreferences.getShareLocation()
        val location = if (shareUserLocation) currentLocationPair() else null
        return DeviceSettings(
            deviceType = EdgeZDeviceType.USER,
            meshId = connectionPreferences.getMeshId(),
            passphrase = connectionPreferences.getMeshPassphrase(),
            shareLocation = shareUserLocation,
            userName = identity.name,
            marker = connectionPreferences.getUserMarker(),
            beaconIntervalSeconds = connectionPreferences.getBeaconIntervalSeconds(),
            userIdHigh = identity.userIdHigh,
            userIdLow = identity.userIdLow,
            userPublicKey = identity.publicKey,
            userPrivateKey = identity.privateKey,
            latitude = location?.first,
            longitude = location?.second,
            maxHop = connectionPreferences.getMeshMaxHop(),
        )
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

    fun connectSavedBle() {
        if (activeConnection == ActiveConnection.BLE) {
            disconnectActiveTransport()
            return
        }
        if (selectedBleAddress.isBlank()) {
            status = "Select a BLE device first"
            showBlePicker = true
            return
        }
        if (!bleClient.hasPermissions()) {
            requestBlePermissions()
            return
        }
        status = "Scanning for ${selectedBleLabel.ifBlank { selectedBleAddress }}..."
        bleCandidates = emptyList()
        val didStartConnect = java.util.concurrent.atomic.AtomicBoolean(false)
        bleClient.startScan { candidate ->
            activity?.runOnUiThread {
                if (bleCandidates.none { it.device.address == candidate.device.address }) {
                    bleCandidates = (bleCandidates + candidate).sortedBy { it.label }
                }
                if (candidate.device.address == selectedBleAddress && didStartConnect.compareAndSet(false, true)) {
                    selectedBle = candidate
                    bleClient.stopScan()
                    val result = bleClient.connect(candidate)
                    result.fold(
                        onSuccess = {
                            status = it
                            connectionPreferences.setLastSuccessfulConnection(ActiveConnection.BLE)
                            onTransportConnectionChange(ActiveConnection.USB, false)
                        },
                        onFailure = {
                            status = it.message ?: "BLE connect failed"
                        },
                    )
                }
            }
        }.onFailure {
            status = it.message ?: "BLE scan failed"
        }
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
        provisionStep = SettingsProvisionStep.SELECT_BLE
        onProvisionComplete()
    }

    fun goBackSettingsProvisionStep() {
        if (provisionStep == SettingsProvisionStep.DEVICE_SETTINGS) {
            provisionStep = SettingsProvisionStep.SELECT_BLE
            status = "Select an EdgeZ BLE device"
        } else {
            cancelProvision()
        }
    }

    fun goNextSettingsProvisionStep() {
        if (provisionStep == SettingsProvisionStep.SELECT_BLE) {
            if (!provisionBleReady) {
                if (connectSelectedBleForProvision()) {
                    pendingProvisionNext = true
                }
                return
            }
            pendingProvisionNext = false
            provisionStep = SettingsProvisionStep.DEVICE_SETTINGS
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
        if (libp2pExtraServerOption == Libp2pExtraServerOption.CUSTOM && libp2pBootstrapPeers.trim().isBlank()) {
            status = "Custom bootstrap server cannot be empty"
            return
        }
        connectionPreferences.setMeshCredentials(
            country,
            id,
            password,
            hopLimit,
            beaconInterval,
            meshBandwidthMHz,
            meshFrequencyKHz,
        )
        maxHop = connectionPreferences.getMeshMaxHop().toString()
        beaconIntervalSeconds = connectionPreferences.getBeaconIntervalSeconds().toString()
        connectionPreferences.setUserName(userName)
        connectionPreferences.setUserMarker(userMarker)
        connectionPreferences.setShareLocation(shareLocation)
        connectionPreferences.setLibp2pMeshEnabled(libp2pMeshEnabled)
        connectionPreferences.setLibp2pPublicDht(libp2pPublicDht)
        connectionPreferences.setLibp2pBootstrapPeers(libp2pBootstrapPeers)
        userIdentity = connectionPreferences.getOrCreateUserIdentity()
        status = "Settings saved"
        onLibp2pMeshSettingsSaved()
    }

    DisposableEffect(Unit) {
        fun handleSettingsFrame(frame: ByteArray) {
            val deviceSettings = decodeHaLowSyncFrame(frame, connectionPreferences.getMeshPassphrase())?.deviceSettings
                ?: return
            activity?.runOnUiThread {
                // Periodic beacon-profile DEVICE_SETTINGS_SET messages also
                // receive REPORT responses. Only an explicit settings GET may
                // refresh this editable form, otherwise a delayed periodic
                // report can overwrite an in-progress name/marker/GPS edit.
                if (!isLoadingDeviceSettings) return@runOnUiThread
                isLoadingDeviceSettings = false
                if (!provisionMode && bleReady) {
                    DeviceModeState.enabled = deviceSettings.deviceType.isDeviceProfile
                    if (deviceSettings.deviceType.isDeviceProfile) {
                        showResetDeviceModeDialog = true
                        status = "This device is provisioned in device mode"
                    } else {
                        status = "Device is already in user mode"
                    }
                    return@runOnUiThread
                }
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
                    if (!provisionMode) {
                        DeviceModeState.enabled = true
                    }
                    currentOnTransportConnectionChange(ActiveConnection.BLE, true)
                    if (provisionMode && provisionStep == SettingsProvisionStep.SELECT_BLE) {
                        if (pendingProvisionNext) {
                            pendingProvisionNext = false
                            provisionStep = SettingsProvisionStep.DEVICE_SETTINGS
                            requestDeviceSettings(ActiveConnection.BLE)
                        } else {
                            status = "BLE ready; tap Next"
                        }
                    } else if (!provisionMode) {
                        requestDeviceSettings(ActiveConnection.BLE)
                    }
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
            // OTA runs on this executor. Do not interrupt it when the user
            // switches away from Settings; the BLE transfer must finish.
        }
    }

    LaunchedEffect(activeConnection, bleReady, provisionMode) {
        if (provisionMode && !provisionBleReady && provisionStep == SettingsProvisionStep.DEVICE_SETTINGS) {
            provisionStep = SettingsProvisionStep.SELECT_BLE
        }
    }

    LaunchedEffect(provisionMode) {
        if (provisionMode && activeConnection != ActiveConnection.BLE && !bleReady) {
            status = "Scanning for EdgeZ BLE devices..."
            startBleScanForProvision()
        }
    }

    if (showBlePicker) {
        BleSelectionScreen(
            candidates = bleCandidates,
            selectedAddress = selectedBleAddress,
            status = status,
            onBack = {
                bleClient.stopScan()
                showBlePicker = false
            },
            onSelect = { candidate ->
                selectedBle = candidate
                selectedBleAddress = candidate.device.address
                selectedBleLabel = candidate.label
                connectionPreferences.setSelectedBleDevice(candidate.device.address, candidate.label)
                bleClient.stopScan()
                status = "Selected ${candidate.label}"
                showBlePicker = false
            },
        )
        LaunchedEffect(Unit) {
            if (!bleClient.hasPermissions()) {
                requestBlePermissions()
                return@LaunchedEffect
            }
            bleCandidates = emptyList()
            status = "Scanning for EdgeZ BLE devices..."
            bleClient.startScan { candidate ->
                activity?.runOnUiThread {
                    if (bleCandidates.none { it.device.address == candidate.device.address }) {
                        bleCandidates = (bleCandidates + candidate).sortedBy { it.label }
                    }
                }
            }.onFailure {
                status = it.message ?: "BLE scan failed"
            }
        }
        DisposableEffect(Unit) {
            onDispose { bleClient.stopScan() }
        }
        return
    }

    if (showResetDeviceModeDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Device mode detected") },
            text = {
                Text("This EdgeZ is provisioned as a device and is advertising its device profile. Reset it to user mode and use this phone user name, marker, and shared GPS instead?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDeviceModeDialog = false
                        sendDeviceSettingsToDevice(
                            settings = currentUserModeSettings(),
                            connection = ActiveConnection.BLE,
                            label = "User mode settings",
                            onSuccessAction = {
                                DeviceModeState.enabled = false
                                status = "Device reset to user mode"
                            },
                        )
                    },
                ) {
                    Text("Reset to user mode")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showResetDeviceModeDialog = false
                        DeviceModeState.enabled = true
                        status = "Keeping device mode"
                    },
                ) {
                    Text("Keep device mode")
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (provisionMode) {
                TopAppBar(
                    title = { Text("Provision device") },
                    navigationIcon = {
                        TextButton(onClick = { goBackSettingsProvisionStep() }) {
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
                        enabled = if (provisionStep == SettingsProvisionStep.SELECT_BLE) selectedBle != null else showDeviceSettingsOnly,
                        onClick = {
                            if (provisionStep == SettingsProvisionStep.SELECT_BLE) {
                                goNextSettingsProvisionStep()
                            } else {
                                saveMeshPreferences()
                            }
                        },
                    ) {
                        Text(if (provisionStep == SettingsProvisionStep.SELECT_BLE) "Next" else "Save")
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
                        if (provisionStep == SettingsProvisionStep.SELECT_BLE) {
                            "Step 1 of 2: Select BLE device"
                        } else {
                            "Step 2 of 2: Configure device"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                } else {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                }
                if (provisionMode || showDeviceSettingsOnly) {
                    Spacer(Modifier.height(6.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium)
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

            if (!showDeviceSettingsOnly) item {
                SettingsCard(
                    title = "BLE connection",
                    action = {
                        OutlinedButton(onClick = { showBlePicker = true }) {
                            Text("Select")
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Selected device", style = MaterialTheme.typography.titleSmall)
                            Text(
                                selectedBleLabel.ifBlank { selectedBleAddress.ifBlank { "No BLE device selected" } },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (selectedBleAddress.isNotBlank()) {
                                Text(selectedBleAddress, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                if (activeConnection == ActiveConnection.BLE) "BLE connected" else "BLE disconnected",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (activeConnection == ActiveConnection.BLE && haLowStatus?.firmwareVersion?.isNotBlank() == true) {
                                Text(
                                    "Firmware: ${haLowStatus.firmwareVersion}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (activeConnection == ActiveConnection.BLE && haLowStatus?.loadedLicense != null) {
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val licensed = haLowStatus.loadedLicense == true
                                    Icon(
                                        painter = painterResource(
                                            if (licensed) R.drawable.ic_license_valid else R.drawable.ic_license_error,
                                        ),
                                        contentDescription = if (licensed) "Device licensed" else "Device unlicensed",
                                        tint = if (licensed) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                    Text(
                                        if (licensed) "Licensed" else "Unlicensed",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (licensed) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = activeConnection == ActiveConnection.BLE || selectedBleAddress.isNotBlank(),
                                onClick = { connectSavedBle() },
                            ) {
                                Text(if (activeConnection == ActiveConnection.BLE) "Disconnect" else "Connect")
                            }
                        }
                    }
                    if (activeConnection == ActiveConnection.BLE) {
                        Spacer(Modifier.height(10.dp))
                        val currentFirmware = haLowStatus?.firmwareVersion.orEmpty()
                        val updateAvailable = otaRelease?.let { isNewerFirmwareVersion(currentFirmware, it.version) } == true
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = currentFirmware.isNotBlank() && !otaCheckInProgress && !otaInProgress,
                                onClick = ::checkForOtaUpdate,
                            ) {
                                Text(if (otaCheckInProgress) "Checking..." else "Check for update")
                            }
                            if (updateAvailable) {
                                Button(
                                    enabled = !otaInProgress && bleClient.isOtaReady(),
                                    onClick = { otaRelease?.let(::installOtaUpdate) },
                                ) {
                                    Text(if (otaInProgress) "Updating ${(otaProgress * 100).toInt()}%" else "Update")
                                }
                            }
                        }
                        if (otaMessage.isNotBlank()) {
                            Text(otaMessage, style = MaterialTheme.typography.bodySmall)
                        }
                        if (updateAvailable && !bleClient.isOtaReady()) {
                            Text(
                                "This connected firmware does not expose BLE OTA yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto connect", style = MaterialTheme.typography.titleSmall)
                            Text("Connect the selected BLE device on app start and reconnect if it drops", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = bleAutoConnect,
                            onCheckedChange = { enabled ->
                                bleAutoConnect = enabled
                                connectionPreferences.setBleAutoConnect(enabled)
                                status = if (enabled) "BLE auto connect enabled" else "BLE auto connect disabled"
                            },
                        )
                    }
                }
            }

            if (!provisionMode && !showDeviceSettingsOnly) item {
                TabRow(selectedTabIndex = selectedSettingsTab.ordinal) {
                    SettingsTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedSettingsTab == tab,
                            onClick = { selectedSettingsTab = tab },
                            text = { Text(tab.label) },
                        )
                    }
                }
            }

            if (showDeviceSettingsOnly || (!provisionMode && selectedSettingsTab == SettingsTab.USER)) item {
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
                    } else {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                connectionPreferences.setUserName(userName)
                                connectionPreferences.setUserMarker(userMarker)
                                connectionPreferences.setShareLocation(shareLocation)
                                userIdentity = connectionPreferences.getOrCreateUserIdentity()
                                userName = userIdentity.name
                                status = "User settings saved"
                            },
                        ) {
                            Text("Save user settings")
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

            if (showDeviceSettingsOnly || (!provisionMode && selectedSettingsTab == SettingsTab.MESH_NETWORK)) item {
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
                                            val bandwidths = haLowBandwidthOptions(country)
                                            meshBandwidthMHz = meshBandwidthMHz.takeIf { it in bandwidths }
                                                ?: bandwidths.first()
                                            val frequencies = haLowFrequenciesKHz(country, meshBandwidthMHz)
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
                                haLowBandwidthOptions(meshCountry).forEach { bandwidth ->
                                    DropdownMenuItem(
                                        text = { Text("$bandwidth MHz") },
                                        onClick = {
                                            meshBandwidthMHz = bandwidth
                                            val frequencies = haLowFrequenciesKHz(meshCountry, bandwidth)
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
                                Text("Frequency: ${haLowFrequencyLabel(meshCountry, meshFrequencyKHz)}")
                            }
                            DropdownMenu(
                                expanded = frequencyDropdownExpanded,
                                onDismissRequest = { frequencyDropdownExpanded = false },
                            ) {
                                haLowFrequenciesKHz(meshCountry, meshBandwidthMHz).forEach { frequency ->
                                    DropdownMenuItem(
                                        text = { Text(haLowFrequencyLabel(meshCountry, frequency)) },
                                        onClick = {
                                            meshFrequencyKHz = frequency
                                            frequencyDropdownExpanded = false
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
                        visualTransformation = if (passphraseVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation(mask = '*')
                        },
                        trailingIcon = {
                            IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                                Icon(
                                    painter = painterResource(
                                        if (passphraseVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                                    ),
                                    contentDescription = if (passphraseVisible) {
                                        "Hide passphrase"
                                    } else {
                                        "Show passphrase"
                                    },
                                )
                            }
                        },
                    )
                    if (SHOW_LIBP2P_SETTINGS && !showDeviceSettingsOnly) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Libp2p mesh", style = MaterialTheme.typography.titleSmall)
                                Text("Join the DHT gossip topic for this mesh", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = libp2pMeshEnabled,
                                onCheckedChange = { enabled ->
                                    libp2pMeshEnabled = enabled
                                    connectionPreferences.setLibp2pMeshEnabled(enabled)
                                    status = if (enabled) "Libp2p mesh enabled" else "Libp2p mesh disabled"
                                },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Public DHT", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Use public bootstrap peers in addition to custom bootstrap",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = libp2pPublicDht,
                                onCheckedChange = { enabled ->
                                    libp2pPublicDht = enabled
                                    connectionPreferences.setLibp2pPublicDht(enabled)
                                    status = if (enabled) "Libp2p public DHT enabled" else "Libp2p public DHT disabled"
                                },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { libp2pExtraServerDropdownExpanded = true },
                            ) {
                                Text("Bootstrap Server: ${libp2pExtraServerOption.label}")
                            }
                            DropdownMenu(
                                expanded = libp2pExtraServerDropdownExpanded,
                                onDismissRequest = { libp2pExtraServerDropdownExpanded = false },
                            ) {
                                Libp2pExtraServerOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            libp2pExtraServerOption = option
                                            libp2pBootstrapPeers = when (option) {
                                                Libp2pExtraServerOption.NONE -> DEFAULT_LIBP2P_BOOTSTRAP_PEERS
                                                Libp2pExtraServerOption.EDGEZ -> EDGEZ_LIBP2P_BOOTSTRAP_PEERS
                                                Libp2pExtraServerOption.CUSTOM -> libp2pBootstrapPeers
                                            }
                                            libp2pExtraServerDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = libp2pBootstrapPeers,
                            onValueChange = { value ->
                                libp2pBootstrapPeers = value
                                libp2pExtraServerOption = libp2pExtraServerOptionFor(value)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Bootstrap server list") },
                            singleLine = false,
                            maxLines = 4,
                            supportingText = {
                                if (isCustomBootstrapServerMissing) {
                                    Text("Custom bootstrap server cannot be empty")
                                } else {
                                    Text("Enter relay/DHT server multiaddrs, one per line or separated by commas")
                                }
                            },
                        )
                    }
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
                            visualTransformation = if (deviceUpstreamWifiPassphraseVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation(mask = '*')
                            },
                            trailingIcon = {
                                IconButton(onClick = { deviceUpstreamWifiPassphraseVisible = !deviceUpstreamWifiPassphraseVisible }) {
                                    Icon(
                                        painter = painterResource(
                                            if (deviceUpstreamWifiPassphraseVisible) {
                                                R.drawable.ic_visibility_off
                                            } else {
                                                R.drawable.ic_visibility
                                            },
                                        ),
                                        contentDescription = if (deviceUpstreamWifiPassphraseVisible) {
                                            "Hide upstream Wi-Fi passphrase"
                                        } else {
                                            "Show upstream Wi-Fi passphrase"
                                        },
                                    )
                                }
                            },
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
                        Button(
                            onClick = { saveMeshPreferences() },
                            enabled = !isCustomBootstrapServerMissing,
                        ) {
                            Text(if (deviceMode) "Save to device" else "Save settings")
                        }
                    }
                }
            }

            if (!provisionMode && !showDeviceSettingsOnly && selectedSettingsTab == SettingsTab.OTHERS) item {
                SettingsCard(title = "Background connection") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Allow background connection", style = MaterialTheme.typography.titleSmall)
                            Text("Request permission to keep BLE connected in the background", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { requestIgnoreBatteryOptimizations() }) {
                            Text("Allow")
                        }
                    }
                }
            }

            if (!provisionMode && !showDeviceSettingsOnly && selectedSettingsTab == SettingsTab.OTHERS) item {
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
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-answer voice calls", style = MaterialTheme.typography.titleSmall)
                            Text("Answer incoming calls automatically", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = autoAnswerVoiceCalls,
                            onCheckedChange = { enabled ->
                                autoAnswerVoiceCalls = enabled
                                connectionPreferences.setAutoAnswerVoiceCalls(enabled)
                                if (enabled && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    activity?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2004)
                                    status = "Allow microphone access for auto-answer"
                                } else {
                                    status = if (enabled) "Auto-answer enabled" else "Auto-answer disabled"
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BleSelectionScreen(
    candidates: List<BleCandidate>,
    selectedAddress: String,
    status: String,
    onBack: () -> Unit,
    onSelect: (BleCandidate) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Select BLE device") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
            )
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
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
            if (candidates.isEmpty()) {
                item {
                    Text("Scanning for EdgeZ BLE devices...", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                items(candidates, key = { it.device.address }) { candidate ->
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onSelect(candidate) },
                    ) {
                        Text(if (candidate.device.address == selectedAddress) "Selected: ${candidate.label}" else candidate.label)
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
private fun SettingsPreview() {
    EdgeZTheme {
        val context = LocalContext.current.applicationContext
        SettingsScreen(
            client = EdgezUsbClient(context),
            bleClient = EdgezBleClient(context),
            activeConnection = ActiveConnection.NONE,
            haLowStatus = null,
            edgeZDatabase = EdgeZDatabase(context),
            shareLocation = false,
            onShareLocationChange = {},
            onTransportConnectionChange = { _, _ -> },
            onTransportDisconnect = {},
        )
    }
}
