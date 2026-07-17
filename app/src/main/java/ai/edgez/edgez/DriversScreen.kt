package ai.edgez.edgez

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val EDGEZ_MARKETPLACE_URL = "https://www.edgez.ai/mobile/marketplace"
private const val EDGEZ_EDITOR_URL = "https://www.edgez.ai/mobile/editor"

@Composable
fun DriversScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var webViewUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val uartI2cDrivers = remember(context) {
        DeviceSensorCatalog.sensorDefinitionsFor(context, DeviceSensorConnector.UART_I2C)
            .filter { it.key.isNotBlank() }
    }
    val rs485Drivers = remember(context) {
        DeviceSensorCatalog.sensorDefinitionsFor(context, DeviceSensorConnector.RS485)
            .filter { it.key.isNotBlank() }
    }

    webViewUrl?.let { url ->
        DriversWebViewScreen(
            url = url,
            onBack = { webViewUrl = null },
            modifier = modifier,
        )
        return
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
                Text("Drivers", style = MaterialTheme.typography.headlineMedium)
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { webViewUrl = EDGEZ_MARKETPLACE_URL },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Marketplace")
                    }
                    Button(
                        onClick = { webViewUrl = EDGEZ_EDITOR_URL },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Editor")
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
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriversWebViewScreen(
    url: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember(context) {
        WebView(context).apply {
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            loadUrl(url)
        }
    }
    val navigateBack = {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    BackHandler(onBack = navigateBack)
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    TextButton(onClick = navigateBack) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}
