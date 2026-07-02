package ai.edgez.edgez

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.organicmaps.sdk.Framework
import app.organicmaps.sdk.MapController
import app.organicmaps.sdk.MapRenderingListener
import app.organicmaps.sdk.MapView
import app.organicmaps.sdk.downloader.CountryItem
import app.organicmaps.sdk.downloader.MapManager
import app.organicmaps.sdk.util.ConnectionState
import kotlinx.coroutines.delay

private const val TAG_MAP = "EdgeZMap"
private const val DEFAULT_MAP_ZOOM = 9
private const val MIN_DOWNLOAD_PROMPT_ZOOM = 9
private const val REGION_AUTOCACHE_INTERVAL_MS = 3_500L
private const val REGION_AUTOCACHE_INITIAL_DELAY_MS = 5_000L
private const val MAP_REFRESH_DELAY_MS = 250L
private const val MAP_INITIAL_RENDER_DELAY_MS = 750L
private const val DOWNLOAD_PROMPT_GESTURE_DELAY_MS = 500L

private data class MapTarget(
    val latitude: Double,
    val longitude: Double,
    val label: String,
)

private data class MapDownloadProgress(
    val countryId: String,
    val progress: Float?,
)

@Composable
fun MapScreen(users: List<HaLowUser>) {
    val context = LocalContext.current
    val application = context.applicationContext as EdgeZApplication
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var locationPermissionGranted by remember { mutableStateOf(context.hasMapLocationPermission()) }
    var phoneLocation by remember { mutableStateOf<Location?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        locationPermissionGranted = grants.values.any { it } || context.hasMapLocationPermission()
    }
    val targetUser = users.firstOrNull { it.hasLocation() }
    val target = targetUser?.let { user ->
        val latitude = user.latitude
        val longitude = user.longitude
        if (latitude != null && longitude != null) {
            MapTarget(latitude, longitude, user.displayName)
        } else {
            null
        }
    } ?: phoneLocation?.let { location ->
        MapTarget(location.latitude, location.longitude, "phone location")
    }
    val targetState by rememberUpdatedState(target)

    var initialized by remember { mutableStateOf(application.organicMaps.arePlatformAndCoreInitialized()) }
    var status by remember { mutableStateOf("Starting offline map") }
    var controller by remember { mutableStateOf<MapController?>(null) }
    var pendingRegionId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<MapDownloadProgress?>(null) }
    val requestedRegions = remember { mutableSetOf<String>() }

    fun refreshDownloadPrompt() {
        if (isDownloadPromptZoomAllowed()) {
            findDownloadableRegion(requestedRegions)?.let { regionId ->
                pendingRegionId = regionId
                status = "Map region available to download"
            }
        } else {
            pendingRegionId = null
        }
    }

    LaunchedEffect(Unit) {
        application.initializeOrganicMaps {
            initialized = true
            status = "Offline map ready"
        }.onSuccess {
            if (application.organicMaps.arePlatformAndCoreInitialized()) {
                initialized = true
                status = "Offline map ready"
                runCatching { Framework.nativeRestoreDownloadQueue() }
            }
        }.onFailure { error ->
            status = "Map unavailable: ${error.message ?: error::class.java.simpleName}"
            Log.e(TAG_MAP, "Organic Maps initialization failed", error)
        }
    }

    LaunchedEffect(initialized, locationPermissionGranted) {
        if (initialized && !locationPermissionGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    @SuppressLint("MissingPermission")
    DisposableEffect(initialized, locationPermissionGranted, controller) {
        val locationHelper = application.organicMaps.locationHelper
        if (initialized && locationPermissionGranted && controller != null) {
            runCatching {
                locationHelper.start()
            }.onFailure { error ->
                Log.w(TAG_MAP, "Unable to start map location updates", error)
            }
        }
        onDispose {
            if (initialized && locationPermissionGranted && controller != null) {
                locationHelper.stop()
            }
        }
    }

    LaunchedEffect(initialized, locationPermissionGranted, controller) {
        if (!initialized || !locationPermissionGranted || controller == null) {
            phoneLocation = null
            return@LaunchedEffect
        }

        while (true) {
            phoneLocation = application.organicMaps.locationHelper.savedLocation
                ?: context.getBestKnownMapLocation()
            delay(PHONE_LOCATION_REFRESH_MS)
        }
    }

    LaunchedEffect(initialized, controller) {
        if (!initialized || controller == null) {
            return@LaunchedEffect
        }

        runCatching { Framework.nativeRestoreDownloadQueue() }
        delay(REGION_AUTOCACHE_INITIAL_DELAY_MS)
        while (true) {
            refreshDownloadPrompt()
            delay(REGION_AUTOCACHE_INTERVAL_MS)
        }
    }

    LaunchedEffect(initialized, controller, target?.latitude, target?.longitude) {
        if (initialized && controller != null) {
            centerOnTarget(target, controller)
            delay(MAP_REFRESH_DELAY_MS)
            centerOnTarget(target, controller)
        }
    }

    DisposableEffect(initialized) {
        val slot = if (initialized) {
            MapManager.nativeSubscribe(object : MapManager.StorageCallback {
                override fun onStatusChanged(data: List<MapManager.StorageCallbackData>) {
                    val event = data.lastOrNull() ?: return
                    val name = event.countryId
                    status = when (event.newStatus) {
                        CountryItem.STATUS_DONE -> {
                            downloadProgress = null
                            "Offline map cached: $name"
                        }
                        CountryItem.STATUS_PROGRESS -> {
                            if (downloadProgress?.countryId != name) {
                                downloadProgress = MapDownloadProgress(name, null)
                            }
                            "Downloading map: $name"
                        }
                        CountryItem.STATUS_ENQUEUED -> {
                            downloadProgress = MapDownloadProgress(name, null)
                            "Queued map: $name"
                        }
                        CountryItem.STATUS_FAILED -> {
                            requestedRegions.remove(event.countryId)
                            downloadProgress = null
                            "Map download failed: $name"
                        }
                        else -> status
                    }
                }

                override fun onProgress(countryId: String, localSize: Long, remoteSize: Long) {
                    val progress = if (remoteSize > 0L) {
                        ((localSize * 100L) / remoteSize).coerceIn(0L, 100L)
                    } else {
                        0L
                    }
                    downloadProgress = MapDownloadProgress(
                        countryId = countryId,
                        progress = if (remoteSize > 0L) {
                            (localSize.toFloat() / remoteSize.toFloat()).coerceIn(0f, 1f)
                        } else {
                            null
                        },
                    )
                    status = "Downloading map: $countryId $progress%"
                }
            })
        } else {
            null
        }

        onDispose {
            if (slot != null) {
                MapManager.nativeUnsubscribe(slot)
            }
        }
    }

    DisposableEffect(controller, lifecycle) {
        val activeController = controller
        if (activeController != null) {
            lifecycle.addObserver(activeController)
        }
        onDispose {
            if (activeController != null) {
                lifecycle.removeObserver(activeController)
            }
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (initialized) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        MapView(viewContext).also { mapView ->
                            mapView.setOnTouchListener { _, event ->
                                if (event.actionMasked == MotionEvent.ACTION_UP) {
                                    refreshDownloadPrompt()
                                    mapView.postDelayed(
                                        { refreshDownloadPrompt() },
                                        DOWNLOAD_PROMPT_GESTURE_DELAY_MS,
                                    )
                                }
                                false
                            }
                            lateinit var mapController: MapController
                            mapController = MapController(
                                mapView,
                                application.organicMaps.locationHelper,
                                object : MapRenderingListener {
                                    override fun onRenderingCreated() {
                                        status = "Offline map ready"
                                        centerOnTarget(targetState, mapController)
                                    }

                                    override fun onRenderingRestored() {
                                        centerOnTarget(targetState, mapController)
                                    }

                                    override fun onRenderingInitializationFinished() {
                                        centerOnTarget(targetState, mapController)
                                    }
                                },
                                {
                                    status = "Map rendering is not supported on this device"
                                },
                                false,
                            )
                            controller = mapController
                            mapView.post { centerOnTarget(targetState, mapController) }
                            mapView.postDelayed(
                                { centerOnTarget(targetState, mapController) },
                                MAP_INITIAL_RENDER_DELAY_MS,
                            )
                        }
                    },
                )
            } else {
                Text(
                    text = status,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (pendingRegionId != null) {
                MapDownloadPrompt(
                    regionId = pendingRegionId.orEmpty(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    onDismiss = {
                        pendingRegionId = null
                        status = "Offline map ready"
                    },
                    onConfirm = {
                        val regionId = pendingRegionId ?: return@MapDownloadPrompt
                        startRegionDownload(regionId, requestedRegions)?.let { status = it }
                        pendingRegionId = null
                    },
                )
            }

            downloadProgress?.let { progress ->
                MapDownloadProgressPanel(
                    progress = progress,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun MapDownloadProgressPanel(
    progress: MapDownloadProgress,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val progressFraction = progress.progress
            val percent = progressFraction?.let { (it * 100).toInt().coerceIn(0, 100) }
            Text(
                text = if (percent != null) {
                    "Downloading map: ${progress.countryId} $percent%"
                } else {
                    "Preparing map download: ${progress.countryId}"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (progressFraction != null) {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MapDownloadPrompt(
    regionId: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Download map: $regionId?",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "It will be cached for offline use.",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(modifier = Modifier.padding(top = 12.dp)) {
                Button(onClick = onConfirm) {
                    Text("Download")
                }
                Button(
                    modifier = Modifier.padding(start = 8.dp),
                    onClick = onDismiss,
                ) {
                    Text("Not now")
                }
            }
        }
    }
}

private const val PHONE_LOCATION_REFRESH_MS = 1_000L

private fun centerOnTarget(target: MapTarget?, controller: MapController?) {
    val latitude = target?.latitude
    val longitude = target?.longitude
    runCatching {
        if (latitude != null && longitude != null) {
            Framework.nativeStopLocationFollow()
            Framework.nativeZoomToPoint(latitude, longitude, DEFAULT_MAP_ZOOM, false)
        } else {
            refreshCurrentViewport()
        }
        forceMapRefresh(controller)
    }.onFailure { error ->
        Log.w(TAG_MAP, "Unable to refresh map for ${target?.label ?: "current viewport"}", error)
    }
}

private fun refreshCurrentViewport() {
    val center = Framework.nativeGetScreenRectCenter()
    val latitude = center.getOrNull(0) ?: return
    val longitude = center.getOrNull(1) ?: return
    val zoom = Framework.nativeGetDrawScale().takeIf { it > 0 } ?: DEFAULT_MAP_ZOOM
    Framework.nativeZoomToPoint(latitude, longitude, zoom, false)
}

private fun forceMapRefresh(controller: MapController?) {
    controller?.updateCompassOffset(0, 0)
    controller?.view?.postInvalidate()
}

private fun Context.hasMapLocationPermission(): Boolean {
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

private fun Context.getBestKnownMapLocation(): Location? {
    if (!hasMapLocationPermission()) return null
    val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
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
    }.maxByOrNull { it.time }
}

private fun findDownloadableRegion(requestedRegions: Set<String>): String? {
    if (!ConnectionState.INSTANCE.isConnected()) {
        return null
    }
    if (!isDownloadPromptZoomAllowed()) {
        return null
    }

    return runCatching {
        if (Framework.nativeIsDownloadedMapAtScreenCenter()) {
            return@runCatching null
        }

        val center = Framework.nativeGetScreenRectCenter()
        val latitude = center.getOrNull(0) ?: return@runCatching null
        val longitude = center.getOrNull(1) ?: return@runCatching null
        val countryId = MapManager.nativeFindCountry(latitude, longitude) ?: return@runCatching null
        if (countryId.isBlank()) {
            return@runCatching null
        }

        countryId.takeUnless { it in requestedRegions }
    }.onFailure { error ->
        Log.w(TAG_MAP, "Unable to find downloadable map region", error)
    }.getOrNull()
}

private fun isDownloadPromptZoomAllowed(): Boolean {
    return runCatching {
        Framework.nativeGetDrawScale() >= MIN_DOWNLOAD_PROMPT_ZOOM
    }.onFailure { error ->
        Log.w(TAG_MAP, "Unable to read map zoom", error)
    }.getOrDefault(false)
}

private fun startRegionDownload(regionId: String, requestedRegions: MutableSet<String>): String? {
    if (!ConnectionState.INSTANCE.isConnected()) {
        return null
    }

    return runCatching {
        if (ConnectionState.INSTANCE.isMobileConnected()) {
            MapManager.nativeEnableDownloadOn3g()
        }
        if (requestedRegions.add(regionId)) {
            MapManager.startDownload(regionId)
            "Downloading map region"
        } else {
            "Map region queued"
        }
    }.onFailure { error ->
        requestedRegions.remove(regionId)
        Log.w(TAG_MAP, "Unable to start map region download", error)
    }.getOrNull()
}
