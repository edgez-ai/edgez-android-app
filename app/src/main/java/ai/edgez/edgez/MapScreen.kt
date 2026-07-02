package ai.edgez.edgez

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
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

private const val TAG_MAP = "EdgeZMap"
private const val DEFAULT_MAP_ZOOM = 16

@Composable
fun MapScreen(users: List<HaLowUser>) {
    val context = LocalContext.current
    val application = context.applicationContext as EdgeZApplication
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val targetUser by rememberUpdatedState(users.firstOrNull { it.hasLocation() })

    var initialized by remember { mutableStateOf(application.organicMaps.arePlatformAndCoreInitialized()) }
    var status by remember { mutableStateOf("Starting offline map") }
    var controller by remember { mutableStateOf<MapController?>(null) }

    LaunchedEffect(Unit) {
        application.initializeOrganicMaps {
            initialized = true
            status = "Offline map ready"
        }.onSuccess {
            if (application.organicMaps.arePlatformAndCoreInitialized()) {
                initialized = true
                status = "Offline map ready"
            }
        }.onFailure { error ->
            status = "Map unavailable: ${error.message ?: error::class.java.simpleName}"
            Log.e(TAG_MAP, "Organic Maps initialization failed", error)
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
                            controller = MapController(
                                mapView,
                                application.organicMaps.locationHelper,
                                object : MapRenderingListener {
                                    override fun onRenderingCreated() {
                                        status = "Offline map ready"
                                        centerOnTarget(targetUser)
                                    }

                                    override fun onRenderingRestored() {
                                        centerOnTarget(targetUser)
                                    }

                                    override fun onRenderingInitializationFinished() {
                                        centerOnTarget(targetUser)
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

            MapStatusOverlay(
                status = status,
                targetUser = targetUser,
                locatedUsers = users.count { it.hasLocation() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun MapStatusOverlay(
    status: String,
    targetUser: HaLowUser?,
    locatedUsers: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = targetUser?.displayName ?: "No node location",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(status) },
                )
                AssistChip(
                    modifier = Modifier.padding(start = 8.dp),
                    onClick = {},
                    label = { Text("$locatedUsers pins") },
                )
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
