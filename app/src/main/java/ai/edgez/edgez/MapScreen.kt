package ai.edgez.edgez

import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
private const val DEFAULT_MAP_ZOOM = 12
private const val REGION_AUTOCACHE_INTERVAL_MS = 3_500L
private const val REGION_AUTOCACHE_INITIAL_DELAY_MS = 5_000L

@Composable
fun MapScreen(users: List<HaLowUser>) {
    val context = LocalContext.current
    val application = context.applicationContext as EdgeZApplication
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val locatedUsers = users.filter { it.hasLocation() }
    val targetUser = locatedUsers.firstOrNull()
    val targetUserState by rememberUpdatedState(targetUser)

    var initialized by remember { mutableStateOf(application.organicMaps.arePlatformAndCoreInitialized()) }
    var status by remember { mutableStateOf("Starting offline map") }
    var controller by remember { mutableStateOf<MapController?>(null) }
    var pendingRegionId by remember { mutableStateOf<String?>(null) }
    val requestedRegions = remember { mutableSetOf<String>() }

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

    LaunchedEffect(initialized, controller) {
        if (!initialized || controller == null) {
            return@LaunchedEffect
        }

        runCatching { Framework.nativeRestoreDownloadQueue() }
        delay(REGION_AUTOCACHE_INITIAL_DELAY_MS)
        while (true) {
            findDownloadableRegion(requestedRegions)?.let { regionId ->
                pendingRegionId = regionId
                status = "Map region available to download"
            }
            delay(REGION_AUTOCACHE_INTERVAL_MS)
        }
    }

    LaunchedEffect(initialized, controller, targetUser?.nodeNum, targetUser?.latitude, targetUser?.longitude) {
        if (initialized && controller != null) {
            centerOnTarget(targetUser)
        }
    }

    LaunchedEffect(initialized, controller, locatedUsers) {
        if (initialized && controller != null) {
            updateUserMarkers(locatedUsers)
        }
    }

    DisposableEffect(initialized, controller) {
        onDispose {
            if (initialized && controller != null) {
                clearUserMarkers()
            }
        }
    }

    DisposableEffect(initialized) {
        val slot = if (initialized) {
            MapManager.nativeSubscribe(object : MapManager.StorageCallback {
                override fun onStatusChanged(data: List<MapManager.StorageCallbackData>) {
                    val event = data.lastOrNull() ?: return
                    val name = event.countryId
                    status = when (event.newStatus) {
                        CountryItem.STATUS_DONE -> "Offline map cached: $name"
                        CountryItem.STATUS_PROGRESS -> "Downloading map: $name"
                        CountryItem.STATUS_ENQUEUED -> "Queued map: $name"
                        CountryItem.STATUS_FAILED -> {
                            requestedRegions.remove(event.countryId)
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
                                    findDownloadableRegion(requestedRegions)?.let { regionId ->
                                        pendingRegionId = regionId
                                        status = "Map region available to download"
                                    }
                                }
                                false
                            }
                            controller = MapController(
                                mapView,
                                application.organicMaps.locationHelper,
                                object : MapRenderingListener {
                                    override fun onRenderingCreated() {
                                        status = "Offline map ready"
                                        centerOnTarget(targetUserState)
                                    }

                                    override fun onRenderingRestored() {
                                        centerOnTarget(targetUserState)
                                    }

                                    override fun onRenderingInitializationFinished() {
                                        centerOnTarget(targetUserState)
                                    }
                                },
                                {
                                    status = "Map rendering is not supported on this device"
                                },
                                false,
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
        }
    }
}

@Composable
private fun MapDownloadPrompt(
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
                text = "Download this map region?",
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

private fun centerOnTarget(user: HaLowUser?) {
    val latitude = user?.latitude ?: return
    val longitude = user.longitude ?: return
    runCatching {
        Framework.nativeStopLocationFollow()
        Framework.nativeSetViewportCenter(latitude, longitude, DEFAULT_MAP_ZOOM)
    }.onFailure { error ->
        Log.w(TAG_MAP, "Unable to center map on ${user.displayName}", error)
    }
}

private fun updateUserMarkers(users: List<HaLowUser>) {
    val coordinates = users.mapNotNull { user ->
        val latitude = user.latitude
        val longitude = user.longitude
        if (latitude != null && longitude != null) latitude to longitude else null
    }
    val latitudes = coordinates.map { it.first }.toDoubleArray()
    val longitudes = coordinates.map { it.second }.toDoubleArray()
    runCatching {
        Framework.nativeSetEdgeZUserMarkers(latitudes, longitudes)
    }.onFailure { error ->
        Log.w(TAG_MAP, "Unable to update user markers", error)
    }
}

private fun clearUserMarkers() {
    runCatching {
        Framework.nativeSetEdgeZUserMarkers(DoubleArray(0), DoubleArray(0))
    }.onFailure { error ->
        Log.w(TAG_MAP, "Unable to clear user markers", error)
    }
}

private fun findDownloadableRegion(requestedRegions: Set<String>): String? {
    if (!ConnectionState.INSTANCE.isConnected()) {
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
