package ai.edgez.edgez

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.ble.EdgezBleClient
import ai.edgez.edgez.usb.EDGEZ_HEADER_LEN
import ai.edgez.edgez.usb.EDGEZ_MAGIC_0
import ai.edgez.edgez.usb.EDGEZ_MAGIC_1
import ai.edgez.edgez.usb.EDGEZ_MAX_PAYLOAD
import ai.edgez.edgez.usb.EDGEZ_TYPE_CONTROL_RESP
import ai.edgez.edgez.usb.EDGEZ_TYPE_ECHO_RESP
import ai.edgez.edgez.usb.EDGEZ_TYPE_ERROR
import ai.edgez.edgez.usb.EDGEZ_TYPE_HALOW_SYNC_FROM_RADIO
import ai.edgez.edgez.usb.EDGEZ_TYPE_HALOW_SYNC_STATUS_RESP
import ai.edgez.edgez.usb.EDGEZ_VERSION
import ai.edgez.edgez.usb.EdgezUsbClient
import ai.edgez.edgez.usb.EdgezUsbControlProto
import ai.edgez.edgez.usb.USB_CONTROL_ACTION_ECHO
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

@Composable
fun DebugScreen(
    client: EdgezUsbClient,
    bleClient: EdgezBleClient,
    activeConnection: ActiveConnection,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var echoPayload by rememberSaveable { mutableStateOf("hello esp32s3") }
    var status by remember { mutableStateOf("Connect USB or BLE in Settings, then send an echo.") }
    var response by remember { mutableStateOf("") }
    var log by remember { mutableStateOf(listOf<String>()) }
    val activity = context as? ComponentActivity
    val currentActiveConnection by rememberUpdatedState(activeConnection)

    fun appendLog(line: String) {
        log = (listOf(line) + log).take(200)
    }

    fun handleFrame(source: ActiveConnection, frame: ByteArray) {
        if (frame.size < EDGEZ_HEADER_LEN ||
            frame[0] != EDGEZ_MAGIC_0 ||
            frame[1] != EDGEZ_MAGIC_1 ||
            frame[2] != EDGEZ_VERSION) {
            return
        }

        val responseType = frame[3].toInt() and 0xff
        val responseSeq = ByteBuffer.wrap(frame, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        val responseLen = ByteBuffer.wrap(frame, 6, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        if (responseLen > EDGEZ_MAX_PAYLOAD || EDGEZ_HEADER_LEN + responseLen > frame.size) {
            return
        }

        val selectedConnection = currentActiveConnection
        if (selectedConnection == ActiveConnection.NONE) {
            appendLog("${source.name} RX ignored; no interface selected")
            return
        }
        if (source != selectedConnection) {
            appendLog("${source.name} RX ignored; interface is ${selectedConnection.name}")
            return
        }

        val payload = frame.copyOfRange(EDGEZ_HEADER_LEN, EDGEZ_HEADER_LEN + responseLen)
        val text = String(payload, StandardCharsets.UTF_8)
        activity?.runOnUiThread {
            when (responseType) {
                EDGEZ_TYPE_CONTROL_RESP -> {
                    val control = EdgezUsbControlProto.decodeResponse(payload)
                    val controlHaLowStatus = control?.halowStatus
                    if (control?.action == USB_CONTROL_ACTION_ECHO) {
                        response = control.echoPayload
                        status = "${source.name} protobuf echo RX seq=$responseSeq: ${control.echoPayload}"
                    } else if (controlHaLowStatus != null) {
                        status = "${source.name} control seq=$responseSeq: ${controlHaLowStatus.summary()}"
                    } else {
                        status = "${source.name} control seq=$responseSeq: ${control?.message ?: "malformed"}"
                    }
                }
                EDGEZ_TYPE_HALOW_SYNC_FROM_RADIO, EDGEZ_TYPE_HALOW_SYNC_STATUS_RESP -> {
                    val halowMessage = decodeHaLowSyncFrame(frame)
                    val halowStatus = halowMessage?.halowStatus ?: decodeHaLowStatusFrame(frame)
                    status = if (halowMessage != null) {
                        "${source.name} HaLow sync RX seq=$responseSeq: ${halowMessage.summary()}"
                    } else if (halowStatus != null) {
                        "${source.name} HaLow sync RX seq=$responseSeq: ${halowStatus.summary()}"
                    } else {
                        "${source.name} malformed HaLow sync RX seq=$responseSeq type=$responseType"
                    }
                }
                EDGEZ_TYPE_ECHO_RESP -> {
                    response = text
                    status = "${source.name} echo RX seq=$responseSeq: $text"
                }
                EDGEZ_TYPE_ERROR -> {
                    status = "${source.name} device error on seq=$responseSeq: $text"
                }
                else -> {
                    status = "${source.name} packet on seq=$responseSeq: type=$responseType"
                }
            }
            appendLog(status)
        }
    }

    DisposableEffect(Unit) {
        val removeFrameListener = client.addFrameListener { frame ->
            handleFrame(ActiveConnection.USB, frame)
        }
        val removeBleFrameListener = bleClient.addFrameListener { frame ->
            handleFrame(ActiveConnection.BLE, frame)
        }
        val removeDebugListener = client.addDebugListener { line ->
            activity?.runOnUiThread {
                appendLog("USB $line")
            }
        }
        val removeBleDebugListener = bleClient.addDebugListener { line ->
            activity?.runOnUiThread {
                appendLog("BLE $line")
            }
        }
        onDispose {
            removeFrameListener()
            removeBleFrameListener()
            removeDebugListener()
            removeBleDebugListener()
            executor.shutdownNow()
        }
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Debug", style = MaterialTheme.typography.headlineMedium)
        Text("Interface: ${activeConnection.name}", style = MaterialTheme.typography.bodyMedium)
        Text(status, style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            value = echoPayload,
            onValueChange = { echoPayload = it.take(128) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Echo payload") },
            singleLine = true,
        )

        Button(onClick = {
            val sentText = echoPayload
            status = "Sending echo..."
            appendLog(status)
            executor.execute {
                val selectedConnection = currentActiveConnection
                val result = when (selectedConnection) {
                    ActiveConnection.BLE -> bleClient.sendEcho(sentText)
                    ActiveConnection.USB -> client.sendEcho(sentText)
                    ActiveConnection.NONE -> Result.failure(IllegalStateException("No TX connection"))
                }
                activity?.runOnUiThread {
                    result.fold(
                        onSuccess = {
                            response = sentText
                            status = "Protobuf echo sent (${sentText.length} chars): $sentText via ${selectedConnection.name}"
                            appendLog(status)
                        },
                        onFailure = {
                            status = it.message ?: "Echo failed"
                            appendLog(status)
                        },
                    )
                }
            }
        }) {
            Text("Send Echo")
        }

        ResponseCard(response)
        Spacer(Modifier.height(4.dp))
        LogCard(log)
        Button(onClick = onClose) {
            Text("Close")
        }
    }
}
