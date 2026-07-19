package ai.edgez.edgez

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val EDGEZ_MARKETPLACE_URL = "https://www.edgez.ai/mobile/marketplace"

@Composable
fun DriversScreen(
    installRequest: MarketplaceDriverInstallRequest? = null,
    onInstallHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pendingInstall by remember(installRequest) { mutableStateOf<MarketplaceDriver?>(null) }
    var installError by remember(installRequest) { mutableStateOf<String?>(null) }
    var loadingInstall by remember(installRequest) { mutableStateOf(false) }
    val installScope = rememberCoroutineScope()
    val uartI2cDrivers = remember(context, installRequest) {
        DeviceSensorCatalog.sensorDefinitionsFor(context, DeviceSensorConnector.UART_I2C)
            .filter { it.key.isNotBlank() }
    }
    val rs485Drivers = remember(context, installRequest) {
        DeviceSensorCatalog.sensorDefinitionsFor(context, DeviceSensorConnector.RS485)
            .filter { it.key.isNotBlank() }
    }

    LaunchedEffect(installRequest) {
        if (installRequest == null) return@LaunchedEffect
        loadingInstall = true
        installError = null
        pendingInstall = null
        runCatching {
            withContext(Dispatchers.IO) { fetchMarketplaceDriver(installRequest) }
        }.onSuccess { driver ->
            pendingInstall = driver
        }.onFailure { error ->
            installError = error.message ?: "Unable to download the marketplace driver"
        }
        loadingInstall = false
    }

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Drivers", style = MaterialTheme.typography.headlineMedium)
                    Button(onClick = { openCustomTab(context, EDGEZ_MARKETPLACE_URL) }) {
                        Text("Marketplace")
                    }
                }
            }
            item {
                Text("UART / I2C", style = MaterialTheme.typography.titleMedium)
            }
            items(uartI2cDrivers.size) { index ->
                SensorInfoCard(sensor = uartI2cDrivers[index])
            }
            item {
                Text("RS485", style = MaterialTheme.typography.titleMedium)
            }
            items(rs485Drivers.size) { index ->
                SensorInfoCard(sensor = rs485Drivers[index])
            }
        }
    }

    when {
        loadingInstall -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Preparing driver") },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
        pendingInstall != null -> {
            val driver = pendingInstall ?: return
            AlertDialog(
                onDismissRequest = onInstallHandled,
                title = { Text("Install ${driver.name}?") },
                text = {
                    Text("This adds the ${driver.connector.name.replace('_', ' ')} driver to this app. You can then select it when configuring a connected device.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            installScope.launch {
                                pendingInstall = null
                                loadingInstall = true
                                runCatching {
                                    withContext(Dispatchers.IO) { installMarketplaceDriver(context, driver) }
                                }.onSuccess {
                                    onInstallHandled()
                                }.onFailure { error ->
                                    installError = error.message ?: "Unable to install the driver"
                                }
                                loadingInstall = false
                            }
                        },
                    ) {
                        Text("Install")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onInstallHandled) { Text("Cancel") }
                },
            )
        }
        installError != null -> AlertDialog(
            onDismissRequest = onInstallHandled,
            title = { Text("Driver install failed") },
            text = { Text(installError ?: "Unable to install the driver") },
            confirmButton = {
                TextButton(onClick = onInstallHandled) { Text("Close") }
            },
        )
    }
}

private fun openCustomTab(context: android.content.Context, url: String) {
    CustomTabsIntent.Builder()
        .setShowTitle(false)
        .build()
        .launchUrl(context, Uri.parse(url))
}
