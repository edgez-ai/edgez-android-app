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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.ui.theme.EdgeZTheme
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Arrays
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
private const val EDGEZ_MAX_FRAME = EDGEZ_HEADER_LEN + EDGEZ_MAX_PAYLOAD

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EdgeZTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EdgeZApp()
                }
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun EdgeZApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.USB) }

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
            AppDestination.USB -> UsbEchoApp()
            AppDestination.HOME -> PlaceholderScreen("Home")
            AppDestination.FAVORITES -> PlaceholderScreen("Favorites")
            AppDestination.PROFILE -> PlaceholderScreen("Profile")
        }
    }
}

private enum class AppDestination(
    val label: String,
    val icon: Int,
) {
    USB("USB", R.drawable.ic_usb),
    HOME("Home", R.drawable.ic_home),
    FAVORITES("Favorites", R.drawable.ic_favorite),
    PROFILE("Profile", R.drawable.ic_account_box),
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

private data class UsbCandidate(
    val device: UsbDevice,
    val intf: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint,
) {
    val label: String
        get() = "VID=%04x PID=%04x ${device.productName ?: "USB device"} if=${intf.id} ${outEndpoint.describe()} ${inEndpoint.describe()}"
            .format(device.vendorId, device.productId)
}

private fun UsbEndpoint.describe(): String {
    val directionName = if (direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
    return "%s ep=0x%02x max=%d".format(directionName, address, maxPacketSize)
}

private class EdgezUsbClient(private val context: Context) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    @Volatile
    private var frameListener: ((ByteArray) -> Unit)? = null
    private val rxBuffer = ByteArray(EDGEZ_MAX_FRAME * 4)
    private var rxLen = 0
    private var rxTaskRunning = false
    private var rxTask: Thread? = null
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
        startRxTask(opened, candidate.inEndpoint)
        return "Connected to ${candidate.label}"
    }

    fun close() {
        stopRxTask()
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

    fun setFrameListener(listener: (ByteArray) -> Unit) {
        frameListener = listener
    }

    private fun startRxTask(conn: UsbDeviceConnection, inEndpoint: UsbEndpoint) {
        stopRxTask()
        rxLen = 0
        rxTaskRunning = true
        rxTask = Thread {
            val scratch = ByteArray(EDGEZ_MAX_FRAME)
            while (rxTaskRunning) {
                val read = conn.bulkTransfer(inEndpoint, scratch, scratch.size, 100)
                if (!rxTaskRunning) {
                    break
                }
                if (read <= 0) {
                    continue
                }

                if (rxLen + read > rxBuffer.size) {
                    rxLen = 0
                }
                System.arraycopy(scratch, 0, rxBuffer, rxLen, read)
                rxLen += read

                while (rxLen >= EDGEZ_HEADER_LEN) {
                    val magicOffset = findMagicOffset(rxBuffer, rxLen)
                    if (magicOffset < 0) {
                        rxLen = 0
                        break
                    }
                    if (magicOffset > 0) {
                        System.arraycopy(rxBuffer, magicOffset, rxBuffer, 0, rxLen - magicOffset)
                        rxLen -= magicOffset
                    }

                    if (rxLen < EDGEZ_HEADER_LEN || rxBuffer[2] != EDGEZ_VERSION) {
                        break
                    }

                    val payloadLen = readLe16(rxBuffer, 6)
                    if (payloadLen > EDGEZ_MAX_PAYLOAD) {
                        rxLen = 0
                        break
                    }
                    val frameLen = EDGEZ_HEADER_LEN + payloadLen
                    if (rxLen < frameLen) {
                        break
                    }

                    val frame = Arrays.copyOf(rxBuffer, frameLen)
                    frameListener?.invoke(frame)
                    if (rxLen == frameLen) {
                        rxLen = 0
                    } else {
                        System.arraycopy(rxBuffer, frameLen, rxBuffer, 0, rxLen - frameLen)
                        rxLen -= frameLen
                    }
                }
            }
        }.also { thread ->
            thread.name = "edgez-usb-rx"
            thread.isDaemon = true
            thread.start()
        }
    }

    private fun stopRxTask() {
        rxTaskRunning = false
        val thread = rxTask
        if (thread != null && thread.isAlive) {
            thread.interrupt()
        }
        rxTask = null
    }

    private fun findMagicOffset(data: ByteArray, length: Int): Int {
        var i = 0
        while (i + 2 <= length) {
            if (data[i] == EDGEZ_MAGIC_0 && data[i + 1] == EDGEZ_MAGIC_1) {
                return i
            }
            i++
        }
        return -1
    }

    private fun readLe16(data: ByteArray, start: Int): Int {
        return (data[start].toInt() and 0xff) or ((data[start + 1].toInt() and 0xff) shl 8)
    }

    fun echo(message: String, timeoutMs: Int = 1500): Result<String> {
        val conn = connection ?: return Result.failure(IllegalStateException("USB is not connected"))
        val outEp = outEndpoint ?: return Result.failure(IllegalStateException("Missing OUT endpoint"))

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
            return Result.failure(IllegalStateException("USB write failed on ${outEp.describe()}: $written/${txBytes.size}"))
        }
        return Result.success("Sent")
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
    val activity = context as? ComponentActivity

    fun appendLog(line: String) {
        log = (listOf(line) + log).take(16)
    }

    fun handleFrame(frame: ByteArray) {
        if (frame.size < EDGEZ_HEADER_LEN || frame[0] != EDGEZ_MAGIC_0 || frame[1] != EDGEZ_MAGIC_1 || frame[2] != EDGEZ_VERSION) {
            return
        }

        val responseType = frame[3].toInt() and 0xff
        val responseSeq = ByteBuffer.wrap(frame, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        val responseLen = ByteBuffer.wrap(frame, 6, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        if (responseLen > EDGEZ_MAX_PAYLOAD || EDGEZ_HEADER_LEN + responseLen > frame.size) {
            return
        }

        val text = String(frame, EDGEZ_HEADER_LEN, responseLen, StandardCharsets.UTF_8)
        activity?.runOnUiThread {
            when (responseType) {
                EDGEZ_TYPE_ECHO_RESP -> {
                    response = text
                    status = "RX seq=$responseSeq: $text"
                }
                EDGEZ_TYPE_ERROR -> {
                    status = "Device error on seq=$responseSeq: $text"
                }
                else -> {
                    status = "Unknown packet on seq=$responseSeq: type=$responseType"
                }
            }
            appendLog(status)
        }
    }

    DisposableEffect(Unit) {
        client.setFrameListener(::handleFrame)
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
            client.setFrameListener { _ -> }
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
