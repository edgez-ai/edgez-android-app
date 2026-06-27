package ai.edgez.edgez

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.ui.theme.EdgeZTheme
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

private const val ACTION_USB_PERMISSION = "ai.edgez.edgez.USB_PERMISSION"
private const val ESPRESSIF_VID = 0x303A
private const val EDGEZ_MAGIC_0 = 'E'.code.toByte()
private const val EDGEZ_MAGIC_1 = 'Z'.code.toByte()
private const val EDGEZ_VERSION = 1.toByte()
private const val EDGEZ_TYPE_ECHO_REQ = 1.toByte()
private const val EDGEZ_TYPE_ECHO_RESP = 2
private const val EDGEZ_TYPE_ERROR = 0x7f
private const val EDGEZ_HEADER_LEN = 8
private const val EDGEZ_MAX_PAYLOAD = 256

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EdgeZTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UsbEchoApp()
                }
            }
        }
    }
}

private data class UsbCandidate(
    val device: UsbDevice,
    val intf: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint,
) {
    val label: String
        get() = "VID=%04x PID=%04x ${device.productName ?: "USB device"}".format(device.vendorId, device.productId)
}

private class EdgezUsbClient(private val context: Context) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var seq = 0

    fun scan(): List<UsbCandidate> {
        return usbManager.deviceList.values.mapNotNull { device ->
            findVendorInterface(device)?.let { (intf, inEp, outEp) ->
                UsbCandidate(device, intf, inEp, outEp)
            }
        }.sortedWith(compareBy({ if (it.device.vendorId == ESPRESSIF_VID) 0 else 1 }, { it.label }))
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val intent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, intent)
    }

    fun connect(candidate: UsbCandidate): String {
        close()
        if (!usbManager.hasPermission(candidate.device)) {
            return "Permission required"
        }

        val opened = usbManager.openDevice(candidate.device) ?: return "Open failed"
        if (!opened.claimInterface(candidate.intf, true)) {
            opened.close()
            return "Claim interface failed"
        }

        connection = opened
        claimedInterface = candidate.intf
        inEndpoint = candidate.inEndpoint
        outEndpoint = candidate.outEndpoint
        return "Connected to ${candidate.label}"
    }

    fun close() {
        val conn = connection
        val intf = claimedInterface
        if (conn != null && intf != null) {
            conn.releaseInterface(intf)
        }
        connection?.close()
        connection = null
        claimedInterface = null
        inEndpoint = null
        outEndpoint = null
    }

    fun echo(message: String, timeoutMs: Int = 1500): Result<String> {
        val conn = connection ?: return Result.failure(IllegalStateException("USB is not connected"))
        val outEp = outEndpoint ?: return Result.failure(IllegalStateException("Missing OUT endpoint"))
        val inEp = inEndpoint ?: return Result.failure(IllegalStateException("Missing IN endpoint"))

        val payload = message.toByteArray(StandardCharsets.UTF_8).let { bytes ->
            bytes.copyOf(min(bytes.size, EDGEZ_MAX_PAYLOAD))
        }
        val currentSeq = (seq++ and 0xffff)
        val tx = ByteBuffer.allocate(EDGEZ_HEADER_LEN + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        tx.put(EDGEZ_MAGIC_0)
        tx.put(EDGEZ_MAGIC_1)
        tx.put(EDGEZ_VERSION)
        tx.put(EDGEZ_TYPE_ECHO_REQ)
        tx.putShort(currentSeq.toShort())
        tx.putShort(payload.size.toShort())
        tx.put(payload)

        val txBytes = tx.array()
        val written = conn.bulkTransfer(outEp, txBytes, txBytes.size, timeoutMs)
        if (written != txBytes.size) {
            return Result.failure(IllegalStateException("USB write failed: $written/${txBytes.size}"))
        }

        val rx = ByteArray(EDGEZ_HEADER_LEN + EDGEZ_MAX_PAYLOAD)
        val read = conn.bulkTransfer(inEp, rx, rx.size, timeoutMs)
        if (read < EDGEZ_HEADER_LEN) {
            return Result.failure(IllegalStateException("USB read failed: $read"))
        }
        if (rx[0] != EDGEZ_MAGIC_0 || rx[1] != EDGEZ_MAGIC_1 || rx[2] != EDGEZ_VERSION) {
            return Result.failure(IllegalStateException("Bad response header"))
        }

        val responseType = rx[3].toInt() and 0xff
        val responseSeq = ByteBuffer.wrap(rx, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        val responseLen = ByteBuffer.wrap(rx, 6, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        if (responseSeq != currentSeq) {
            return Result.failure(IllegalStateException("Sequence mismatch: got $responseSeq expected $currentSeq"))
        }
        if (responseLen > read - EDGEZ_HEADER_LEN) {
            return Result.failure(IllegalStateException("Bad response length: $responseLen/$read"))
        }

        val text = String(rx, EDGEZ_HEADER_LEN, responseLen, StandardCharsets.UTF_8)
        return when (responseType) {
            EDGEZ_TYPE_ECHO_RESP -> Result.success(text)
            EDGEZ_TYPE_ERROR -> Result.failure(IllegalStateException("Device error: $text"))
            else -> Result.failure(IllegalStateException("Unexpected response type: $responseType"))
        }
    }

    private fun findVendorInterface(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC) {
                continue
            }
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue
                }
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    inEp = ep
                } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    outEp = ep
                }
            }
            if (inEp != null && outEp != null) {
                return Triple(intf, inEp, outEp)
            }
        }
        return null
    }
}

@Composable
private fun UsbEchoApp() {
    val context = LocalContext.current
    val client = remember { EdgezUsbClient(context.applicationContext) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var candidates by remember { mutableStateOf(client.scan()) }
    var selected by remember { mutableStateOf<UsbCandidate?>(candidates.firstOrNull()) }
    var message by remember { mutableStateOf("hello esp32s3") }
    var status by remember { mutableStateOf("Connect the ESP32-S3 USB port, then scan.") }
    var response by remember { mutableStateOf("") }
    var log by remember { mutableStateOf(listOf<String>()) }

    fun appendLog(line: String) {
        log = (listOf(line) + log).take(16)
    }

    DisposableEffect(Unit) {
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
            client.close()
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
                    status = "Found ${candidates.size} USB vendor device(s)"
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
                status = "Sending echo..."
                appendLog(status)
                executor.execute {
                    val result = client.echo(message)
                    (context as? ComponentActivity)?.runOnUiThread {
                        result.fold(
                            onSuccess = {
                                response = it
                                status = "Echo OK (${it.length} chars)"
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
private fun DeviceList(
    candidates: List<UsbCandidate>,
    selected: UsbCandidate?,
    onSelect: (UsbCandidate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("USB devices", style = MaterialTheme.typography.titleMedium)
        if (candidates.isEmpty()) {
            Text("No vendor USB interfaces found.")
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

@Composable
private fun ResponseCard(response: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Response", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(response.ifBlank { "-" }, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun LogCard(log: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Log", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            LazyColumn(modifier = Modifier.height(140.dp)) {
                items(log) { line -> Text(line, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun UsbEchoPreview() {
    EdgeZTheme {
        UsbEchoApp()
    }
}
