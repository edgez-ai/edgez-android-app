package ai.edgez.edgez

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.ble.BleCandidate
import ai.edgez.edgez.ble.EdgezBleClient
import ai.edgez.edgez.ui.theme.EdgeZTheme
import ai.edgez.edgez.usb.ACTION_USB_PERMISSION
import ai.edgez.edgez.usb.EDGEZ_HEADER_LEN
import ai.edgez.edgez.usb.EDGEZ_MAGIC_0
import ai.edgez.edgez.usb.EDGEZ_MAGIC_1
import ai.edgez.edgez.usb.EDGEZ_MAX_PAYLOAD
import ai.edgez.edgez.usb.EDGEZ_TYPE_CONTROL_RESP
import ai.edgez.edgez.usb.EDGEZ_TYPE_ECHO_RESP
import ai.edgez.edgez.usb.EDGEZ_TYPE_ERROR
import ai.edgez.edgez.usb.EDGEZ_VERSION
import ai.edgez.edgez.usb.EdgezUsbClient
import ai.edgez.edgez.usb.EdgezUsbControlProto
import ai.edgez.edgez.usb.USB_CONTROL_ACTION_ECHO
import ai.edgez.edgez.usb.UsbCandidate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

@Composable
fun UsbEchoScreen(
    client: EdgezUsbClient,
    onConnectedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val executor = remember { Executors.newSingleThreadExecutor() }
    val currentOnConnectedChange by rememberUpdatedState(onConnectedChange)
    var candidates by remember { mutableStateOf(client.scan()) }
    var selected by remember { mutableStateOf<UsbCandidate?>(candidates.firstOrNull()) }
    var message by rememberSaveable { mutableStateOf("hello esp32s3") }
    var status by remember { mutableStateOf("Connect the ESP32-S3 USB port, then scan.") }
    var response by remember { mutableStateOf("") }
    var log by remember { mutableStateOf(listOf<String>()) }

    fun appendLog(line: String) {
        log = (listOf(line) + log).take(200)
    }

    fun handleFrame(frame: ByteArray) {
        decodeEchoFrame(frame)?.let { decoded ->
            activity?.runOnUiThread {
                response = decoded.echoText
                status = "USB ${decoded.label}"
                appendLog(status)
            }
        }
    }

    DisposableEffect(Unit) {
        val removeFrameListener = client.addFrameListener(::handleFrame)
        val removeDebugListener = client.addDebugListener { line ->
            activity?.runOnUiThread {
                appendLog("USB $line")
                if (line == "CLOSE") {
                    currentOnConnectedChange(false)
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
                appendLog(status)
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), flags)
        onDispose {
            context.unregisterReceiver(receiver)
            removeFrameListener()
            removeDebugListener()
            executor.shutdownNow()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("EdgeZ USB", style = MaterialTheme.typography.headlineMedium)
            Text(status, style = MaterialTheme.typography.bodyMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    candidates = client.scan()
                    selected = candidates.firstOrNull()
                    status = "Found ${candidates.size} USB device interface(s)"
                    appendLog(status)
                }) { Text("Scan") }
                Button(enabled = selected != null, onClick = {
                    val candidate = selected ?: return@Button
                    if (!client.hasPermission(candidate.device)) {
                        client.requestPermission(candidate.device)
                        status = "Requesting USB permission"
                        appendLog(status)
                    } else {
                        status = client.connect(candidate)
                        currentOnConnectedChange(client.isConnected())
                        appendLog(status)
                    }
                }) { Text("Connect") }
            }

            DeviceList(candidates, selected) { selected = it }

            OutlinedTextField(
                value = message,
                onValueChange = { message = it.take(EDGEZ_MAX_PAYLOAD) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Echo payload") },
                singleLine = true,
            )

            Button(onClick = {
                val sentText = message.take(EDGEZ_MAX_PAYLOAD)
                status = "Sending echo..."
                appendLog(status)
                executor.execute {
                    val result = client.sendEcho(sentText)
                    activity?.runOnUiThread {
                        result.fold(
                            onSuccess = {
                                response = sentText
                                status = "Echo OK (${sentText.length} chars): $sentText"
                                appendLog(status)
                            },
                            onFailure = {
                                status = it.message ?: "Echo failed"
                                appendLog(status)
                            },
                        )
                    }
                }
            }) { Text("Send Echo") }

            ResponseCard(response)
            LogCard(log)
        }
    }
}

@Composable
fun BleEchoScreen(
    client: EdgezBleClient,
    onConnectedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val executor = remember { Executors.newSingleThreadExecutor() }
    val currentOnConnectedChange by rememberUpdatedState(onConnectedChange)
    var candidates by remember { mutableStateOf(listOf<BleCandidate>()) }
    var selected by remember { mutableStateOf<BleCandidate?>(null) }
    var message by rememberSaveable { mutableStateOf("hello esp32s3") }
    var status by remember { mutableStateOf("Enable BLE on the device, then scan.") }
    var response by remember { mutableStateOf("") }
    var log by remember { mutableStateOf(listOf<String>()) }

    fun appendLog(line: String) {
        log = (listOf(line) + log).take(200)
    }

    fun requestBlePermissions() {
        activity?.requestPermissions(client.requiredPermissions(), 2001)
        status = "Requesting BLE permission"
        appendLog(status)
    }

    fun handleFrame(frame: ByteArray) {
        decodeEchoFrame(frame)?.let { decoded ->
            activity?.runOnUiThread {
                response = decoded.echoText
                status = "BLE ${decoded.label}"
                appendLog(status)
            }
        }
    }

    DisposableEffect(Unit) {
        val removeFrameListener = client.addFrameListener(::handleFrame)
        val removeDebugListener = client.addDebugListener { line ->
            activity?.runOnUiThread {
                appendLog("BLE $line")
                if (line == "SERVICE ready") {
                    currentOnConnectedChange(true)
                    status = "BLE connected"
                    appendLog(status)
                } else if ((line.startsWith("CONN") && line.contains("state=0")) || line == "CLOSE") {
                    currentOnConnectedChange(false)
                }
            }
        }
        onDispose {
            removeFrameListener()
            removeDebugListener()
            executor.shutdownNow()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("EdgeZ BLE", style = MaterialTheme.typography.headlineMedium)
            Text(status, style = MaterialTheme.typography.bodyMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!client.hasPermissions()) {
                        requestBlePermissions()
                    } else {
                        candidates = emptyList()
                        selected = null
                        val result = client.startScan { candidate ->
                            activity?.runOnUiThread {
                                if (candidates.none { it.device.address == candidate.device.address }) {
                                    candidates = (candidates + candidate).sortedBy { it.label }
                                }
                                selected = selected ?: candidate
                            }
                        }
                        result.fold(
                            onSuccess = {
                                status = it
                                appendLog(status)
                            },
                            onFailure = {
                                status = it.message ?: "BLE scan failed"
                                appendLog(status)
                            },
                        )
                    }
                }) { Text("Scan") }
                Button(onClick = {
                    client.stopScan()
                    status = "BLE scan stopped"
                    appendLog(status)
                }) { Text("Stop") }
                Button(enabled = selected != null, onClick = {
                    val candidate = selected ?: return@Button
                    client.stopScan()
                    val result = client.connect(candidate)
                    result.fold(
                        onSuccess = {
                            status = it
                            appendLog(status)
                        },
                        onFailure = {
                            status = it.message ?: "BLE connect failed"
                            appendLog(status)
                        },
                    )
                }) { Text("Connect") }
            }

            BleDeviceList(candidates, selected) { selected = it }

            OutlinedTextField(
                value = message,
                onValueChange = { message = it.take(128) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Echo payload") },
                singleLine = true,
            )

            Button(onClick = {
                val sentText = message.take(128)
                status = "Sending echo..."
                appendLog(status)
                executor.execute {
                    val result = client.sendEcho(sentText)
                    activity?.runOnUiThread {
                        result.fold(
                            onSuccess = {
                                response = sentText
                                status = "Echo OK (${sentText.length} chars): $sentText"
                                appendLog(status)
                            },
                            onFailure = {
                                status = it.message ?: "Echo failed"
                                appendLog(status)
                            },
                        )
                    }
                }
            }) { Text("Send Echo") }

            ResponseCard(response)
            LogCard(log)
        }
    }
}

@Composable
private fun BleDeviceList(
    candidates: List<BleCandidate>,
    selected: BleCandidate?,
    onSelect: (BleCandidate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("BLE devices", style = MaterialTheme.typography.titleSmall)
        if (candidates.isEmpty()) {
            Text("No EdgeZ BLE devices found.")
        } else {
            candidates.forEach { candidate ->
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelect(candidate) },
                ) {
                    Text(if (candidate == selected) "Selected: ${candidate.label}" else candidate.label)
                }
            }
        }
    }
}

private data class DecodedEchoFrame(
    val echoText: String,
    val label: String,
)

private fun decodeEchoFrame(frame: ByteArray): DecodedEchoFrame? {
    if (frame.size < EDGEZ_HEADER_LEN ||
        frame[0] != EDGEZ_MAGIC_0 ||
        frame[1] != EDGEZ_MAGIC_1 ||
        frame[2] != EDGEZ_VERSION) {
        return null
    }

    val responseType = frame[3].toInt() and 0xff
    val responseSeq = ByteBuffer.wrap(frame, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    val responseLen = ByteBuffer.wrap(frame, 6, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    if (responseLen > EDGEZ_MAX_PAYLOAD || EDGEZ_HEADER_LEN + responseLen > frame.size) {
        return null
    }

    val payload = frame.copyOfRange(EDGEZ_HEADER_LEN, EDGEZ_HEADER_LEN + responseLen)
    return when (responseType) {
        EDGEZ_TYPE_CONTROL_RESP -> {
            val control = EdgezUsbControlProto.decodeResponse(payload) ?: return DecodedEchoFrame(
                echoText = "",
                label = "malformed control response seq=$responseSeq",
            )
            if (control.action == USB_CONTROL_ACTION_ECHO) {
                DecodedEchoFrame(
                    echoText = control.echoPayload,
                    label = "RX seq=$responseSeq: ${control.echoPayload}",
                )
            } else {
                DecodedEchoFrame(
                    echoText = control.message,
                    label = "control seq=$responseSeq: ${control.message.ifBlank { "OK" }}",
                )
            }
        }
        EDGEZ_TYPE_ECHO_RESP -> {
            val text = String(payload, StandardCharsets.UTF_8)
            DecodedEchoFrame(text, "debug echo seq=$responseSeq: $text")
        }
        EDGEZ_TYPE_ERROR -> {
            val text = String(payload, StandardCharsets.UTF_8)
            DecodedEchoFrame(text, "device error on seq=$responseSeq: $text")
        }
        else -> DecodedEchoFrame("", "packet on seq=$responseSeq: type=$responseType")
    }
}

@Preview(showBackground = true)
@Composable
private fun UsbEchoScreenPreview() {
    EdgeZTheme {
        UsbEchoScreen(
            client = EdgezUsbClient(LocalContext.current.applicationContext),
            onConnectedChange = {},
        )
    }
}
