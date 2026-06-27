package ai.edgez.edgez

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.usb.EdgezUsbClient

@PreviewScreenSizes
@Composable
fun EdgeZApp() {
    val context = LocalContext.current
    val usbClient = remember { EdgezUsbClient(context.applicationContext) }
    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.HOME) }

    DisposableEffect(Unit) {
        onDispose {
            usbClient.setFrameListener(null)
            usbClient.close()
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestination.entries.forEach { destination ->
                item(
                    icon = {
                        Icon(
                            painter = painterResource(destination.icon),
                            contentDescription = destination.label,
                        )
                    },
                    label = { Text(destination.label) },
                    selected = destination == currentDestination,
                    onClick = { currentDestination = destination },
                )
            }
        },
    ) {
        when (currentDestination) {
            AppDestination.HOME -> HomeScreen(usbClient)
            AppDestination.FAVORITES -> PlaceholderScreen("Favorites")
            AppDestination.PROFILE -> PlaceholderScreen("Profile")
            AppDestination.SETTINGS -> SettingsScreen(usbClient)
        }
    }
}

private enum class AppDestination(
    val label: String,
    val icon: Int,
) {
    HOME("Home", R.drawable.ic_home),
    FAVORITES("Favorites", R.drawable.ic_favorite),
    PROFILE("Profile", R.drawable.ic_account_box),
    SETTINGS("Settings", R.drawable.ic_usb),
}

@Composable
private fun PlaceholderScreen(title: String) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Text(
            text = title,
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
