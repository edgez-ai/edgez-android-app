package ai.edgez.edgez

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.edgez.edgez.ble.EdgezBleClient
import ai.edgez.edgez.usb.DeviceSettings
import ai.edgez.edgez.usb.EdgezUsbClient
import ai.edgez.edgez.usb.HaLowInterfaceStatus
import ai.edgez.edgez.usb.EdgezUsbControlProto
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val BEACON_LOCATION_FRESH_MS = 10 * 60 * 1000L
private const val TAG_BEACON = "EdgeZUsers"

object EdgeZBeaconRunner {
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var usbClient: EdgezUsbClient? = null
    @Volatile private var bleClient: EdgezBleClient? = null
    @Volatile private var activeConnection: ActiveConnection = ActiveConnection.NONE
    @Volatile private var haLowStatus: HaLowInterfaceStatus? = null
    @Volatile private var onFramePublished: ((ByteArray) -> Unit)? = null
    @Volatile private var running = false
    private var executor: ExecutorService? = null

    private val beaconRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val context = appContext
            val intervalMs = (context?.let { LastConnectionPreferences(it).getBeaconIntervalSeconds() }
                ?: DEFAULT_BEACON_INTERVAL_SECONDS) * 1_000L
            if (context != null) {
                sendBeaconIfReady(context)
            }
            handler.postDelayed(this, intervalMs)
        }
    }

    fun attach(
        context: Context,
        usbClient: EdgezUsbClient,
        bleClient: EdgezBleClient,
        onFramePublished: ((ByteArray) -> Unit)? = null,
    ) {
        appContext = context.applicationContext
        this.usbClient = usbClient
        this.bleClient = bleClient
        this.onFramePublished = onFramePublished
    }

    fun setActiveConnection(connection: ActiveConnection) {
        activeConnection = connection
        if (connection == ActiveConnection.NONE) {
            haLowStatus = null
        }
    }

    fun setHaLowStatus(status: HaLowInterfaceStatus?) {
        haLowStatus = status
    }

    fun setFramePublishedListener(listener: ((ByteArray) -> Unit)?) {
        onFramePublished = listener
    }

    fun publishSelfBeaconToLibp2p(context: Context) {
        val status = haLowStatus
        if (DeviceModeState.enabled) return
        val source = activeConnection
        val publishToLibp2p = status == null || (status.supported && status.stackInitialized && status.meshMode)

        val contextRef = context.applicationContext
        val preferences = LastConnectionPreferences(contextRef)
        val userIdentity = preferences.getOrCreateUserIdentity()
        val meshPassphrase = preferences.getMeshPassphrase()
        val marker = preferences.getUserMarker()
        val location = if (preferences.getShareLocation()) {
            contextRef.getBestKnownLocation()
        } else {
            null
        }
        val frame = runCatching {
            EdgezUsbControlProto.encodeHaLowBeacon(
                userIdentity.userIdHigh,
                userIdentity.userIdLow,
                userIdentity.name,
                userIdentity.publicKey,
                meshPassphrase,
                location?.latitude,
                location?.longitude,
                location?.time ?: 0L,
                marker,
            )
        }.getOrNull() ?: return

        val localExecutor = synchronized(lock) {
            executor ?: Executors.newSingleThreadExecutor().also { executor = it }
        }
        localExecutor.execute {
            when (source) {
                ActiveConnection.USB -> usbClient?.sendHaLowBeacon(
                    userIdentity.userIdHigh,
                    userIdentity.userIdLow,
                    userIdentity.name,
                    userIdentity.publicKey,
                    meshPassphrase,
                    location?.latitude,
                    location?.longitude,
                    location?.time ?: 0L,
                    marker,
                )
                ActiveConnection.BLE -> bleClient?.sendHaLowBeacon(
                    userIdentity.userIdHigh,
                    userIdentity.userIdLow,
                    userIdentity.name,
                    userIdentity.publicKey,
                    meshPassphrase,
                    location?.latitude,
                    location?.longitude,
                    location?.time ?: 0L,
                    marker,
                )
                ActiveConnection.NONE -> null
            }
            if (publishToLibp2p) {
                onFramePublished?.invoke(frame)
            }
        }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        synchronized(lock) {
            if (running) return
            running = true
            handler.removeCallbacks(beaconRunnable)
            if (executor == null || executor?.isShutdown == true) {
                executor = Executors.newSingleThreadExecutor()
            }
            handler.post(beaconRunnable)
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            handler.removeCallbacks(beaconRunnable)
            executor?.shutdownNow()
            executor = null
        }
    }

    private fun sendBeaconIfReady(context: Context) {
        val source = activeConnection
        val status = haLowStatus
        val canSendTransportBeacon = source != ActiveConnection.NONE &&
            status != null &&
            status.supported &&
            status.stackInitialized &&
            status.meshMode
        val canPublishLibp2pBeacon = LastConnectionPreferences(context).getLibp2pMeshEnabled() &&
            (status == null || (status.supported && status.stackInitialized && status.meshMode))
        if (!canSendTransportBeacon && !canPublishLibp2pBeacon) {
            return
        }

        val preferences = LastConnectionPreferences(context)
        if (DeviceModeState.enabled) return
        val userIdentity = preferences.getOrCreateUserIdentity()
        val meshPassphrase = preferences.getMeshPassphrase()
        val marker = preferences.getUserMarker()
        val shareLocationEnabled = preferences.getShareLocation()
        val location = if (shareLocationEnabled) context.getBestKnownLocation() else null
        val deviceSettings = DeviceSettings(
            deviceType = EdgeZDeviceType.USER,
            meshId = preferences.getMeshId(),
            passphrase = meshPassphrase,
            shareLocation = shareLocationEnabled,
            userName = userIdentity.name,
            marker = marker,
            beaconIntervalSeconds = preferences.getBeaconIntervalSeconds(),
            userIdHigh = userIdentity.userIdHigh,
            userIdLow = userIdentity.userIdLow,
            userPublicKey = userIdentity.publicKey,
            userPrivateKey = userIdentity.privateKey,
            latitude = location?.latitude,
            longitude = location?.longitude,
            maxHop = preferences.getMeshMaxHop(),
        )
        Log.d(
            TAG_BEACON,
            "periodic device_settings share=$shareLocationEnabled marker=$marker hasLocation=${location != null} lat=${location?.latitude} lon=${location?.longitude}",
        )

        val executor = executor ?: return
        executor.execute {
            val beaconFrame = runCatching {
                EdgezUsbControlProto.encodeHaLowBeacon(
                    userIdentity.userIdHigh,
                    userIdentity.userIdLow,
                    userIdentity.name,
                    userIdentity.publicKey,
                    meshPassphrase,
                    location?.latitude,
                    location?.longitude,
                    location?.time ?: 0L,
                    marker,
                )
            }.getOrNull() ?: return@execute
            val transportResult = when (source) {
                ActiveConnection.USB -> usbClient?.sendDeviceSettings(deviceSettings)
                ActiveConnection.BLE -> bleClient?.sendDeviceSettings(deviceSettings)
                ActiveConnection.NONE -> null
            }
            if (canPublishLibp2pBeacon || transportResult?.isSuccess == true) {
                Log.d(TAG_BEACON, "periodic device_settings sent source=$source; queue beacon for libp2p direct=$canPublishLibp2pBeacon")
                onFramePublished?.invoke(beaconFrame)
            }
        }
    }
}

internal fun Context.getBestKnownLocation(): Location? {
    val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) return null
    val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = if (hasFine) {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    } else {
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    }
    return providers.mapNotNull { provider ->
        runCatching {
            if (locationManager.isProviderEnabled(provider)) {
                locationManager.getLastKnownLocation(provider)
            } else {
                null
            }
        }.getOrNull()
    }.maxByOrNull { location ->
        locationQualityScore(location, System.currentTimeMillis())
    }
}

private fun locationQualityScore(location: Location, nowMs: Long): Double {
    val ageMs = (nowMs - location.time).coerceAtLeast(0L)
    val ageMinutes = ageMs / 60_000.0
    val accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else 1_000.0
    val providerBonus = when (location.provider) {
        LocationManager.GPS_PROVIDER -> 50.0
        LocationManager.NETWORK_PROVIDER -> 10.0
        else -> 0.0
    }
    val stalePenalty = if (ageMs > BEACON_LOCATION_FRESH_MS) 200.0 else 0.0
    return providerBonus - accuracyMeters - (ageMinutes * 3.0) - stalePenalty
}
